package trade.nesty.app.evm

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Universal Router v2.1.2 command encoding (Uniswap/universal-router@802fe4c,
 * v4-periphery@545a5d2), ported from the extension's uniswap/codec.ts. Only the commands Nesty
 * uses exist; the decoder refuses everything else, which the validator relies on.
 */
data class PoolKey(
    val currency0: String,
    val currency1: String,
    val fee: Int,
    val tickSpacing: Int,
    val hooks: String,
) {
    fun abi(): List<Any?> = listOf(currency0, currency1, BigInteger.valueOf(fee.toLong()), BigInteger.valueOf(tickSpacing.toLong()), hooks)

    companion object {
        fun of(v: Any?): PoolKey {
            val l = v as List<*>
            return PoolKey(
                (l[0] as String).lowercase(),
                (l[1] as String).lowercase(),
                (l[2] as BigInteger).toInt(),
                (l[3] as BigInteger).toInt(),
                (l[4] as String).lowercase(),
            )
        }
    }
}

data class V3Hop(val tokenIn: String, val fee: Int, val tokenOut: String)

sealed interface V4Action {
    data class SwapExactInSingle(
        val poolKey: PoolKey,
        val zeroForOne: Boolean,
        val amountIn: BigInteger,
        val amountOutMinimum: BigInteger,
        val minHopPriceX36: BigInteger,
        val hookData: ByteArray,
    ) : V4Action {
        override fun equals(other: Any?) = other is SwapExactInSingle && poolKey == other.poolKey &&
            zeroForOne == other.zeroForOne && amountIn == other.amountIn && amountOutMinimum == other.amountOutMinimum &&
            minHopPriceX36 == other.minHopPriceX36 && hookData.contentEquals(other.hookData)
        override fun hashCode() = poolKey.hashCode()
    }

    data class Settle(val currency: String, val amount: BigInteger, val payerIsUser: Boolean) : V4Action
    data class SettleAll(val currency: String, val maxAmount: BigInteger) : V4Action
    data class Take(val currency: String, val recipient: String, val amount: BigInteger) : V4Action
    data class TakeAll(val currency: String, val minAmount: BigInteger) : V4Action
}

sealed interface Command {
    data class WrapEth(val recipient: String, val amount: BigInteger) : Command
    data class UnwrapWeth(val recipient: String, val amountMin: BigInteger) : Command
    data class Sweep(val token: String, val recipient: String, val amountMin: BigInteger) : Command
    data class V2SwapExactIn(
        val recipient: String,
        val amountIn: BigInteger,
        val amountOutMin: BigInteger,
        val path: List<String>,
        val payerIsUser: Boolean,
    ) : Command
    data class V3SwapExactIn(
        val recipient: String,
        val amountIn: BigInteger,
        val amountOutMin: BigInteger,
        val path: List<V3Hop>,
        val payerIsUser: Boolean,
    ) : Command
    data class Permit2Permit(val permit: PermitSingle, val signature: ByteArray) : Command {
        override fun equals(other: Any?) = other is Permit2Permit && permit == other.permit && signature.contentEquals(other.signature)
        override fun hashCode() = permit.hashCode()
    }
    data class V4Swap(val actions: List<V4Action>) : Command
}

class DecodeError(message: String) : Exception(message)

data class DecodedExecute(val commands: List<Command>, val deadline: BigInteger)

object UniversalRouter {
    const val MSG_SENDER = "0x0000000000000000000000000000000000000001"
    const val ADDRESS_THIS = "0x0000000000000000000000000000000000000002"
    const val NATIVE = "0x0000000000000000000000000000000000000000"
    /** v4 "use the full open delta" amount. */
    val OPEN_DELTA: BigInteger = BigInteger.ZERO
    /** "The router's whole balance of this token". */
    val CONTRACT_BALANCE: BigInteger = BigInteger.ONE.shiftLeft(255)
    /** Smallest non-zero minimum for a leg whose output stays in the router. */
    val INNER_MIN: BigInteger = BigInteger.ONE

    val execute = AbiFunction("function execute(bytes commands, bytes[] inputs, uint256 deadline) payable")

    private const val V3_SWAP_EXACT_IN = 0x00
    private const val SWEEP = 0x04
    private const val V2_SWAP_EXACT_IN = 0x08
    private const val PERMIT2_PERMIT = 0x0a
    private const val WRAP_ETH = 0x0b
    private const val UNWRAP_WETH = 0x0c
    private const val V4_SWAP = 0x10

    private const val SWAP_EXACT_IN_SINGLE = 0x06
    private const val SETTLE = 0x0b
    private const val SETTLE_ALL = 0x0c
    private const val TAKE = 0x0e
    private const val TAKE_ALL = 0x0f

