package trade.nesty.app.evm

import java.math.BigInteger
import trade.nesty.app.i18n.tr

/** One step of a Nesty Router call. */
data class RouterCall(
    val target: String,
    val value: BigInteger,
    val data: ByteArray,
    /** Zero address: no injection. */
    val injectToken: String = ZERO_ADDRESS,
    val injectOffset: BigInteger = BigInteger.ZERO,
    /** May pull the injected amount during this call only (zero address: nobody). */
    val spender: String = ZERO_ADDRESS,
) {
    fun abi(): List<Any?> = listOf(target, value, data, injectToken, injectOffset, spender)

    override fun equals(other: Any?) = other is RouterCall && sameAddress(target, other.target) && value == other.value &&
        data.contentEquals(other.data) && sameAddress(injectToken, other.injectToken) && injectOffset == other.injectOffset &&
        sameAddress(spender, other.spender)

    override fun hashCode() = data.contentHashCode()
}

/** A funded curve trade: the curve's pool and the AMM leg that turns native into its asset. */
class FundedRoute(
    val ctx: VenueCtx,
    val router: String,
    val wallet: String,
    val venue: Venue,
    val pool: PoolRef,
    val legVenue: Venue,
    val leg: PoolRef,
    val asset: String,
    val deadline: BigInteger,
)

/**
 * The Nesty Router (contracts/src/NestyRouter.sol), ported from nesty-router.ts: a funded
 * curve trade (native ↔ quote asset on an AMM, plus the curve trade) as ONE transaction. Its
 * steps are plain venue calls built with the venues' own builders. Where a step spends what
 * an earlier one produced, the router writes its balance of that asset into the step's
 * calldata just before the call (balance injection). Users never approve the router: a sell
 * hands it exactly the amount sold through a one-time Permit2 transfer.
 */
object NestyRouter {
    /** The router's name for native ether in `tokenOut`, `sweep` and injections. */
    const val NATIVE = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

    /** A step `value` meaning "all the ether the router holds". */
    val ALL_VALUE: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE

    private const val MAX_WINDOW_S = 30 * 60L

    val execute = AbiFunction(
        "function execute((address target, uint256 value, bytes data, address injectToken, uint256 injectOffset, address spender)[] calls, (((address token, uint256 amount) permitted, uint256 nonce, uint256 deadline) permit, bytes signature) input, address tokenOut, uint256 minOut, address[] sweep, uint256 deadline) payable returns (uint256 out)",
    )

    /** Amount the builders are called with; where it lands in the calldata is the injection offset. */
    private val SENTINEL = BigInteger("ce1e7ace1e7ace1e7ace1e7ace1e7ace1e7ace1e7ace1e7ace1e7ace1e7ace1e", 16)
    private val SENTINEL_WORD = Abi.word(SENTINEL)

    fun plain(tx: TxDraft) = RouterCall(tx.to, tx.value, tx.data)

    /**
     * A call that spends the router's whole balance of `token`, built by `build(amount)`.
     * `spender`, when set, may pull that amount during the call (the router approves it).
     */
    fun injected(token: String, build: (BigInteger) -> TxDraft, spender: String = ZERO_ADDRESS): RouterCall {
        val tx = build(SENTINEL)
        val data = tx.data
        val at = indexOf(data, SENTINEL_WORD, 0)
        if (at < 0 || indexOf(data, SENTINEL_WORD, at + 1) >= 0 || tx.value == SENTINEL) {
            throw IllegalArgumentException(tr("Cannot place the amount in this call"))
        }
        val zeroed = data.copyOf()
        for (i in at until at + 32) zeroed[i] = 0
        return RouterCall(tx.to, tx.value, zeroed, token, big(at), spender)
    }

    private fun indexOf(data: ByteArray, word: ByteArray, from: Int): Int {
        // Hex offsets in the TypeScript are counted in characters and must be even: whole bytes.
        outer@ for (i in from..data.size - word.size) {
            for (j in word.indices) if (data[i + j] != word[j]) continue@outer
            return i
        }
        return -1
    }

