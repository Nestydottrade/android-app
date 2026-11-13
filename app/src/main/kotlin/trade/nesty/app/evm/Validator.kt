package trade.nesty.app.evm

import java.math.BigInteger

/** A transaction as a venue builds it, before gas and nonce. */
data class TxDraft(val to: String, val data: ByteArray, val value: BigInteger) {
    override fun equals(other: Any?) = other is TxDraft && to.equals(other.to, true) && data.contentEquals(other.data) && value == other.value
    override fun hashCode() = to.lowercase().hashCode() * 31 + data.contentHashCode()
}

/** One decoded action in the validator's report. A reason marks the one that blocks. */
data class BlockedAction(val label: String, val ok: Boolean, val reason: String?)

fun act(label: String, reason: String? = null) = BlockedAction(label, reason == null, reason)

data class ValidationResult(val ok: Boolean, val actions: List<BlockedAction>) {
    /** The first reason it was refused, for a message. */
    val reason: String? get() = actions.firstOrNull { !it.ok }?.reason
}

/** What a contract the engine calls (a launchpad, a router) may be asked to do. */
class ContractRule(
    val label: String,
    /** It takes ERC-20 approvals (a launchpad that pulls the tokens it sells). */
    val approvalSpender: Boolean = false,
    val validate: (TxDraft, ValidationContext) -> List<BlockedAction>,
)

data class ValidationContext(
    val chain: EvmChain,
    val from: String,
    val ownWallets: List<String>,
    /** The token being traded, if the action involves one. */
    val token: String? = null,
    /** For a curve quoted in an ERC-20 (a funded route): that asset. */
    val fundingAsset: String? = null,
    /** The chain's Universal Router. */
    val router: String? = null,
    /** Launchpad contracts and the like, by lowercase address. */
    val extraContracts: Map<String, ContractRule> = emptyMap(),
    /** Upper bound for ERC-20 approvals; required for one to pass. */
    val maxApproval: BigInteger? = null,
    /** Upper bound for Permit2 permits; required for one to pass. */
    val maxPermit: BigInteger? = null,
    /** Most native value one transaction may carry (from the dollar limit). */
    val maxValue: BigInteger,
    /** An outside address the user typed and confirmed for a send. Never set for trades. */
    val payee: String? = null,
    val nowS: Long,
)

/** `0x1234…abcd`, as the extension's shortAddress writes it. */
fun shortAddress(a: String): String = if (a.length <= 10) a else "${a.take(6)}…${a.takeLast(4)}"

/** Smallest units as viem's formatUnits writes them: no trailing zeros, no dot for a whole. */
fun formatUnits(v: BigInteger, decimals: Int): String {
    val neg = v.signum() < 0
    val s = v.abs().toString().padStart(decimals + 1, '0')
    val whole = s.substring(0, s.length - decimals)
    val frac = s.substring(s.length - decimals).trimEnd('0')
    return (if (neg) "-" else "") + whole + (if (frac.isEmpty()) "" else ".$frac")
}

fun formatEther(v: BigInteger) = formatUnits(v, 18)

/**
 * Runs before EVERY signature (transactions and Permit2 typed data), as validator.ts does.
 * No RPC. Anything not explicitly understood is refused.
 */
object Validator {
    private const val MAX_DEADLINE_S = 30 * 60L
    private const val MAX_PERMIT_TTL_S = 2 * 60 * 60L
    private val CONTRACT_BALANCE = UniversalRouter.CONTRACT_BALANCE
    private const val NATIVE = UniversalRouter.NATIVE
    private const val ADDRESS_THIS = UniversalRouter.ADDRESS_THIS
    private const val MSG_SENDER = UniversalRouter.MSG_SENDER

    val erc20Approve = AbiFunction("function approve(address spender, uint256 amount) returns (bool)")
    val erc20Transfer = AbiFunction("function transfer(address to, uint256 amount) returns (bool)")

    fun isOwn(ctx: ValidationContext, a: String) = ctx.ownWallets.any { sameAddress(it, a) }

    private fun isPayee(ctx: ValidationContext, a: String) = ctx.payee != null && sameAddress(ctx.payee, a)

    private fun result(actions: List<BlockedAction>) = ValidationResult(actions.isNotEmpty() && actions.all { it.ok }, actions)

    /** The chain's stablecoins and hop tokens: allowed only between native and the token. */
    fun isStable(chain: EvmChain, a: String) = chain.routeAssets.any { sameAddress(it.first, a) }

