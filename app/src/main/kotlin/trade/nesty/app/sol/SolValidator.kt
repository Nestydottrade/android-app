package trade.nesty.app.sol

import trade.nesty.app.evm.BlockedAction
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.act
import trade.nesty.app.evm.shortAddress
import trade.nesty.app.sol.SolAddr.ATA_PROGRAM
import trade.nesty.app.sol.SolAddr.COMPUTE_BUDGET_PROGRAM
import trade.nesty.app.sol.SolAddr.SYSTEM_PROGRAM
import trade.nesty.app.sol.SolAddr.TOKEN_PROGRAM
import trade.nesty.app.sol.dex.Dex
import trade.nesty.app.sol.dex.DexVenue
import java.math.BigInteger

/** The pay currency, for amounts in the actions. */
data class PayUnit(val symbol: String, val decimals: Int)

/**
 * What one Solana transaction may do: trade `mint` on `route` from `wallet`, within the user's
 * limits. Ported from src/background/solana/validator.ts.
 */
data class SolValidationContext(
    val wallet: String,
    /** The token the user asked to trade: the route must end in it. */
    val mint: String,
    /** The route the trade was built on, as read from chain. */
    val route: SolRoute,
    val side: Side,
    /** Most the trade may spend, in the pay currency (from the USD limit). */
    val maxSpend: BigInteger,
    /** Most lamports the priority fee may cost (from the max network fee). */
    val maxPriorityLamports: BigInteger,
    /** The pay currency, for amounts in the actions. SOL when absent. */
    val payUnit: PayUnit? = null,
)

data class SolValidationResult(
    val ok: Boolean,
    val actions: List<BlockedAction>,
    /** What the trade spends in the pay currency, as the validator read it. */
    val spend: BigInteger,
    /** The amounts its trade call names: what goes in, and the least it takes back. */
    val amountIn: BigInteger,
    val minOut: BigInteger,
) {
    /** The first reason it was refused, for a message. */
    val reason: String? get() = actions.firstOrNull { !it.ok }?.reason
}

/**
 * Runs before EVERY Solana signature. Pure and synchronous, like the EVM validator: every
 * instruction must be one Nesty builds for this route, with the accounts derived here again from
 * the wallet and the route. Anything else is rejected.
 */
object SolValidator {
    private val SOL_UNIT = PayUnit("SOL", 9)

    /** Raw units as a decimal without trailing zeros, then the symbol. */
    fun units(raw: BigInteger, decimals: Int, symbol: String): String {
        val one = BigInteger.TEN.pow(decimals)
        val frac = raw.mod(one).toString().padStart(decimals, '0').trimEnd('0')
        return "${raw / one}${if (frac.isNotEmpty()) ".$frac" else ""} $symbol"
    }

    private fun sol(lamports: BigInteger) = units(lamports, 9, "SOL")

    /** An idempotent create of `owner`'s `mint` account, paid by `payer`. */
    private fun ataCreateKeys(payer: String, owner: String, mint: String, program: String): List<AccountMeta> = listOf(
        AccountMeta(payer, isSigner = true, isWritable = true),
        AccountMeta(SolAddr.associatedTokenAddress(owner, mint, program), isSigner = false, isWritable = true),
        AccountMeta(owner, isSigner = false, isWritable = false),
        AccountMeta(mint, isSigner = false, isWritable = false),
        AccountMeta(SYSTEM_PROGRAM, isSigner = false, isWritable = false),
        AccountMeta(program, isSigner = false, isWritable = false),
    )

    /** The compute budget a transaction asks for: one limit and one price at most. */
    private class Budget(var limit: BigInteger? = null, var price: BigInteger? = null)

    private fun readBudget(ix: Instruction, b: Budget, actions: MutableList<BlockedAction>) {
        val d = ix.data
        val tag = d.getOrNull(0)?.toInt()
        if (ix.keys.isNotEmpty()) {
            actions += act("Compute budget", "Unexpected accounts")
        } else if (tag == Spl.CU_SET_LIMIT && d.size == 5 && b.limit == null) {
            val limit = le(d, 1, 4)
            b.limit = limit
            actions += act("Compute limit $limit", if (limit > BigInteger.valueOf(Spl.MAX_COMPUTE_UNITS.toLong())) "Above the maximum" else null)
        } else if (tag == Spl.CU_SET_PRICE && d.size == 9 && b.price == null) {
            b.price = le(d, 1, 8)
        } else {
            actions += act("Compute budget", "Unknown or repeated instruction")
        }
    }