    /** Sends the router's whole balance of `token` to `to` (the contract the next step calls). */
    private fun handOver(token: String, to: String) =
        injected(token, { amount -> TxDraft(token, Erc20.transfer.encode(to, amount), BigInteger.ZERO) })

    /** Whether the router can sell into this leg (allowance-based, or swaps its own balance). */
    fun legSupported(ctx: VenueCtx, legVenue: Venue, leg: PoolRef): Boolean {
        if (!legVenue.sellApproval(ctx, leg).viaPermit2) return true
        return legVenue.buildSellPrefunded(ctx, leg, PrefundedParams(ZERO_ADDRESS, BigInteger.ONE, BigInteger.ZERO)) != null
    }

    /** native → asset (into the router) → curve buy with all of it (the curve may pull it). */
    fun buyCalls(r: FundedRoute, amountIn: BigInteger, assetMin: BigInteger, minOut: BigInteger): List<RouterCall> {
        val fund = r.legVenue.buildBuy(r.ctx, r.leg, BuyParams(r.router, amountIn, assetMin, r.deadline))
        return listOf(
            plain(fund),
            injected(
                r.asset,
                { a -> r.venue.buildBuy(r.ctx, r.pool, BuyParams(r.wallet, a, minOut, r.deadline)) },
                r.venue.sellApproval(r.ctx, r.pool).spender,
            ),
        )
    }

    /**
     * (tokens pulled by Permit2) → curve sell into the router → asset → native to the wallet.
     * Null when the leg cannot be sold into from the router.
     */
    fun sellCalls(r: FundedRoute, assetMin: BigInteger, minOut: BigInteger): List<RouterCall>? {
        val calls = mutableListOf(
            injected(
                r.pool.token,
                { a -> r.venue.buildSell(r.ctx, r.pool, SellParams(r.router, a, assetMin, r.deadline)) },
                r.venue.sellApproval(r.ctx, r.pool).spender,
            ),
        )
        val legApproval = r.legVenue.sellApproval(r.ctx, r.leg)
        if (!legApproval.viaPermit2) {
            calls += injected(
                r.asset,
                { a -> r.legVenue.buildSell(r.ctx, r.leg, SellParams(r.wallet, a, minOut, r.deadline)) },
                legApproval.spender,
            )
            return calls
        }
        val swap = r.legVenue.buildSellPrefunded(r.ctx, r.leg, PrefundedParams(r.wallet, minOut, r.deadline)) ?: return null
        calls += handOver(r.asset, swap.to)
        calls += plain(swap)
        return calls
    }

    class SignedTransfer(val permit: TransferPermit, val signature: ByteArray)

    fun encodeExecute(
        calls: List<RouterCall>,
        permit: SignedTransfer?,
        tokenOut: String,
        minOut: BigInteger,
        sweep: List<String>,
        deadline: BigInteger,
    ): ByteArray {
        val input = if (permit != null) {
            listOf(
                listOf(listOf(permit.permit.token, permit.permit.amount), permit.permit.nonce, permit.permit.deadline),
                permit.signature,
            )
        } else {
            listOf(listOf(listOf(ZERO_ADDRESS, BigInteger.ZERO), BigInteger.ZERO, BigInteger.ZERO), ByteArray(0))
        }
        return execute.encode(calls.map { it.abi() }, input, tokenOut, minOut, sweep, deadline)
    }

    /** Checks a SignatureTransfer before it is signed: this token, at most this amount, this router. */
    fun checkTransferPermit(permit: TransferPermit, spender: String, ctx: ValidationContext): BlockedAction {
        val label = tr("Permit2: Nesty Router pulls {1} of {2} once", permit.amount, shortAddress(permit.token))
        if (ctx.token == null || !sameAddress(permit.token, ctx.token)) return act(label, tr("Permit is for a different token"))
        if (ctx.chain.nestyRouter == null || !sameAddress(spender, ctx.chain.nestyRouter)) return act(label, tr("Spender is not the Nesty Router"))
        if (ctx.maxPermit == null || permit.amount > ctx.maxPermit) return act(label, tr("Permit amount is above the amount being sold"))
        if (permit.deadline < big(ctx.nowS) || permit.deadline > big(ctx.nowS + MAX_WINDOW_S)) {
            return act(label, tr("Permit deadline outside the allowed window"))
        }
        return act(label)
    }