    private fun allowedAsset(ctx: ValidationContext, a: String) =
        sameAddress(a, NATIVE) || sameAddress(a, ctx.chain.wrappedNative) || isStable(ctx.chain, a) ||
            (ctx.token != null && sameAddress(a, ctx.token))

    private fun recipientOk(ctx: ValidationContext, r: String) =
        sameAddress(r, ADDRESS_THIS) || sameAddress(r, MSG_SENDER) || isOwn(ctx, r)

    private fun paysUser(ctx: ValidationContext, r: String) = sameAddress(r, MSG_SENDER) || isOwn(ctx, r)

    private fun checkPermit(permit: PermitSingle, ctx: ValidationContext): BlockedAction {
        val label = "Permit2: allow router to pull ${permit.amount} of ${shortAddress(permit.token)}"
        if (ctx.token == null || !sameAddress(permit.token, ctx.token)) return act(label, "Permit is for a different token")
        if (ctx.router == null || !sameAddress(permit.spender, ctx.router)) return act(label, "Permit spender is not the router")
        if (ctx.maxPermit == null || permit.amount > ctx.maxPermit) return act(label, "Permit amount is above the amount being sold")
        if (permit.expiration > ctx.nowS + MAX_PERMIT_TTL_S) return act(label, "Permit expiry too long")
        if (permit.sigDeadline > big(ctx.nowS + MAX_PERMIT_TTL_S)) return act(label, "Permit signature deadline too long")
        return act(label)
    }

    private fun checkV4(a: V4Action, ctx: ValidationContext, out: MutableList<BlockedAction>): Boolean = when (a) {
        is V4Action.SwapExactInSingle -> {
            val c0 = a.poolKey.currency0
            val c1 = a.poolKey.currency1
            val label = "v4 swap ${shortAddress(c0)} / ${shortAddress(c1)}"
            fun nativeSide(c: String) = sameAddress(c, NATIVE) || sameAddress(c, ctx.chain.wrappedNative)
            // Native ↔ hop asset (ETH → flETH): its output stays in the unlock as credit.
            val bridgeLeg = (nativeSide(c0) && isStable(ctx.chain, c1)) || (nativeSide(c1) && isStable(ctx.chain, c0))
            if (!allowedAsset(ctx, c0) || !allowedAsset(ctx, c1)) out += act(label, "Pool contains an unexpected asset")
            else if (!bridgeLeg && (ctx.token == null || (!sameAddress(c0, ctx.token) && !sameAddress(c1, ctx.token)))) {
                out += act(label, "Pool does not contain the traded token")
            } else out += act(label)
            false
        }
        is V4Action.Settle -> {
            val label = "v4 settle ${shortAddress(a.currency)}${if (a.payerIsUser) " from wallet" else " from router"}"
            out += if (allowedAsset(ctx, a.currency)) act(label) else act(label, "Unexpected asset")
            false
        }
        is V4Action.SettleAll -> {
            val label = "v4 settle all ${shortAddress(a.currency)}"
            out += if (allowedAsset(ctx, a.currency)) act(label) else act(label, "Unexpected asset")
            false
        }
        is V4Action.Take -> {
            val label = "v4 take ${shortAddress(a.currency)} → ${shortAddress(a.recipient)}"
            out += when {
                !allowedAsset(ctx, a.currency) -> act(label, "Unexpected asset")
                !recipientOk(ctx, a.recipient) -> act(label, "Recipient is not one of your wallets")
                else -> act(label)
            }
            paysUser(ctx, a.recipient)
        }
        is V4Action.TakeAll -> {
            val label = "v4 take all ${shortAddress(a.currency)} → sender"
            out += if (allowedAsset(ctx, a.currency)) act(label) else act(label, "Unexpected asset")
            true
        }
    }

    /** A swap's tokens in order; a v3 path with no hops reads as one unknown token. */
    private fun pathOf(c: Command): List<String?> = when (c) {
        is Command.V2SwapExactIn -> c.path
        is Command.V3SwapExactIn -> listOf(c.path.firstOrNull()?.tokenIn) + c.path.map { it.tokenOut }
        else -> emptyList()
    }

