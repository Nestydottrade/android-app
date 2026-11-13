package trade.nesty.app.evm

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import trade.nesty.app.keys.Evm
import trade.nesty.app.keys.keccak256

/**
 * ABI encoding, signed EIP-1559 transactions and Permit2 signatures, byte for byte as viem
 * made them in basics.json. Every signature is by the MADE-UP test key below: never funded,
 * never used on a chain.
 */
class BasicsTest {
    private val v = EvmVectors.load("basics")
    private val madeUpKey = "4c1e9a7b".repeat(8).hexBytes()

    @Test
    fun madeUpKeyIsTheVectorsWallet() {
        assertEquals(v.s("wallet").lowercase(), Evm.address(madeUpKey).lowercase())
    }

    @Test
    fun abiEncodesAndDecodesLikeViem() {
        for (c in v.arr("abi")) {
            val o = c.jsonObject
            val types = Abi.params(o.s("types"))
            val values = o.arr("values").mapIndexed { i, e -> EvmVectors.abiValue(types.items[i], e) }
            val encoded = Abi.encode(types, values)
            assertEquals(o.s("types"), o.s("encoded"), encoded.toHex())
            // Decoding gives the same values back (addresses lowercase, bytes as bytes).
            val again = Abi.encode(types, Abi.decode(types, encoded))
            assertEquals(o.s("types"), o.s("encoded"), again.toHex())
        }
    }

    @Test
    fun signsTransactionsLikeViem() {
        for (t in v.arr("txs")) {
            val o = t.jsonObject
            val tx = Tx1559(
                chainId = o.getValue("chainId").long(),
                nonce = o.getValue("nonce").long(),
                maxPriorityFeePerGas = o.getValue("maxPriorityFeePerGas").big(),
                maxFeePerGas = o.getValue("maxFeePerGas").big(),
                gas = o.getValue("gas").big(),
                to = o.s("to"),
                value = o.getValue("value").big(),
                data = o.s("data").hexBytes(),
            )
            val raw = tx.sign(madeUpKey)
            assertEquals("chain ${o.s("chainId")} nonce ${o.s("nonce")}", o.s("raw"), raw.toHex())
            assertEquals(o.s("hash"), keccak256(raw).toHex())
        }
    }

    @Test
    fun signsPermit2LikeViem() {
        for (p in v.arr("permits")) {
            val o = p.jsonObject
            val chainId = o.getValue("chainId").long()
            val permit = o.obj("permit")
            val typed = if (o.s("kind") == "single") {
                val d = permit.obj("details")
                Permit2.single(
                    chainId,
                    PermitSingle(d.s("token"), d.getValue("amount").big(), d.getValue("expiration").long(), d.getValue("nonce").long(), permit.s("spender"), permit.getValue("sigDeadline").big()),
                )
            } else {
                val t = permit.obj("permitted")
                Permit2.transfer(
                    chainId,
                    TransferPermit(t.s("token"), t.getValue("amount").big(), permit.getValue("nonce").big(), permit.getValue("deadline").big()),
                    o.s("spender"),
                )
            }
            assertEquals(o.s("digest"), typed.digest().toHex())
            assertEquals(o.s("signature"), typed.sign(madeUpKey).toHex())
        }
    }

    @Test
    fun functionSelectors() {
        assertEquals("0x70a08231", AbiFunction("function balanceOf(address) view returns (uint256)").selector.toHex())
        assertEquals("0x3593564c", AbiFunction("function execute(bytes commands, bytes[] inputs, uint256 deadline) payable").selector.toHex())
        assertEquals(
            "0xc6a5026a",
            AbiFunction("function quoteExactInputSingle((address tokenIn, address tokenOut, uint256 amountIn, uint24 fee, uint160 sqrtPriceLimitX96)) returns (uint256 amountOut, uint160, uint32, uint256 gasEstimate)").selector.toHex(),
        )
        assertEquals("0x82ad56cb", AbiFunction("function aggregate3((address target, bool allowFailure, bytes callData)[] calls) payable returns ((bool success, bytes returnData)[] returnData)").selector.toHex())
    }

    @Test
    fun signatureRecoversTheSigner() {
        val hash = keccak256("nesty".toByteArray())
        val sig = Secp256k1.sign(hash, madeUpKey)
        assertEquals(v.s("wallet").lowercase(), Secp256k1.recoverAddress(hash, sig))
        // Deterministic: the same hash gives the same signature.
        assertEquals(sig.toBytes65().toHex(), Secp256k1.sign(hash, madeUpKey).toBytes65().toHex())
    }
}