    private fun allOk(xs: List<BlockedAction>) = xs.isNotEmpty() && xs.all { it.ok }

    /** The call with a non-zero amount in its injection slot, so venue rules see a real trade. */
    private fun withPlaceholder(data: ByteArray, offset: Int): ByteArray {
        val out = data.copyOf()
        val one = Abi.word(BigInteger.ONE)
        for (i in 0 until 32) out[offset + i] = one[i]
        return out
    }

    private fun checkStep(c: RouterCall, tx: TxDraft, next: RouterCall?, ctx: ValidationContext, isAsset: (String) -> Boolean): List<BlockedAction> {
        val token = ctx.token!!
        val asset = ctx.fundingAsset!!
        if (isAsset(c.target)) {
            // An asset the route holds: only handed, all of it, to the contract the next step
            // calls. Approvals are the router's own (see `spender`); it refuses `approve` steps.
            if (tx.value.signum() != 0) return listOf(act(tr("Token call {1}", shortAddress(c.target)), tr("Must not carry native value")))
            val approve = runCatching { Erc20.approve.decodeCall(c.data) }.getOrNull()
            val transfer = if (approve == null) runCatching { Erc20.transfer.decodeCall(c.data) }.getOrNull() else null
            val args = approve ?: transfer ?: return listOf(act(tr("Token call {1}", shortAddress(c.target)), tr("Unknown function")))
            val to = args[0] as String
            if (approve != null) return listOf(act("Approve ${shortAddress(to)}", tr("Approvals are made by the router itself")))
            val label = tr("Hand over {1} → {2}", shortAddress(c.target), shortAddress(to))
            if (!sameAddress(c.injectToken, c.target) || c.injectOffset != big(36)) return listOf(act(label, tr("Amount is not the router's balance")))
            return if (ctx.router != null && sameAddress(to, ctx.router) && next != null && sameAddress(next.target, to)) {
                listOf(act(label))
            } else {
                listOf(act(label, tr("Recipient is not the next step")))
            }
        }
        if (ctx.router != null && sameAddress(c.target, ctx.router)) return Validator.validateUniversalRouter(tx, ctx.copy(token = asset))
        val rule = ctx.extraContracts[c.target.lowercase()] ?: return listOf(act(tr("Call to {1}", shortAddress(c.target)), tr("Contract is not on the allowlist")))
        // A step trades either the selected token (the curve) or the quote asset (the leg).
        val asToken = rule.validate(tx, ctx.copy(token = token))
        if (allOk(asToken)) return asToken
        val asAsset = rule.validate(tx, ctx.copy(token = asset))
        return if (allOk(asAsset)) asAsset else asToken
    }

    /**
     * Validates `execute` for the funded curve trade in `ctx` (`token` + `fundingAsset`): what
     * comes in (ether for buys, the Permit2 pull for sells), every step, what goes out, and that
     * every asset the route touches is returned to the wallet.
     */
    fun rule(router: String) = ContractRule(tr("Nesty Router")) { tx, ctx -> validate(router, tx, ctx) }