    private val addrUint = Abi.params("address, uint256")
    private val addrAddrUint = Abi.params("address, address, uint256")
    private val v2 = Abi.params("address, uint256, uint256, address[], bool, uint256[]")
    private val v3 = Abi.params("address, uint256, uint256, bytes, bool, uint256[]")
    private val permit = Abi.params(
        "((address token, uint160 amount, uint48 expiration, uint48 nonce) details, address spender, uint256 sigDeadline), bytes",
    )
    private val v4Swap = Abi.params("bytes, bytes[]")
    private val v4ExactInSingle = Abi.params(
        "((address currency0, address currency1, uint24 fee, int24 tickSpacing, address hooks) poolKey, bool zeroForOne, uint128 amountIn, uint128 amountOutMinimum, uint256 minHopPriceX36, bytes hookData)",
    )
    private val settle = Abi.params("address, uint256, bool")
    private val take = Abi.params("address, address, uint256")

    // ── v3 paths ────────────────────────────────────────────────────────────

    fun encodeV3Path(hops: List<V3Hop>): ByteArray {
        require(hops.isNotEmpty()) { "empty v3 path" }
        val out = ByteArrayOutputStream()
        out.write(hops[0].tokenIn.hexBytes())
        for (h in hops) {
            out.write(byteArrayOf((h.fee shr 16).toByte(), (h.fee shr 8).toByte(), h.fee.toByte()))
            out.write(h.tokenOut.hexBytes())
        }
        return out.toByteArray()
    }

    fun decodeV3Path(path: ByteArray): List<V3Hop> {
        val len = path.size
        // A plain error, as in codec.ts: inside a command it reads "malformed input".
        if (len < 43 || (len - 20) % 23 != 0) throw IllegalArgumentException("bad v3 path")
        val hops = mutableListOf<V3Hop>()
        var o = 0
        while (o + 43 <= len) {
            val fee = ((path[o + 20].toInt() and 0xff) shl 16) or ((path[o + 21].toInt() and 0xff) shl 8) or (path[o + 22].toInt() and 0xff)
            hops += V3Hop(path.copyOfRange(o, o + 20).toHex(), fee, path.copyOfRange(o + 23, o + 43).toHex())
            o += 23
        }
        return hops
    }

    // ── encode ──────────────────────────────────────────────────────────────

    private fun int(n: Int) = BigInteger.valueOf(n.toLong())

    private fun encodeAction(a: V4Action): ByteArray = when (a) {
        is V4Action.SwapExactInSingle -> Abi.encode(
            v4ExactInSingle,
            listOf(listOf(a.poolKey.abi(), a.zeroForOne, a.amountIn, a.amountOutMinimum, a.minHopPriceX36, a.hookData)),
        )
        is V4Action.Settle -> Abi.encode(settle, listOf(a.currency, a.amount, a.payerIsUser))
        is V4Action.SettleAll -> Abi.encode(addrUint, listOf(a.currency, a.maxAmount))
        is V4Action.Take -> Abi.encode(take, listOf(a.currency, a.recipient, a.amount))
        is V4Action.TakeAll -> Abi.encode(addrUint, listOf(a.currency, a.minAmount))
    }

    private fun actionCode(a: V4Action) = when (a) {
        is V4Action.SwapExactInSingle -> SWAP_EXACT_IN_SINGLE
        is V4Action.Settle -> SETTLE
        is V4Action.SettleAll -> SETTLE_ALL
        is V4Action.Take -> TAKE
        is V4Action.TakeAll -> TAKE_ALL
    }

    private fun code(c: Command) = when (c) {
        is Command.WrapEth -> WRAP_ETH
        is Command.UnwrapWeth -> UNWRAP_WETH
        is Command.Sweep -> SWEEP
        is Command.V2SwapExactIn -> V2_SWAP_EXACT_IN
        is Command.V3SwapExactIn -> V3_SWAP_EXACT_IN
        is Command.Permit2Permit -> PERMIT2_PERMIT
        is Command.V4Swap -> V4_SWAP
    }

    private fun encodeInput(c: Command): ByteArray = when (c) {
        is Command.WrapEth -> Abi.encode(addrUint, listOf(c.recipient, c.amount))
        is Command.UnwrapWeth -> Abi.encode(addrUint, listOf(c.recipient, c.amountMin))
        is Command.Sweep -> Abi.encode(addrAddrUint, listOf(c.token, c.recipient, c.amountMin))
        is Command.V2SwapExactIn -> Abi.encode(v2, listOf(c.recipient, c.amountIn, c.amountOutMin, c.path, c.payerIsUser, emptyList<Any>()))
        is Command.V3SwapExactIn -> Abi.encode(v3, listOf(c.recipient, c.amountIn, c.amountOutMin, encodeV3Path(c.path), c.payerIsUser, emptyList<Any>()))
        is Command.Permit2Permit -> Abi.encode(
            permit,
            listOf(
                listOf(
                    listOf(c.permit.token, c.permit.amount, BigInteger.valueOf(c.permit.expiration), BigInteger.valueOf(c.permit.nonce)),
                    c.permit.spender,
                    c.permit.sigDeadline,
                ),
                c.signature,
            ),
        )
        is Command.V4Swap -> Abi.encode(
            v4Swap,
            listOf(ByteArray(c.actions.size) { actionCode(c.actions[it]).toByte() }, c.actions.map { encodeAction(it) }),
        )
    }