    private fun checkPriority(b: Budget, maxLamports: BigInteger, actions: MutableList<BlockedAction>) {
        val price = b.price ?: return
        val limit = b.limit ?: BigInteger.valueOf(200_000)
        val fee = (price * limit + BigInteger.valueOf(999_999)) / BigInteger.valueOf(1_000_000)
        actions += act("Priority fee ${sol(fee)}", if (fee > maxLamports) "Above your max network fee" else null)
    }

    private fun checkSigners(instructions: List<Instruction>, wallet: String, actions: MutableList<BlockedAction>) {
        for (k in instructions.flatMap { it.keys }) {
            if (k.isSigner && k.pubkey != wallet) actions += act("Signer ${shortAddress(k.pubkey)}", "Only your wallet may sign")
        }
    }

    /** An account a trade may open: the wallet's own (or a listed fee wallet's) for a mint. */
    private class Opening(val owner: String, val mint: String, val program: String, val label: String)

    /** The opening an associated-account instruction makes, if it is one of `opens`. */
    private fun opening(ix: Instruction, wallet: String, opens: List<Opening>): Opening? {
        val d = ix.data
        val owner = ix.keys.getOrNull(2)?.pubkey
        val mint = ix.keys.getOrNull(3)?.pubkey
        if (d.size != 1 || d[0].toInt() != Spl.ATA_CREATE_IDEMPOTENT) return null
        return opens.firstOrNull { o -> o.owner == owner && o.mint == mint && ix.keys == ataCreateKeys(wallet, o.owner, o.mint, o.program) }
    }

    /** What a system instruction wraps into the wallet's own WSOL account, or null. */
    private fun wrapAmount(ix: Instruction, wallet: String): BigInteger? {
        val d = ix.data
        val keys = listOf(
            AccountMeta(wallet, isSigner = true, isWritable = true),
            AccountMeta(SolAddr.wsolAccount(wallet), isSigner = false, isWritable = true),
        )
        return if (d.size == 12 && le(d, 0, 4) == BigInteger.valueOf(Spl.SYSTEM_TRANSFER.toLong()) && ix.keys == keys) le(d, 4, 8) else null
    }

    private enum class WsolStep { SYNC, CLOSE }

    /** A token instruction on the wallet's own WSOL account: syncing it, or closing it to the wallet. */
    private fun wsolStep(ix: Instruction, wallet: String): WsolStep? {
        val d = ix.data
        val wsol = SolAddr.wsolAccount(wallet)
        if (d.size != 1) return null
        if (d[0].toInt() == Spl.TOKEN_SYNC_NATIVE && ix.keys == listOf(AccountMeta(wsol, isSigner = false, isWritable = true))) return WsolStep.SYNC
        val close = listOf(
            AccountMeta(wsol, isSigner = false, isWritable = true),
            AccountMeta(wallet, isSigner = false, isWritable = true),
            AccountMeta(wallet, isSigner = true, isWritable = false),
        )
        if (d[0].toInt() == Spl.TOKEN_CLOSE_ACCOUNT && ix.keys == close) return WsolStep.CLOSE
        return null
    }

    private fun wsolAction(step: WsolStep?) = when (step) {
        WsolStep.SYNC -> act("Sync wrapped SOL")
        WsolStep.CLOSE -> act("Unwrap SOL")
        null -> act("Token program", "Only your own wrapped SOL account")
    }

    /** The validator for a route's trades: every instruction must be one Nesty builds for it. */
    fun validateTrade(instructions: List<Instruction>, ctx: SolValidationContext): SolValidationResult = when (val r = ctx.route) {
        is SolRoute.OnPump -> validatePumpTx(instructions, ctx, r.pump)
        is SolRoute.OnDex -> validateDexTx(instructions, ctx, r.venue)
    }

