package trade.nesty.app.sol

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import trade.nesty.app.evm.bool
import trade.nesty.app.evm.int
import trade.nesty.app.evm.str
import trade.nesty.app.keys.Sol
import java.math.BigInteger

/**
 * Addresses, instructions, compiled messages, a signature and error sentences, each against
 * what the extension's own Solana code wrote (fixtures/sol/basics.json).
 */
class SolBasicsTest {
    private val v = SolVectors.load("basics")
    private val wallet = v.getValue("wallet").str()

    @Test
    fun madeUpSeedGivesTheVectorsWallet() {
        assertEquals(wallet, Sol.address(SolVectors.MADE_UP_SEED))
        assertEquals(v.getValue("wsol").str(), SolAddr.wsolAccount(wallet))
    }

    @Test
    fun associatedTokenAccounts() {
        val atas = v.getValue("atas").jsonArray
        for (e in atas) {
            val a = e.jsonObject
            assertEquals(a.toString(), a.getValue("ata").str(), SolAddr.associatedTokenAddress(a.getValue("owner").str(), a.getValue("mint").str(), a.getValue("program").str()))
        }
        assertEquals(24, atas.size)
    }

    @Test
    fun coinAddresses() {
        for (e in v.getValue("coins").jsonArray) {
            val c = e.jsonObject
            val mint = c.getValue("mint").str()
            assertEquals(c.getValue("metadata").str(), SolAddr.metadataAddress(mint))
            assertEquals(c.getValue("curve").str(), Pump.bondingCurveAddress(mint))
            assertEquals(c.getValue("poolSol").str(), Pump.canonicalPoolAddress(mint, SolAddr.WSOL_MINT))
            assertEquals(c.getValue("poolUsdc").str(), Pump.canonicalPoolAddress(mint, SolAddr.USDC_MINT))
        }
    }

    @Test
    fun programAddressesAndTheCurve() {
        for (e in v.getValue("pdas").jsonArray) {
            val p = e.jsonObject
            val seeds = p.getValue("seeds").jsonArray.map { SolVectors.bytes(it.str()) }
            val (address, bump) = SolAddr.findProgramAddressWithBump(seeds, p.getValue("program").str())
            assertEquals(p.getValue("address").str(), address)
            assertEquals(p.getValue("bump").int(), bump)
        }
        for (e in v.getValue("onCurve").jsonArray) {
            val o = e.jsonObject
            val address = o.getValue("address").str()
            assertEquals(address, o.getValue("onCurve").bool(), SolAddr.isOnCurve(SolAddr.addressBytes(address)))
        }
    }

    @Test
    fun compactLengths() {
        for (e in v.getValue("compact").jsonArray) {
            val c = e.jsonObject
            val want = c.getValue("bytes").jsonArray.map { it.int().toByte() }.toByteArray()
            assertArrayEquals("n=${c.getValue("n")}", want, SolTx.compactU16(c.getValue("n").int()))
        }
    }

    private fun named(): Map<String, Instruction> {
        val wsol = SolAddr.wsolAccount(wallet)
        val coin = "5dWLmCFzZ8tRFEYpCSyWJw2e6JwFE4Rk7MqRuD2opump"
        return mapOf(
            "unitLimit" to Spl.setComputeUnitLimit(123_456),
            "unitPrice" to Spl.setComputeUnitPrice(BigInteger.valueOf(4_053_498)),
            "transfer" to Spl.transfer(wallet, SolVectors.STRANGER, BigInteger.valueOf(1_000_000)),
            "openWsol" to Spl.createAtaIdempotent(wallet, SolAddr.WSOL_MINT, SolAddr.TOKEN_PROGRAM),
            "openCoin2022" to Spl.createAtaIdempotent(wallet, coin, SolAddr.TOKEN_2022_PROGRAM),
            "openForOther" to Spl.createAtaIdempotent(SolVectors.OTHER, SolAddr.USDC_MINT, SolAddr.TOKEN_PROGRAM, payer = wallet),
            "wrap" to Spl.transfer(wallet, wsol, BigInteger.valueOf(250_000_000)),
            "sync" to Spl.syncNative(wsol),
            "close" to Spl.closeAccount(wsol, wallet),
        )
    }

    @Test
    fun instructions() {
        val named = named()
        for (e in v.getValue("instructions").jsonArray) {
            val o = e.jsonObject
            val name = o.getValue("name").str()
            val want = SolVectors.instruction(o)
            val got = named.getValue(name)
            assertEquals(name, want.programId, got.programId)
            assertEquals(name, want.keys, got.keys)
            assertEquals(name, SolVectors.hex(want.data), SolVectors.hex(got.data))
        }
    }

    @Test
    fun messagesTransactionsAndASignature() {
        val named = named()
        var signedOne = false
        for (e in v.getValue("messages").jsonArray) {
            val m = e.jsonObject
            val payer = m.getValue("payer").str()
            val ixs = m.getValue("ixs").jsonArray.map { named.getValue(it.str()) }
            val compiled = SolTx.compileMessage(payer, ixs, m.getValue("blockhash").str())
            assertEquals(m.getValue("message").str(), SolVectors.hex(compiled.bytes))
            assertEquals(m.getValue("accounts").jsonArray.map { it.str() }, compiled.accounts)
            assertEquals(m.getValue("numSigners").int(), compiled.numSigners)
            assertEquals(m.getValue("unsigned").str(), SolVectors.hex(SolTx.serializeTransaction(compiled, emptyList())))
            val sig = m["signature"]?.jsonPrimitive?.content ?: continue
            // MADE-UP seed, made-up blockhash: Ed25519 is deterministic, so the bytes must match.
            val signature = Sol.sign(compiled.bytes, SolVectors.MADE_UP_SEED, payer)
            assertEquals(sig, SolVectors.hex(signature))
            assertEquals(m.getValue("signed").str(), SolVectors.hex(SolTx.serializeTransaction(compiled, listOf(signature))))
            signedOne = true
        }
        assertEquals(true, signedOne)
    }

    @Test
    fun errorSentences() {
        for (e in v.getValue("errors").jsonArray) {
            val c = e.jsonObject
            val programs = c.getValue("programs").jsonArray.map { Instruction(it.str(), emptyList(), ByteArray(0)) }
            assertEquals(c.toString(), c.getValue("text").str(), SolErrors.explain(c["err"], programs))
        }
    }
}