    fun encodeExecute(commands: List<Command>, deadline: BigInteger): ByteArray =
        execute.encode(ByteArray(commands.size) { code(commands[it]).toByte() }, commands.map { encodeInput(it) }, deadline)

    // ── decode (the validator's; throws DecodeError on anything unknown) ───

    private fun decodeActions(input: ByteArray): List<V4Action> {
        val (actionBytes, params) = Abi.decode(v4Swap, input).let { it[0] as ByteArray to it[1] as List<*> }
        val n = actionBytes.size
        if (n != params.size) throw DecodeError("v4 actions/params length mismatch")
        val out = mutableListOf<V4Action>()
        for (k in 0 until n) {
            val code = actionBytes[k].toInt() and 0xff
            val p = params[k] as ByteArray
            out += when (code) {
                SWAP_EXACT_IN_SINGLE -> {
                    val s = Abi.decode(v4ExactInSingle, p)[0] as List<*>
                    V4Action.SwapExactInSingle(
                        PoolKey.of(s[0]),
                        s[1] as Boolean,
                        s[2] as BigInteger,
                        s[3] as BigInteger,
                        s[4] as BigInteger,
                        s[5] as ByteArray,
                    )
                }
                SETTLE -> Abi.decode(settle, p).let { V4Action.Settle(it[0] as String, it[1] as BigInteger, it[2] as Boolean) }
                SETTLE_ALL -> Abi.decode(addrUint, p).let { V4Action.SettleAll(it[0] as String, it[1] as BigInteger) }
                TAKE -> Abi.decode(take, p).let { V4Action.Take(it[0] as String, it[1] as String, it[2] as BigInteger) }
                TAKE_ALL -> Abi.decode(addrUint, p).let { V4Action.TakeAll(it[0] as String, it[1] as BigInteger) }
                else -> throw DecodeError("unsupported v4 action 0x${"%02x".format(code)}")
            }
        }
        return out
    }

    private fun decodeInput(code: Int, input: ByteArray): Command = when (code) {
        WRAP_ETH -> Abi.decode(addrUint, input).let { Command.WrapEth(it[0] as String, it[1] as BigInteger) }
        UNWRAP_WETH -> Abi.decode(addrUint, input).let { Command.UnwrapWeth(it[0] as String, it[1] as BigInteger) }
        SWEEP -> Abi.decode(addrAddrUint, input).let { Command.Sweep(it[0] as String, it[1] as String, it[2] as BigInteger) }
        V2_SWAP_EXACT_IN -> Abi.decode(v2, input).let {
            Command.V2SwapExactIn(it[0] as String, it[1] as BigInteger, it[2] as BigInteger, (it[3] as List<*>).map { a -> a as String }, it[4] as Boolean)
        }
        V3_SWAP_EXACT_IN -> Abi.decode(v3, input).let {
            Command.V3SwapExactIn(it[0] as String, it[1] as BigInteger, it[2] as BigInteger, decodeV3Path(it[3] as ByteArray), it[4] as Boolean)
        }
        PERMIT2_PERMIT -> Abi.decode(permit, input).let {
            val p = it[0] as List<*>
            val d = p[0] as List<*>
            Command.Permit2Permit(
                PermitSingle(
                    d[0] as String,
                    d[1] as BigInteger,
                    (d[2] as BigInteger).toLong(),
                    (d[3] as BigInteger).toLong(),
                    p[1] as String,
                    p[2] as BigInteger,
                ),
                it[1] as ByteArray,
            )
        }
        V4_SWAP -> Command.V4Swap(decodeActions(input))
        else -> throw DecodeError("unsupported router command 0x${"%02x".format(code)}")
    }

    /** Decodes Universal Router calldata. Throws DecodeError for anything outside the subset. */
    fun decodeExecute(data: ByteArray): DecodedExecute {
        val args = try {
            execute.decodeCall(data)
        } catch (e: Exception) {
            null
        } ?: throw DecodeError("not a Universal Router execute(bytes,bytes[],uint256) call")
        val cmdBytes = args[0] as ByteArray
        val inputs = args[1] as List<*>
        val deadline = args[2] as BigInteger
        val n = cmdBytes.size
        if (n == 0 || n != inputs.size) throw DecodeError("commands/inputs length mismatch")
        val commands = mutableListOf<Command>()
        for (k in 0 until n) {
            val raw = cmdBytes[k].toInt() and 0xff
            if (raw and 0x80 != 0) throw DecodeError("allow-revert flag is not permitted")
            try {
                commands += decodeInput(raw, inputs[k] as ByteArray)
            } catch (e: DecodeError) {
                throw e
            } catch (e: Exception) {
                throw DecodeError("malformed input for command 0x${raw.toString(16)}")
            }
        }
        return DecodedExecute(commands, deadline)
    }
}