    fun validatePumpTx(instructions: List<Instruction>, ctx: SolValidationContext, route: PumpRoute): SolValidationResult {
        val actions = ArrayList<BlockedAction>()
        val wallet = ctx.wallet
        val pay = route.pay
        val solPay = pay.isSol
        val coin = Pump.routeCoin(route)
        val variant = Pump.routeVariant(route)
        val unit = ctx.payUnit ?: SOL_UNIT
        fun amount(raw: BigInteger) = units(raw, unit.decimals, unit.symbol)
        val budget = Budget()
        var wrapped = BigInteger.ZERO
        var trades = 0
        var spend = BigInteger.ZERO
        var amountIn = BigInteger.ZERO
        var minOut = BigInteger.ZERO

        if (coin.mint != ctx.mint || !Pump.routeOk(route)) actions += act("Route", "Does not lead to this token")

        checkSigners(instructions, wallet, actions)

        // The accounts a trade may open: the wallet's own in the coin and the pay currency, and a
        // listed buyback wallet's in the pay currency (the trade pays into it).
        val opens = listOf(
            Opening(wallet, coin.mint, coin.tokenProgram, "Open token account"),
            Opening(wallet, pay.mint, pay.program, if (solPay) "Open wrapped SOL account" else "Open ${unit.symbol} account"),
        ) + Pump.BUYBACK_FEE_RECIPIENTS.map { Opening(it, pay.mint, pay.program, "Open pump.fun's buyback ${unit.symbol} account") }

        for (ix in instructions) {
            val d = ix.data
            when (ix.programId) {
                COMPUTE_BUDGET_PROGRAM -> readBudget(ix, budget, actions)
                SYSTEM_PROGRAM -> {
                    val wrap = if (solPay && wrapped.signum() == 0) wrapAmount(ix, wallet) else null
                    if (wrap == null) {
                        actions += act("System program", "Only one transfer to your own wrapped SOL")
                    } else {
                        wrapped = wrap
                        actions += act("Wrap ${sol(wrapped)}")
                    }
                }
                ATA_PROGRAM -> {
                    val open = opening(ix, wallet, opens)
                    actions += if (open != null) act(open.label) else act("Token account", "Not an account this trade needs")
                }
                // Only around SOL trades: wrapping into, and unwrapping, the wallet's own WSOL.
                TOKEN_PROGRAM -> actions += wsolAction(if (solPay) wsolStep(ix, wallet) else null)
                Pump.PUMP_PROGRAM, Pump.PUMP_AMM_PROGRAM -> {
                    trades++
                    val label = if (variant == TradeVariant.MULTI_HOP) "a ${route.hops.size}-hop pump.fun route" else Pump.venueLabel(coin)
                    if (ix.programId != Pump.tradeProgram(variant)) {
                        actions += act("Trade on $label", "Not the trade this token takes")
                        continue
                    }
                    val rec = Pump.readRecipients(route, ix.keys)
                    val keysOk = rec != null &&
                        (rec.fee == null || rec.fee in Pump.feeRecipients(coin)) &&
                        ix.keys == Pump.tradeAccounts(route, ctx.side, wallet, rec)
                    if (!keysOk) {
                        actions += act("Trade on $label", "Accounts are not this token and wallet")
                        continue
                    }
                    val td = Pump.tradeData(variant, ctx.side)
                    val flag = td.flag
                    if (!startsWith(d, td.disc) || d.size != (if (flag == null) 24 else 25)) {
                        actions += act("Trade on $label", "Unknown instruction")
                        continue
                    }
                    amountIn = le(d, 8, 8)
                    minOut = le(d, 16, 8)
                    if (ctx.side == Side.SELL) {
                        actions += act("Sell on $label", if (minOut.signum() == 0) "No minimum received" else null)
                        continue
                    }
                    spend = amountIn
                    val reason = when {
                        flag != null && (d[24].toInt() and 0xff) != flag -> if (flag == 0) "Partial fills are not allowed" else "Volume tracking must stay on"
                        minOut.signum() == 0 -> "No minimum received"
                        spend > ctx.maxSpend -> "Above your per-transaction limit"
                        else -> null
                    }
                    actions += act("Buy on $label for ${amount(spend)}", reason)
                }
                else -> actions += act("Program ${shortAddress(ix.programId)}", "Program is not on the allowlist")
            }
        }

        if (trades != 1) actions += act("Trade", "Exactly one trade per transaction")
        // SOL into a pool is wrapped first, exactly what the buy spends; nothing else wraps.
        val wraps = solPay && ctx.side == Side.BUY && route.hops.firstOrNull() is PoolVenue
        if (wrapped != (if (wraps) spend else BigInteger.ZERO)) actions += act("Wrap ${sol(wrapped)}", "Does not wrap what the trade spends")
        checkPriority(budget, ctx.maxPriorityLamports, actions)
        return SolValidationResult(actions.all { it.ok }, actions, spend, amountIn, minOut)
    }

