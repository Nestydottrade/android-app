package trade.nesty.app.sol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A transaction error (from simulation or a landed transaction) as a sentence for the trade.
 * Never echoes account data; unknown errors keep only their kind. Ported from
 * src/background/solana/errors.ts.
 */
object SolErrors {
    private const val SLIPPAGE = "Price moved past your slippage. Raise slippage or try again."

    /** pump.fun program errors a trade can hit (docs: PUMP_PROGRAM_README, IDL). */
    private val PUMP_ERRORS = mapOf(
        6002L to SLIPPAGE,
        6003L to SLIPPAGE,
        6005L to "The pump.fun curve is complete. Trading opens on PumpSwap once it migrates.",
        6021L to "Not enough tokens left on the curve.",
        6023L to "Not enough tokens to sell.",
        6040L to "Not enough SOL to cover the new accounts.",
        6041L to "Not enough SOL to cover the fees.",
        6042L to SLIPPAGE,
        // Nesty trades cashback coins with v2, mayhem pools with v1: these mean the coin changed.
        6094L to "pump.fun refused this trade for a cashback coin. Try again.",
    )

    private val PUMP_AMM_ERRORS = mapOf(
        6004L to SLIPPAGE,
        6040L to SLIPPAGE,
        6063L to "The pool cannot pay out that much right now.",
        6078L to "This pool is not a pump.fun pool.",
        6079L to "PumpSwap refused this trade for a cashback coin. Try again.",
        6080L to "PumpSwap refused this trade for a mayhem pool. Try again.",
        6084L to "The route between the pools broke. Try again.",
        6085L to "A curve on the route moved mid-trade. Try again.",
        6086L to "The route mixed buys and sells.",
    )

    fun explain(err: JsonElement?, instructions: List<Instruction>): String {
        if (err is JsonPrimitive && err.isString) return topLevel(err.content)
        if (err is JsonObject) {
            val (kind, value) = err.entries.firstOrNull() ?: return "Transaction failed."
            if (kind == "InstructionError" && value is JsonArray) {
                val index = (value.getOrNull(0) as? JsonPrimitive)?.intOrNull
                val inner = value.getOrNull(1)
                val program = index?.let { instructions.getOrNull(it)?.programId }
                val custom = ((inner as? JsonObject)?.get("Custom") as? JsonPrimitive)?.longOrNull
                if (custom != null) {
                    if (program == Pump.PUMP_PROGRAM) PUMP_ERRORS[custom]?.let { return it }
                    if (program == Pump.PUMP_AMM_PROGRAM) PUMP_AMM_ERRORS[custom]?.let { return it }
                    // Custom 1 is the system or token program's "insufficient funds", also when a
                    // trade runs into it moving tokens.
                    if (custom == 1L) {
                        if (program == SolAddr.SYSTEM_PROGRAM) return "Insufficient SOL balance."
                        if (program == SolAddr.TOKEN_PROGRAM || program == Pump.PUMP_PROGRAM || program == Pump.PUMP_AMM_PROGRAM) {
                            return "Insufficient balance for this trade."
                        }
                    }
                    return "Program error $custom."
                }
                if (inner is JsonPrimitive && inner.isString) return topLevel(inner.content)
            }
            if (kind == "InsufficientFundsForRent") return "Not enough SOL to keep the account open."
            return topLevel(kind)
        }
        return "Transaction failed."
    }

    private val LETTERS = Regex("^[A-Za-z]{1,40}$")

    private fun topLevel(code: String): String = when (code) {
        "InsufficientFundsForFee" -> "Not enough SOL for the network fee."
        "BlockhashNotFound" -> "Expired before it landed. Try again."
        "AccountNotFound" -> "This wallet has no SOL yet."
        "InsufficientFunds" -> "Insufficient SOL balance."
        "ComputationalBudgetExceeded", "ProgramFailedToComplete" -> "Ran out of compute. Try again."
        // Our own names for the runtime's error kinds: letters only, never data.
        else -> if (LETTERS.matches(code)) "Failed: $code." else "Transaction failed."
    }
}

/**
 * SOL in USD from Orca's SOL/USDC Whirlpool, the deepest SOL/USD pool on chain, cached briefly.
 * Ported from src/background/solana/price.ts.
 */
class SolPrice(
    private val rpc: SolRpc,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the one read at a time runs: a caller that stops waiting does not stop it for the rest. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile
    private var cached: Pair<Double, Long>? = null
    private val lock = Any()
    private var inflight: Deferred<Double?>? = null

    suspend fun usd(): Double? {
        cached?.let { (usd, at) -> if (now() - at < TTL_MS) return usd }
        val read = synchronized(lock) {
            inflight ?: scope.async {
                try {
                    load()
                } finally {
                    synchronized(lock) { inflight = null }
                }
            }.also { inflight = it }
        }
        return read.await()
    }

    private suspend fun load(): Double? = try {
        val usd = solUsdFromWhirlpool(rpc.getAccount(SOL_USD_POOL))
        if (usd != null) cached = usd to now()
        usd ?: cached?.first
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        cached?.first
    }

    companion object {
        /** Orca's SOL/USDC Whirlpool. */
        const val SOL_USD_POOL = "Czfq3xZZDmsdGdUyrNLtRhGc47cXcZtLG4crryfu44zE"
        private const val WHIRLPOOL_PROGRAM = "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"
        private const val TTL_MS = 20_000L

        /**
         * SOL in USD from the pool's sqrt price (Q64.64, USDC per SOL before decimals). Null for
         * anything that is not that pool, or a price no market would show.
         */
        fun solUsdFromWhirlpool(account: SolAccount?): Double? {
            if (account == null || account.owner != WHIRLPOOL_PROGRAM || account.data.size < 213) return null
            val d = account.data
            if (SolAddr.toAddress(d.copyOfRange(101, 133)) != SolAddr.WSOL_MINT) return null
            if (SolAddr.toAddress(d.copyOfRange(181, 213)) != SolAddr.USDC_MINT) return null
            val sqrt = Reader(d, 65).u128().toDouble() / Math.pow(2.0, 64.0)
            // SOL has 9 decimals, USDC 6.
            val usd = sqrt * sqrt * 1e3
            return if (usd.isFinite() && usd > 0.01 && usd < 1_000_000) usd else null
        }
    }
}

@Suppress("unused")
private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