    private fun checkCommand(c: Command, ctx: ValidationContext, value: BigInteger, out: MutableList<BlockedAction>): Boolean = when (c) {
        is Command.WrapEth -> {
            val label = "Wrap ${if (c.amount == CONTRACT_BALANCE) "all" else formatEther(c.amount)} ${ctx.chain.nativeSymbol}"
            out += when {
                !sameAddress(c.recipient, ADDRESS_THIS) && !isOwn(ctx, c.recipient) -> act(label, "Wrapped funds go to an unknown address")
                c.amount != CONTRACT_BALANCE && c.amount > value -> act(label, "Wraps more than the transaction value")
                else -> act(label)
            }
            isOwn(ctx, c.recipient)
        }
        is Command.UnwrapWeth -> {
            val label = "Unwrap to ${shortAddress(c.recipient)}"
            out += if (paysUser(ctx, c.recipient)) act(label) else act(label, "Recipient is not one of your wallets")
            paysUser(ctx, c.recipient)
        }
        is Command.Sweep -> {
            val label = "Sweep ${shortAddress(c.token)} → ${shortAddress(c.recipient)}"
            out += when {
                !allowedAsset(ctx, c.token) -> act(label, "Unexpected asset")
                !paysUser(ctx, c.recipient) -> act(label, "Recipient is not one of your wallets")
                else -> act(label)
            }
            paysUser(ctx, c.recipient)
        }
        is Command.V2SwapExactIn, is Command.V3SwapExactIn -> {
            val v2 = c is Command.V2SwapExactIn
            val tokens = pathOf(c)
            val recipient = if (c is Command.V2SwapExactIn) c.recipient else (c as Command.V3SwapExactIn).recipient
            val amountOutMin = if (c is Command.V2SwapExactIn) c.amountOutMin else (c as Command.V3SwapExactIn).amountOutMin
            val label = "${if (v2) "v2" else "v3"} swap ${tokens.joinToString(" → ") { t -> t?.let { shortAddress(it) } ?: "?" }}"
            val first = tokens.firstOrNull()
            val last = tokens.lastOrNull()
            val tradesToken = ctx.token != null && first != null && last != null &&
                (sameAddress(first, ctx.token) || sameAddress(last, ctx.token))
            // Native ↔ stable leg of a stable-quoted route: its output stays in the router.
            val bridgeLeg = !tradesToken && sameAddress(recipient, ADDRESS_THIS) && tokens.any { it != null && isStable(ctx.chain, it) }
            out += when {
                tokens.any { it == null || !allowedAsset(ctx, it) } -> act(label, "Path contains an unexpected asset")
                !tradesToken && !bridgeLeg -> act(label, "Path does not trade the selected token")
                tokens.drop(1).dropLast(1).any { it != null && !isStable(ctx.chain, it) } ->
                    act(label, "Only a stablecoin may sit between native and the token")
                !recipientOk(ctx, recipient) -> act(label, "Recipient is not one of your wallets")
                amountOutMin.signum() == 0 -> act(label, "No minimum output (no slippage protection)")
                else -> act(label)
            }
            paysUser(ctx, recipient)
        }
        is Command.Permit2Permit -> {
            out += checkPermit(c.permit, ctx)
            false
        }
        is Command.V4Swap -> {
            var pays = false
            for (a in c.actions) pays = checkV4(a, ctx, out) || pays
            if (c.actions.any { it is V4Action.SwapExactInSingle && it.amountOutMinimum.signum() == 0 }) {
                out += act("v4 swap minimum output", "No minimum output (no slippage protection)")
            }
            pays
        }
    }

    /** A stablecoin a step leaves in the router must be used up by a later step. */
    private fun strandedStables(commands: List<Command>, ctx: ValidationContext): List<String> {
        val held = LinkedHashMap<String, String>()
        fun put(a: String) {
            if (isStable(ctx.chain, a)) held[a.lowercase()] = a
        }
        fun use(a: String) {
            held.remove(a.lowercase())
        }
        for (c in commands) {
            when (c) {
                is Command.V2SwapExactIn, is Command.V3SwapExactIn -> {
                    val path = pathOf(c)
                    val payerIsUser = if (c is Command.V2SwapExactIn) c.payerIsUser else (c as Command.V3SwapExactIn).payerIsUser
                    val amountIn = if (c is Command.V2SwapExactIn) c.amountIn else (c as Command.V3SwapExactIn).amountIn
                    val recipient = if (c is Command.V2SwapExactIn) c.recipient else (c as Command.V3SwapExactIn).recipient
                    val first = path.firstOrNull()
                    val last = path.lastOrNull()
                    if (first != null && !payerIsUser && amountIn == CONTRACT_BALANCE) use(first)
                    if (last != null && sameAddress(recipient, ADDRESS_THIS)) put(last)
                }
                is Command.Sweep -> if (paysUser(ctx, c.recipient)) use(c.token)
                is Command.V4Swap -> for (a in c.actions) {
                    if (a is V4Action.Settle && !a.payerIsUser && a.amount == CONTRACT_BALANCE) use(a.currency)
                    if (a is V4Action.Take && sameAddress(a.recipient, ADDRESS_THIS)) put(a.currency)
                }
                else -> Unit
            }
        }
        return held.values.toList()
    }