    private fun validate(router: String, tx: TxDraft, ctx: ValidationContext): List<BlockedAction> {
        val call = runCatching { execute.decodeCall(tx.data) }.getOrNull() ?: return listOf(act(tr("Nesty Router call"), tr("Unknown function")))
        val calls = (call[0] as List<*>).map { c ->
            val l = c as List<*>
            RouterCall(l[0] as String, l[1] as BigInteger, l[2] as ByteArray, l[3] as String, l[4] as BigInteger, l[5] as String)
        }
        val input = call[1] as List<*>
        val permitTuple = input[0] as List<*>
        val permitted = permitTuple[0] as List<*>
        val permit = TransferPermit(permitted[0] as String, permitted[1] as BigInteger, permitTuple[1] as BigInteger, permitTuple[2] as BigInteger)
        val tokenOut = call[2] as String
        val minOut = call[3] as BigInteger
        val sweep = (call[4] as List<*>).map { it as String }
        val deadline = call[5] as BigInteger
        val token = ctx.token
        val asset = ctx.fundingAsset
        if (token == null || asset == null) return listOf(act(tr("Nesty Router"), tr("Not a funded curve trade")))
        val out = mutableListOf<BlockedAction>()
        val nowS = big(ctx.nowS)
        if (deadline < nowS || deadline > nowS + big(MAX_WINDOW_S)) out += act("Deadline", tr("Deadline outside the allowed window"))
        if (minOut.signum() == 0) out += act(tr("Minimum output"), tr("No minimum output (no slippage protection)"))

        if (permit.amount.signum() > 0) {
            out += checkTransferPermit(permit, router, ctx)
            if (!sameAddress(tokenOut, NATIVE)) out += act("Output", tr("A sell must pay out native"))
            if (tx.value.signum() != 0) out += act("Value", tr("A sell must not carry native value"))
        } else {
            if (!sameAddress(tokenOut, token)) out += act("Output", tr("A buy must pay out the selected token"))
            if (tx.value.signum() == 0) out += act("Value", tr("A buy without native input"))
        }

        val assets = listOf(token, asset, ctx.chain.wrappedNative)
        val isAsset = { a: String -> assets.any { sameAddress(it, a) } }
        for (a in assets) {
            if (!sameAddress(a, tokenOut) && sweep.none { sameAddress(it, a) }) {
                out += act("Return ${shortAddress(a)}", tr("Left in the router instead of returned to you"))
            }
        }
        for (s in sweep) if (!isAsset(s)) out += act("Return ${shortAddress(s)}", tr("Unexpected asset"))
        if (calls.isEmpty()) out += act("Steps", tr("No steps"))

        val stepCtx = ctx.copy(
            from = router,
            // What a step pays the router is returned to the wallet at the end (checked above).
            ownWallets = ctx.ownWallets + router,
            payee = null,
            maxValue = tx.value,
        )
        var fixedValue = BigInteger.ZERO
        calls.forEachIndexed { i, c ->
            fun prefix(a: BlockedAction) = a.copy(label = "Step ${i + 1}: ${a.label}")
            if (c.value != ALL_VALUE) fixedValue += c.value
            if (sameAddress(c.target, router) || sameAddress(c.target, Permit2.ADDRESS)) {
                out += prefix(act(tr("Call to {1}", shortAddress(c.target)), tr("Permit2 and the router itself are off limits")))
                return@forEachIndexed
            }
            var data = c.data
            if (!sameAddress(c.injectToken, ZERO_ADDRESS)) {
                val len = big(data.size)
                if (!isAsset(c.injectToken) && !sameAddress(c.injectToken, NATIVE)) {
                    out += prefix(act(tr("Amount of {1}", shortAddress(c.injectToken)), tr("Unexpected asset")))
                    return@forEachIndexed
                }
                if (c.injectOffset < big(4) || c.injectOffset + big(32) > len) {
                    out += prefix(act(tr("Amount slot"), tr("Outside the call data")))
                    return@forEachIndexed
                }
                data = withPlaceholder(data, c.injectOffset.toInt())
            }
            if (!sameAddress(c.spender, ZERO_ADDRESS)) {
                // The router lets `spender` pull the injected ERC-20 for this call only.
                val l = tr("Let {1} take {2} for this step", shortAddress(c.spender), shortAddress(c.injectToken))
                if (sameAddress(c.injectToken, ZERO_ADDRESS) || sameAddress(c.injectToken, NATIVE)) {
                    out += prefix(act(l, tr("Approval without an injected token amount")))
                    return@forEachIndexed
                }
                if (ctx.extraContracts[c.spender.lowercase()]?.approvalSpender != true) {
                    out += prefix(act(l, tr("Spender is not an allowlisted venue")))
                    return@forEachIndexed
                }
                out += prefix(act(l))
            }
            val value = if (c.value == ALL_VALUE) tx.value else c.value
            val step = TxDraft(c.target, data, value)
            out += checkStep(c, step, calls.getOrNull(i + 1), stepCtx, isAsset).map { prefix(it) }
        }
        if (fixedValue > tx.value) out += act("Value", tr("Steps spend more than the transaction value"))
        return out
    }
}
