package trade.nesty.app.sol

import trade.nesty.app.evm.BlockedAction
import trade.nesty.app.evm.Side
import trade.nesty.app.evm.act
import trade.nesty.app.evm.shortAddress
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.SolAddr.ATA_PROGRAM
import trade.nesty.app.sol.SolAddr.COMPUTE_BUDGET_PROGRAM
import trade.nesty.app.sol.SolAddr.SYSTEM_PROGRAM
import trade.nesty.app.sol.SolAddr.TOKEN_PROGRAM
import trade.nesty.app.sol.dex.Dex
import trade.nesty.app.sol.dex.DexVenue
import trade.nesty.app.move.RelaySol
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
    /** The tip a private send may pay the block engine, in lamports: none when zero. */
    val maxTipLamports: BigInteger = BigInteger.ZERO,
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
            actions += act(tr("Compute budget"), tr("Unexpected accounts"))
        } else if (tag == Spl.CU_SET_LIMIT && d.size == 5 && b.limit == null) {
            val limit = le(d, 1, 4)
            b.limit = limit
            actions += act(tr("Compute limit {1}", limit), if (limit > BigInteger.valueOf(Spl.MAX_COMPUTE_UNITS.toLong())) tr("Above the maximum") else null)
        } else if (tag == Spl.CU_SET_PRICE && d.size == 9 && b.price == null) {
            b.price = le(d, 1, 8)
        } else {
            actions += act(tr("Compute budget"), tr("Unknown or repeated instruction"))
        }
    }

    private fun checkPriority(b: Budget, maxLamports: BigInteger, actions: MutableList<BlockedAction>) {
        val price = b.price ?: return
        val limit = b.limit ?: BigInteger.valueOf(200_000)
        val fee = (price * limit + BigInteger.valueOf(999_999)) / BigInteger.valueOf(1_000_000)
        actions += act(tr("Priority fee {1}", sol(fee)), if (fee > maxLamports) tr("Above your max network fee") else null)
    }

    private fun checkSigners(instructions: List<Instruction>, wallet: String, actions: MutableList<BlockedAction>) {
        for (k in instructions.flatMap { it.keys }) {
            if (k.isSigner && k.pubkey != wallet) actions += act("Signer ${shortAddress(k.pubkey)}", tr("Only your wallet may sign"))
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

    /** What a trade's system instructions did: the one wrap into its own WSOL, and the one tip. */
    private class SystemUse(var wrapped: BigInteger = BigInteger.ZERO, var tips: Int = 0)

    /**
     * A system instruction of a trade: a tip to the block engine for a private send (at most one,
     * at most the tip the user's speed sets), or the one transfer into the wallet's own WSOL.
     */
    private fun readSystem(ix: Instruction, ctx: SolValidationContext, solPay: Boolean, use: SystemUse, actions: MutableList<BlockedAction>) {
        val tip = Jito.tipOf(ix, ctx.wallet)
        if (tip != null) {
            use.tips++
            val reason = when {
                use.tips > 1 -> tr("Only one tip")
                tip > ctx.maxTipLamports -> tr("Above your MEV protection tip")
                tip < Jito.MIN_TIP -> tr("Below the block engine's minimum")
                else -> null
            }
            actions += act(tr("MEV protection tip {1}", sol(tip)), reason)
            return
        }
        val wrap = if (solPay && use.wrapped.signum() == 0) wrapAmount(ix, ctx.wallet) else null
        if (wrap == null) {
            actions += act(tr("System program"), tr("Only one transfer to your own wrapped SOL"))
        } else {
            use.wrapped = wrap
            actions += act("Wrap ${sol(wrap)}")
        }
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
        WsolStep.SYNC -> act(tr("Sync wrapped SOL"))
        WsolStep.CLOSE -> act(tr("Unwrap SOL"))
        null -> act(tr("Token program"), tr("Only your own wrapped SOL account"))
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
        val system = SystemUse()
        var trades = 0
        var spend = BigInteger.ZERO
        var amountIn = BigInteger.ZERO
        var minOut = BigInteger.ZERO

        if (coin.mint != ctx.mint || !Pump.routeOk(route)) actions += act("Route", tr("Does not lead to this token"))

        checkSigners(instructions, wallet, actions)

        // The accounts a trade may open: the wallet's own in the coin and the pay currency, and a
        // listed buyback wallet's in the pay currency (the trade pays into it).
        val opens = listOf(
            Opening(wallet, coin.mint, coin.tokenProgram, tr("Open token account")),
            Opening(wallet, pay.mint, pay.program, if (solPay) tr("Open wrapped SOL account") else tr("Open {1} account", unit.symbol)),
        ) + Pump.BUYBACK_FEE_RECIPIENTS.map { Opening(it, pay.mint, pay.program, tr("Open pump.fun's buyback {1} account", unit.symbol)) }

        for (ix in instructions) {
            val d = ix.data
            when (ix.programId) {
                COMPUTE_BUDGET_PROGRAM -> readBudget(ix, budget, actions)
                SYSTEM_PROGRAM -> readSystem(ix, ctx, solPay, system, actions)
                ATA_PROGRAM -> {
                    val open = opening(ix, wallet, opens)
                    actions += if (open != null) act(open.label) else act(tr("Token account"), tr("Not an account this trade needs"))
                }
                // Only around SOL trades: wrapping into, and unwrapping, the wallet's own WSOL.
                TOKEN_PROGRAM -> actions += wsolAction(if (solPay) wsolStep(ix, wallet) else null)
                Pump.PUMP_PROGRAM, Pump.PUMP_AMM_PROGRAM -> {
                    trades++
                    val label = if (variant == TradeVariant.MULTI_HOP) tr("a {1}-hop pump.fun route", route.hops.size) else Pump.venueLabel(coin)
                    if (ix.programId != Pump.tradeProgram(variant)) {
                        actions += act(tr("Trade on {1}", label), tr("Not the trade this token takes"))
                        continue
                    }
                    val rec = Pump.readRecipients(route, ix.keys)
                    val keysOk = rec != null &&
                        (rec.fee == null || rec.fee in Pump.feeRecipients(coin)) &&
                        ix.keys == Pump.tradeAccounts(route, ctx.side, wallet, rec)
                    if (!keysOk) {
                        actions += act(tr("Trade on {1}", label), tr("Accounts are not this token and wallet"))
                        continue
                    }
                    val td = Pump.tradeData(variant, ctx.side)
                    val flag = td.flag
                    if (!startsWith(d, td.disc) || d.size != (if (flag == null) 24 else 25)) {
                        actions += act(tr("Trade on {1}", label), tr("Unknown instruction"))
                        continue
                    }
                    amountIn = le(d, 8, 8)
                    minOut = le(d, 16, 8)
                    if (ctx.side == Side.SELL) {
                        actions += act(tr("Sell on {1}", label), if (minOut.signum() == 0) tr("No minimum received") else null)
                        continue
                    }
                    spend = amountIn
                    val reason = when {
                        flag != null && (d[24].toInt() and 0xff) != flag -> if (flag == 0) tr("Partial fills are not allowed") else tr("Volume tracking must stay on")
                        minOut.signum() == 0 -> tr("No minimum received")
                        spend > ctx.maxSpend -> tr("Above your per-transaction limit")
                        else -> null
                    }
                    actions += act(tr("Buy on {1} for {2}", label, amount(spend)), reason)
                }
                else -> actions += act("Program ${shortAddress(ix.programId)}", tr("Program is not on the allowlist"))
            }
        }

        if (trades != 1) actions += act("Trade", tr("Exactly one trade per transaction"))
        // SOL into a pool is wrapped first, exactly what the buy spends; nothing else wraps.
        val wraps = solPay && ctx.side == Side.BUY && route.hops.firstOrNull() is PoolVenue
        if (system.wrapped != (if (wraps) spend else BigInteger.ZERO)) actions += act("Wrap ${sol(system.wrapped)}", tr("Does not wrap what the trade spends"))
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
        val system = SystemUse()
        var swaps = 0
        var spend = BigInteger.ZERO
        var amountIn = BigInteger.ZERO
        var minOut = BigInteger.ZERO

        if (v.mint != ctx.mint) actions += act("Pool", tr("Does not trade this token"))
        checkSigners(instructions, wallet, actions)

        val opens = listOf(
            Opening(wallet, v.mint, v.tokenProgram, tr("Open token account")),
            Opening(wallet, v.quote.mint, v.quote.program, if (solPay) tr("Open wrapped SOL account") else tr("Open {1} account", unit.symbol)),
        )

        for (ix in instructions) {
            when (ix.programId) {
                COMPUTE_BUDGET_PROGRAM -> readBudget(ix, budget, actions)
                SYSTEM_PROGRAM -> readSystem(ix, ctx, solPay, system, actions)
                ATA_PROGRAM -> {
                    val open = opening(ix, wallet, opens)
                    actions += if (open != null) act(open.label) else act(tr("Token account"), tr("Not an account this trade needs"))
                }
                TOKEN_PROGRAM -> actions += wsolAction(if (solPay) wsolStep(ix, wallet) else null)
                m.program -> {
                    swaps++
                    val a = m.amounts(ix.data)
                    if (a == null) {
                        actions += act(tr("Trade on {1}", label), tr("Unknown instruction"))
                        continue
                    }
                    // Rebuilt from the pool and the wallet: any other account or byte is refused.
                    val want = m.swap(v, ctx.side, wallet, a.amountIn, a.minOut)
                    if (ix.keys != want.keys || !ix.data.contentEquals(want.data)) {
                        actions += act(tr("Trade on {1}", label), tr("Accounts are not this pool and wallet"))
                        continue
                    }
                    amountIn = a.amountIn
                    minOut = a.minOut
                    if (ctx.side == Side.SELL) {
                        actions += act(tr("Sell on {1}", label), if (a.minOut.signum() == 0) tr("No minimum received") else null)
                        continue
                    }
                    spend = a.amountIn
                    val reason = when {
                        a.minOut.signum() == 0 -> tr("No minimum received")
                        spend > ctx.maxSpend -> tr("Above your per-transaction limit")
                        else -> null
                    }
                    actions += act(tr("Buy on {1} for {2}", label, amount(spend)), reason)
                }
                else -> actions += act("Program ${shortAddress(ix.programId)}", tr("Program is not on the allowlist"))
            }
        }

        if (swaps != 1) actions += act("Trade", tr("Exactly one trade per transaction"))
        // Pools trade wrapped SOL: a buy wraps exactly what it spends; nothing else wraps.
        if (system.wrapped != (if (solPay && ctx.side == Side.BUY) spend else BigInteger.ZERO)) actions += act("Wrap ${sol(system.wrapped)}", tr("Does not wrap what the trade spends"))
        checkPriority(budget, ctx.maxPriorityLamports, actions)
        return SolValidationResult(actions.all { it.ok }, actions, spend, amountIn, minOut)
    }

    /**
     * A transfer of SOL or one token to one address (validator.ts validateSolanaTransfer). The
     * transaction may hold exactly that transfer, the opening of the recipient's token account, and
     * its compute budget. Nothing else.
     */
    fun validateTransfer(instructions: List<Instruction>, ctx: SolTransferContext): SolValidationResult {
        val actions = mutableListOf<BlockedAction>()
        val budget = Budget()
        var moves = 0
        val asset = ctx.asset
        checkSigners(instructions, ctx.wallet, actions)
        if (ctx.to == ctx.wallet) actions += act("Recipient", tr("This is the wallet you are sending from"))
        val sent = if (asset != null) units(ctx.amount, asset.decimals, asset.symbol) else sol(ctx.amount)
        for (ix in instructions) {
            val d = ix.data
            when (ix.programId) {
                SolAddr.COMPUTE_BUDGET_PROGRAM -> readBudget(ix, budget, actions)
                SYSTEM_PROGRAM -> {
                    moves++
                    val ok = asset == null && d.size == 12 && le(d, 0, 4) == BigInteger.valueOf(Spl.SYSTEM_TRANSFER.toLong()) &&
                        le(d, 4, 8) == ctx.amount &&
                        ix.keys == listOf(AccountMeta(ctx.wallet, isSigner = true, isWritable = true), AccountMeta(ctx.to, isSigner = false, isWritable = true))
                    actions += if (ok) act(tr("Send {1} to {2}", sent, shortAddress(ctx.to))) else act(tr("System program"), tr("Not the transfer you asked for"))
                }
                ATA_PROGRAM -> {
                    val ok = asset != null && d.size == 1 && d[0].toInt() == Spl.ATA_CREATE_IDEMPOTENT &&
                        ix.keys == ataCreateKeys(ctx.wallet, ctx.to, asset.mint, asset.program)
                    actions += if (ok) act(tr("Open the recipient's {1} account", asset!!.symbol)) else act(tr("Token account"), tr("Not the recipient's account for this token"))
                }
                TOKEN_PROGRAM, SolAddr.TOKEN_2022_PROGRAM -> {
                    moves++
                    val ok = asset != null && ix.programId == asset.program && d.size == 10 && d[0].toInt() == Spl.TOKEN_TRANSFER_CHECKED &&
                        le(d, 1, 8) == ctx.amount && (d[9].toInt() and 0xff) == asset.decimals &&
                        ix.keys == listOf(
                            AccountMeta(SolAddr.associatedTokenAddress(ctx.wallet, asset.mint, asset.program), isSigner = false, isWritable = true),
                            AccountMeta(asset.mint, isSigner = false, isWritable = false),
                            AccountMeta(SolAddr.associatedTokenAddress(ctx.to, asset.mint, asset.program), isSigner = false, isWritable = true),
                            AccountMeta(ctx.wallet, isSigner = true, isWritable = false),
                        )
                    actions += if (ok) act(tr("Send {1} to {2}", sent, shortAddress(ctx.to))) else act(tr("Token program"), tr("Not the transfer you asked for"))
                }
                else -> actions += act("Program ${shortAddress(ix.programId)}", tr("Program is not on the allowlist"))
            }
        }
        if (moves != 1) actions += act("Transfer", tr("Exactly one transfer per transaction"))
        checkPriority(budget, ctx.maxPriorityLamports, actions)
        return SolValidationResult(actions.all { it.ok }, actions, ctx.amount, ctx.amount, BigInteger.ZERO)
    }

    /**
     * A bridge deposit into Relay's vault (validator.ts validateRelayDeposit): exactly the deposit
     * the app builds from the wallet, the amount and the id Relay quoted, and its compute budget.
     */
    fun validateRelayDeposit(instructions: List<Instruction>, ctx: RelayDepositContext): SolValidationResult {
        val actions = mutableListOf<BlockedAction>()
        val budget = Budget()
        val want = RelaySol.depositNative(ctx.wallet, ctx.amount, ctx.id)
        var deposits = 0
        checkSigners(instructions, ctx.wallet, actions)
        for (ix in instructions) {
            when (ix.programId) {
                SolAddr.COMPUTE_BUDGET_PROGRAM -> readBudget(ix, budget, actions)
                RelaySol.PROGRAM -> {
                    deposits++
                    val label = tr("Bridge deposit of {1}", sol(ctx.amount))
                    actions += when {
                        ix.keys.getOrNull(2)?.pubkey != ctx.wallet -> act(label, tr("Deposit is not credited to the sending wallet"))
                        ix.keys != want.keys || !ix.data.contentEquals(want.data) -> act(label, tr("Not the deposit you asked for"))
                        ctx.amount.signum() == 0 -> act(label, tr("Empty deposit"))
                        else -> act(label, if (ctx.amount > ctx.maxSpend) tr("Above your per-transaction limit") else null)
                    }
                }
                else -> actions += act("Program ${shortAddress(ix.programId)}", tr("Program is not on the allowlist"))
            }
        }
        if (deposits != 1) actions += act(tr("Bridge deposit"), tr("Exactly one deposit per transaction"))
        checkPriority(budget, ctx.maxPriorityLamports, actions)
        return SolValidationResult(actions.all { it.ok }, actions, ctx.amount, ctx.amount, BigInteger.ZERO)
    }
}

/** A token a transfer sends; null in the context for SOL. */
data class SolAsset(val mint: String, val program: String, val decimals: Int, val symbol: String)

/** What one transfer may do: send exactly `amount` from `wallet` to `to`. */
data class SolTransferContext(
    val wallet: String,
    val to: String,
    val asset: SolAsset?,
    val amount: BigInteger,
    /** Most lamports the priority fee may cost (from the max network fee). */
    val maxPriorityLamports: BigInteger,
)

/** What one bridge deposit may do: put exactly `amount` lamports into Relay's vault under `id`. */
class RelayDepositContext(
    val wallet: String,
    val amount: BigInteger,
    /** The deposit id from Relay's quote: it ties the deposit to the request. */
    val id: ByteArray,
    /** Most the deposit may be (from the per-transaction limit). */
    val maxSpend: BigInteger,
    val maxPriorityLamports: BigInteger,
)