    /** Universal Router `execute`. Also used for router steps inside a Nesty Router call. */
    fun validateUniversalRouter(tx: TxDraft, ctx: ValidationContext): List<BlockedAction> {
        val out = mutableListOf<BlockedAction>()
        val decoded = try {
            UniversalRouter.decodeExecute(tx.data)
        } catch (e: DecodeError) {
            return listOf(act("Universal Router call", e.message))
        } catch (e: Exception) {
            return listOf(act("Universal Router call", "Could not decode"))
        }
        val (commands, deadline) = decoded
        if (deadline < big(ctx.nowS) || deadline > big(ctx.nowS + MAX_DEADLINE_S)) {
            out += act("Deadline", "Deadline outside the allowed window")
        }
        var paysOut = false
        for (c in commands) paysOut = checkCommand(c, ctx, tx.value, out) || paysOut
        if (!paysOut) out += act("Output", "No step sends the output to your wallet")
        for (s in strandedStables(commands, ctx)) {
            out += act("Stablecoin ${shortAddress(s)}", "Left in the router instead of being swapped on")
        }
        if (tx.value.signum() > 0 && commands.none { it is Command.WrapEth || it is Command.V4Swap }) {
            out += act("Value", "Sends native funds without using them")
        }
        return out
    }

    private fun validateToken(tx: TxDraft, ctx: ValidationContext): List<BlockedAction> {
        if (tx.value.signum() != 0) return listOf(act("Token call", "Token calls must not carry native value"))
        val approve = runCatching { erc20Approve.decodeCall(tx.data) }.getOrNull()
        val transfer = if (approve == null) runCatching { erc20Transfer.decodeCall(tx.data) }.getOrNull() else null
        if (approve != null) {
            val spender = approve[0] as String
            val amount = approve[1] as BigInteger
            val label = "Approve ${shortAddress(spender)} for $amount"
            val spenderOk = sameAddress(spender, Permit2.ADDRESS) || ctx.extraContracts[spender.lowercase()]?.approvalSpender == true
            if (!spenderOk) return listOf(act(label, "Spender is not Permit2 or an allowlisted launchpad"))
            if (ctx.maxApproval == null || amount > ctx.maxApproval) return listOf(act(label, "Approval above the bounded amount"))
            return listOf(act(label))
        }
        if (transfer == null) return listOf(act("Call to token ${shortAddress(tx.to)}", "Unknown function"))
        val to = transfer[0] as String
        val amount = transfer[1] as BigInteger
        val label = "Transfer $amount to ${shortAddress(to)}"
        return listOf(if (isOwn(ctx, to) || isPayee(ctx, to)) act(label) else act(label, "Recipient is not one of your wallets"))
    }

    fun validateTx(tx: TxDraft, ctx: ValidationContext): ValidationResult {
        val pre = mutableListOf<BlockedAction>()
        if (!isOwn(ctx, ctx.from)) pre += act("Sender", "Sender is not one of your wallets")
        if (tx.value > ctx.maxValue) pre += act("Value ${formatEther(tx.value)} ${ctx.chain.nativeSymbol}", "Above your per-transaction limit")
        val extra = ctx.extraContracts[tx.to.lowercase()]
        val actions = when {
            ctx.router != null && sameAddress(tx.to, ctx.router) -> validateUniversalRouter(tx, ctx)
            ctx.token != null && sameAddress(tx.to, ctx.token) -> validateToken(tx, ctx)
            extra != null -> extra.validate(tx, ctx)
            isOwn(ctx, tx.to) || isPayee(ctx, tx.to) ->
                if (tx.data.isEmpty()) listOf(act("Send ${formatEther(tx.value)} ${ctx.chain.nativeSymbol} to ${shortAddress(tx.to)}"))
                else listOf(act("Call to ${shortAddress(tx.to)}", "Calls with data to a wallet are not allowed"))
            else -> listOf(act("Call to ${shortAddress(tx.to)}", "Contract is not on the allowlist"))
        }
        return result(pre + actions)
    }

    /** Validates a Permit2 PermitSingle before it is signed. */
    fun validatePermit(permit: PermitSingle, ctx: ValidationContext): ValidationResult = result(listOf(checkPermit(permit, ctx)))
}