    /**
     * Runs before every signature of a trade on a pool of another program. The swap must be
     * exactly the call Nesty builds for this pool and wallet from the amounts it names; around it,
     * only the wallet's own accounts in the coin and the pool's quote may be opened, and SOL
     * wrapped (exactly what a buy spends) and unwrapped.
     */
    fun validateDexTx(instructions: List<Instruction>, ctx: SolValidationContext, v: DexVenue): SolValidationResult {
        val actions = ArrayList<BlockedAction>()
        val wallet = ctx.wallet
        val m = Dex.module(v)
        val label = m.label(v)
        val solPay = v.quote.isSol
        val unit = ctx.payUnit ?: SOL_UNIT
        fun amount(raw: BigInteger) = units(raw, unit.decimals, unit.symbol)
        val budget = Budget()
        var wrapped = BigInteger.ZERO
        var swaps = 0
        var spend = BigInteger.ZERO
        var amountIn = BigInteger.ZERO
        var minOut = BigInteger.ZERO

        if (v.mint != ctx.mint) actions += act("Pool", "Does not trade this token")
        checkSigners(instructions, wallet, actions)

        val opens = listOf(
            Opening(wallet, v.mint, v.tokenProgram, "Open token account"),
            Opening(wallet, v.quote.mint, v.quote.program, if (solPay) "Open wrapped SOL account" else "Open ${unit.symbol} account"),
        )

        for (ix in instructions) {
            when (ix.programId) {
                COMPUTE_BUDGET_PROGRAM -> readBudget(ix, budget, actions)
                SYSTEM_PROGRAM -> {
                    val wrap = if (solPay && wrapped.signum() == 0) wrapAmount(ix, wallet) else null
                    if (wrap == null) {
                        actions += act("System program", "Only one transfer to your own wrapped SOL")
                    } else {
                        wrapped = wrap
                        actions += act("Wrap ${sol(wrapped)}")
                    }
                }
                ATA_PROGRAM -> {
                    val open = opening(ix, wallet, opens)
                    actions += if (open != null) act(open.label) else act("Token account", "Not an account this trade needs")
                }
                TOKEN_PROGRAM -> actions += wsolAction(if (solPay) wsolStep(ix, wallet) else null)
                m.program -> {
                    swaps++
                    val a = m.amounts(ix.data)
                    if (a == null) {
                        actions += act("Trade on $label", "Unknown instruction")
                        continue
                    }
                    // Rebuilt from the pool and the wallet: any other account or byte is refused.
                    val want = m.swap(v, ctx.side, wallet, a.amountIn, a.minOut)
                    if (ix.keys != want.keys || !ix.data.contentEquals(want.data)) {
                        actions += act("Trade on $label", "Accounts are not this pool and wallet")
                        continue
                    }
                    amountIn = a.amountIn
                    minOut = a.minOut
                    if (ctx.side == Side.SELL) {
                        actions += act("Sell on $label", if (a.minOut.signum() == 0) "No minimum received" else null)
                        continue
                    }
                    spend = a.amountIn
                    val reason = when {
                        a.minOut.signum() == 0 -> "No minimum received"
                        spend > ctx.maxSpend -> "Above your per-transaction limit"
                        else -> null
                    }
                    actions += act("Buy on $label for ${amount(spend)}", reason)
                }
                else -> actions += act("Program ${shortAddress(ix.programId)}", "Program is not on the allowlist")
            }
        }

        if (swaps != 1) actions += act("Trade", "Exactly one trade per transaction")
        // Pools trade wrapped SOL: a buy wraps exactly what it spends; nothing else wraps.
        if (wrapped != (if (solPay && ctx.side == Side.BUY) spend else BigInteger.ZERO)) actions += act("Wrap ${sol(wrapped)}", "Does not wrap what the trade spends")
        checkPriority(budget, ctx.maxPriorityLamports, actions)
        return SolValidationResult(actions.all { it.ok }, actions, spend, amountIn, minOut)
    }
}
