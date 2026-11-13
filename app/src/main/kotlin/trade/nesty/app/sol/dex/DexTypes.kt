package trade.nesty.app.sol.dex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import trade.nesty.app.evm.Side
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.QuoteAsset
import trade.nesty.app.sol.SolAccount
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolRpc
import java.math.BigInteger

/** The pool programs Nesty trades on besides pump.fun's own. */
enum class DexKind { RAYDIUM_AMM, RAYDIUM_CPMM, LAUNCHLAB, METEORA_DAMM, METEORA_DBC, METEORA_DLMM, ORCA }

/**
 * A pool of `mint` against `quote` (wrapped SOL or a dollar stablecoin), as read from chain. Each
 * program adds the state its quotes and swaps need (src/background/solana/dex/types.ts).
 */
interface DexVenue {
    val dex: DexKind
    val pool: String

    /** The coin, and the token program it lives under. */
    val mint: String
    val tokenProgram: String

    /** What the coin is priced in, and paid with, in this pool. */
    val quote: QuoteAsset
}

/** What reading a pool needs besides its address. */
class DexLoadCtx(
    /** Accounts, read together with the other pools' reads of the moment. */
    val read: suspend (List<String>) -> List<SolAccount?>,
    /** The coin's token program, from its mint. */
    val coinProgram: String,
    /** A quote mint the pool may pay in (wrapped SOL, a stablecoin), else null. */
    val quoteAsset: suspend (String) -> QuoteAsset?,
)

/** The amounts a swap call's data names. */
data class SwapAmounts(val amountIn: BigInteger, val minOut: BigInteger)

/**
 * One pool program: reading its pools, quoting them exactly as the program will pay, and the one
 * swap instruction Nesty builds for it. The validator rebuilds that instruction from the amounts
 * in a call's data and accepts only an exact match.
 */
abstract class DexModule<V : DexVenue>(val dex: DexKind, val program: String, /** Compute a trade asks for before a simulation measures it. */ val units: Int) {
    /** Where compute grows with the trade (bins it crosses): the compute of this one. */
    open fun unitsFor(v: V, side: Side, amountIn: BigInteger): Int? = null

    /** Pools of `mint` found from its address alone (program-derived), to read and check. */
    open fun candidates(mint: String): List<String> = emptyList()

    /** The pool at `pool`, or null unless it is this program's pool of `mint` and a quote. */
    abstract suspend fun load(ctx: DexLoadCtx, pool: String, mint: String): V?

    /** The coin a pool account of this program trades, or null when it is none. */
    abstract fun coinOf(data: ByteArray): String?

    abstract fun label(v: V): String

    /** What the program pays for `amountIn`, fees included; 0 when it cannot fill it. */
    abstract fun quote(v: V, side: Side, amountIn: BigInteger): BigInteger

    /** Raw quote units per raw coin unit, before fees. */
    abstract fun spot(v: V): Double

    /** Why the pool cannot trade at `nowSec` (paused, not open yet), or null. */
    abstract fun idle(v: V, nowSec: Double): String?

    /** The program call: `amountIn` of what `side` spends, at least `minOut` back. */
    abstract fun swap(v: V, side: Side, user: String, amountIn: BigInteger, minOut: BigInteger): Instruction

    /** The amounts a call's data names, or null when it is not the swap `swap` builds. */
    abstract fun amounts(data: ByteArray): SwapAmounts?
}

/** Where a program would fail the swap; the quote becomes 0 (cannot fill). */
class Refused : Exception("refused")

fun refuse(): Nothing = throw Refused()

/** Runs a quote, 0 when the program would refuse the trade. */
inline fun quoteOrZero(f: () -> BigInteger): BigInteger = try {
    f()
} catch (_: Refused) {
    BigInteger.ZERO
}

/** The wallet's own token accounts a swap moves between: the quote's and the coin's (source, destination). */
fun userAccounts(v: DexVenue, side: Side, user: String): Pair<String, String> {
    val coin = SolAddr.associatedTokenAddress(user, v.mint, v.tokenProgram)
    val quote = SolAddr.associatedTokenAddress(user, v.quote.mint, v.quote.program)
    return if (side == Side.BUY) quote to coin else coin to quote
}

/** Mints a pool may price a coin in: wrapped SOL and the dollar stablecoins. */
fun isDexQuote(mint: String) = mint == SolAddr.WSOL_MINT || mint in SolAddr.STABLE_MINTS

/**
 * Reads asked for in the same moment go out as one `getMultipleAccounts` call, so the pools of a
 * coin cost one request between them.
 */
class BatchedReader(private val rpc: SolRpc, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val mutex = Mutex()
    private var queue: MutableList<Pair<List<String>, CompletableDeferred<Map<String, SolAccount?>>>>? = null

    suspend fun read(keys: List<String>): List<SolAccount?> {
        if (keys.isEmpty()) return emptyList()
        val done = CompletableDeferred<Map<String, SolAccount?>>()
        var start = false
        mutex.withLock {
            val q = queue ?: mutableListOf<Pair<List<String>, CompletableDeferred<Map<String, SolAccount?>>>>().also {
                queue = it
                start = true
            }
            q += keys to done
        }
        if (start) {
            scope.launch {
                // Let the other reads of this moment join.
                delay(1)
                val jobs = mutex.withLock { queue.also { queue = null } } ?: return@launch
                val all = jobs.flatMap { it.first }.distinct()
                try {
                    val accs = rpc.getMultipleAccounts(all)
                    val byKey = HashMap<String, SolAccount?>()
                    all.forEachIndexed { i, k -> byKey[k] = accs.getOrNull(i) }
                    jobs.forEach { it.second.complete(byKey) }
                } catch (e: Exception) {
                    jobs.forEach { it.second.completeExceptionally(e) }
                }
            }
        }
        val byKey = done.await()
        return keys.map { byKey[it] }
    }
}

/** Every pool program, and the one that owns an account. */
object Dex {
    val MODULES: List<DexModule<out DexVenue>> by lazy {
        listOf(RaydiumAmm, RaydiumCpmm, LaunchLab, MeteoraDamm, MeteoraDbc, MeteoraDlmm, Orca)
    }

    @Suppress("UNCHECKED_CAST")
    fun module(v: DexVenue): DexModule<DexVenue> = MODULES.first { it.dex == v.dex } as DexModule<DexVenue>

    /** The module whose program owns an account, if any. */
    @Suppress("UNCHECKED_CAST")
    fun moduleOf(owner: String?): DexModule<DexVenue>? = MODULES.firstOrNull { it.program == owner } as DexModule<DexVenue>?
}
