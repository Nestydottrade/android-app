package trade.nesty.app.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.evm.parseEther
import trade.nesty.app.sol.SolAccount
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolRecipientKind
import trade.nesty.app.sol.SolSend
import trade.nesty.app.sol.SolVectors
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

/** Where funds come from and what stays behind: the arithmetic of One Balance, with no reads. */
class PlansTest {
    private val rent = BigInteger.valueOf(890_880)
    private val solFee = BigInteger.valueOf(505_000)
    private val reserves = Chain.entries.associateWith { Plans.reserve(it, rent, solFee) }

    private fun eth(s: String) = parseEther(s)

    /** ETH at $3,000, BNB at $600, HYPE at $40, SOL at $150. */
    private fun f(chain: Chain, whole: String): Funds {
        val price = when (chain) {
            Chain.BSC -> 600.0
            Chain.ELYSIUM -> 40.0
            Chain.SOLANA -> 150.0
            else -> 3_000.0
        }
        val raw = java.math.BigDecimal(whole).movePointRight(chain.decimals).toBigIntegerExact()
        return Funds(chain, raw, price)
    }

    @Test
    fun whatStaysIsTheExtensionsBridgeReserve() {
        assertEquals(eth("0.002"), reserves[Chain.ETHEREUM])
        assertEquals(eth("0.0005"), reserves[Chain.BSC])
        assertEquals(eth("0.0002"), reserves[Chain.BASE])
        assertEquals(eth("0.0002"), reserves[Chain.ROBINHOOD])
        // Five times as much where leaving takes a swap first.
        assertEquals(eth("0.001"), reserves[Chain.ELYSIUM])
        // Solana: the rent a wallet keeps to exist, and two fees.
        assertEquals(rent + solFee + solFee, reserves[Chain.SOLANA])
    }

    @Test
    fun aWithdrawalTakesTheArrivingChainFirstThenTheFullest() {
        val funds = listOf(f(Chain.BASE, "0.01"), f(Chain.BSC, "0.5"), f(Chain.ETHEREUM, "0.02"), f(Chain.SOLANA, "1"))
        // Base holds $30 (29.40 can leave), BNB $300, Ethereum $60, Solana $150.
        val parts = Plans.withdrawParts(200.0, Chain.BASE, funds, reserves, only = null)!!
        assertEquals(listOf(Chain.BASE, Chain.BSC), parts.map { it.chain })
        assertEquals(eth("0.0098"), parts[0].raw)
        assertEquals(29.4, parts[0].usd, 1e-9)
        assertEquals(200.0 - 29.4, parts[1].usd, 1e-9)
        assertEquals(200.0, parts.sumOf { it.usd }, 1e-9)
    }

    @Test
    fun allIsNeverQuiteAll() {
        val funds = listOf(f(Chain.BASE, "0.01"), f(Chain.SOLANA, "1"))
        val max = Plans.withdrawMax(Chain.BASE, funds, reserves, null)
        assertEquals((0.01 - 0.0002) * 3_000 + (1.0 - 0.00190088) * 150, max, 1e-6)
        val parts = Plans.withdrawParts(max, Chain.BASE, funds, reserves, null)!!
        // Every chain gives all it can, and keeps its reserve.
        assertEquals(eth("0.0098"), parts.first { it.chain == Chain.BASE }.raw)
        assertEquals(BigInteger.valueOf(1_000_000_000) - rent - solFee - solFee, parts.first { it.chain == Chain.SOLANA }.raw)
        assertNull(Plans.withdrawParts(max + 0.01, Chain.BASE, funds, reserves, null))
    }

    @Test
    fun onlyOneChainTouchesNothingElse() {
        val funds = listOf(f(Chain.BASE, "0.01"), f(Chain.BSC, "0.5"))
        val parts = Plans.withdrawParts(20.0, Chain.BASE, funds, reserves, only = Chain.BSC)!!
        assertEquals(listOf(Chain.BSC), parts.map { it.chain })
        assertEquals((0.5 - 0.0005) * 600, Plans.withdrawMax(Chain.BASE, funds, reserves, Chain.BSC), 1e-9)
        // The limit drops to that chain's balance.
        assertNull(Plans.withdrawParts(29.5, Chain.BASE, funds, reserves, only = Chain.BASE))
    }

    @Test
    fun elysiumIsSentOnItselfButDoesNotLeave() {
        val funds = listOf(f(Chain.ELYSIUM, "10"), f(Chain.BASE, "0.01"))
        assertFalse(Plans.canLeave(Chain.ELYSIUM))
        assertEquals(0.0, Plans.withdrawMax(Chain.BASE, funds, reserves, Chain.ELYSIUM), 0.0)
        assertEquals((10 - 0.001) * 40, Plans.withdrawMax(Chain.ELYSIUM, funds, reserves, Chain.ELYSIUM), 1e-9)
        // To Elysium the others can still bridge in.
        val parts = Plans.withdrawParts(420.0, Chain.ELYSIUM, funds, reserves, null)!!
        assertEquals(listOf(Chain.ELYSIUM, Chain.BASE), parts.map { it.chain })
    }

    @Test
    fun dustIsNotBridged() {
        val funds = listOf(f(Chain.BASE, "0.01"), f(Chain.BSC, "0.0012"))
        // BNB can give $0.42: too little to bridge, so it is not counted.
        assertEquals(29.4, Plans.withdrawMax(Chain.BASE, funds, reserves, null), 1e-9)
        // On its own chain it is a plain send, which takes any amount.
        assertEquals((0.0012 - 0.0005) * 600, Plans.withdrawMax(Chain.BSC, funds, reserves, Chain.BSC), 1e-9)
        assertEquals((0.0012 - 0.0005) * 600 + 29.4, Plans.withdrawMax(Chain.BSC, funds, reserves, null), 1e-9)
    }

    @Test
    fun aBuyNeedsNothingMovedUpToTheSheetsMax() {
        val have = eth("1")
        val fee = eth("0.001")
        // The sheet's Max: 99% of the balance less twice the fee.
        val max = have * BigInteger.valueOf(99) / BigInteger.valueOf(100) - fee * BigInteger.TWO
        assertEquals(BigInteger.ZERO, Plans.buyShortfall(have, max, fee, BigInteger.ZERO))
        assertEquals(BigInteger.valueOf(100), Plans.buyShortfall(have, max + BigInteger.valueOf(99), fee, BigInteger.ZERO))
        // An empty chain needs all of it, rounded up.
        val wei = eth("0.5")
        val need = (wei + fee * BigInteger.TWO) * BigInteger.valueOf(100)
        assertEquals(need.divide(BigInteger.valueOf(99)) + BigInteger.ONE, Plans.buyShortfall(BigInteger.ZERO, wei, fee, BigInteger.ZERO))
    }

    @Test
    fun aBuyMovesFromTheFullestChainThatCoversItAlone() {
        val funds = listOf(f(Chain.BASE, "0.001"), f(Chain.BSC, "0.1"), f(Chain.ETHEREUM, "0.03"), f(Chain.ELYSIUM, "100"))
        // $50 short on Base: Ethereum can give $84, BNB $59.70; Elysium cannot leave.
        assertEquals(Chain.ETHEREUM, Plans.buySource(Chain.BASE, 50.0, funds, reserves)!!.first.chain)
        // $83 needs room for the bridge too: no one chain has it.
        assertNull(Plans.buySource(Chain.BASE, 83.0, funds, reserves))
        assertEquals(Plans.movableUsd(84.0), (84.0 - 0.10) / 1.03, 1e-12)
        assertTrue(Plans.roomFor(Plans.movableUsd(84.0)) <= 84.0 + 1e-9)
    }

    @Test
    fun theCapIsWhatWasShownAndOnePercentWithinWhatCanLeave() {
        assertEquals(BigInteger.valueOf(1_010), Plans.cap(BigInteger.valueOf(1_000), BigInteger.valueOf(5_000)))
        assertEquals(BigInteger.valueOf(1_005), Plans.cap(BigInteger.valueOf(1_000), BigInteger.valueOf(1_005)))
    }

    @Test
    fun aMoveCostsWhatDidNotArriveAndItsGas() {
        val leg = MoveLeg(LegKind.BRIDGE, "8453", "56", "1", usdIn = 12.716904, usdOut = 12.615372, quoteFeeUsd = 0.061879, gasUsd = 0.0004)
        assertEquals(12.716904 - 12.615372 + 0.0004, Plans.moveCostUsd(leg), 1e-12)
        // Without both prices, the bridge's own figure.
        assertEquals(0.061879, Plans.moveCostUsd(leg.copy(usdOut = null)), 1e-12)
        assertEquals(0.0, Plans.moveCostUsd(MoveLeg(LegKind.BRIDGE, "8453", "56", "1")), 0.0)
    }

    @Test
    fun typedAddressesAreReadAsTheExtensionReadsThem() {
        val checksummed = "0x11064Ffa76d1c861e9c19b3D36F66572f16f9576"
        assertEquals(Typed.EvmAddress(checksummed), Recipient.parse(checksummed))
        assertEquals(Typed.EvmAddress(checksummed), Recipient.parse("  " + checksummed.lowercase() + "\n"))
        // A typo in a mixed-case address is caught by its checksum.
        assertEquals(Typed.Bad(Recipient.BAD_CHECKSUM), Recipient.parse("0x11064ffa76d1c861e9c19b3D36F66572f16f9576"))
        assertEquals(Typed.Bad(Recipient.BAD_CHECKSUM), Recipient.parse(checksummed.uppercase().replace("0X", "0x")))
        assertEquals(Typed.SolAddress(SolVectors.STRANGER), Recipient.parse(SolVectors.STRANGER))
        assertEquals(Typed.Bad(Recipient.NOT_AN_ADDRESS), Recipient.parse("0x1234"))
        assertEquals(Typed.Bad(Recipient.NOT_AN_ADDRESS), Recipient.parse("pepe.eth"))
        assertNull(Recipient.parse("   "))
        // An address only works on its own kind of chain.
        assertTrue(Typed.EvmAddress(checksummed).fits(Chain.ELYSIUM))
        assertFalse(Typed.EvmAddress(checksummed).fits(Chain.SOLANA))
        assertTrue(Typed.SolAddress(SolVectors.STRANGER).fits(Chain.SOLANA))
        assertFalse(Typed.SolAddress(SolVectors.STRANGER).fits(Chain.BASE))
    }

    @Test
    fun solanaAddressesFundsAreLostAtAreRefused() {
        // The made-up seed's own address: a public key, so on the curve.
        val wallet = SolVectors.load("bridge").getValue("wallet").jsonPrimitive.content
        val pda = SolAddr.findProgramAddress(listOf(SolAddr.seed("vault")), RelaySol.PROGRAM)
        assertEquals(SolRecipientKind.UNUSED, SolSend.recipientKind(wallet, null))
        assertEquals(SolRecipientKind.PROGRAM_ACCOUNT, SolSend.recipientKind(pda, null))
        assertEquals(SolRecipientKind.WALLET, SolSend.recipientKind(wallet, SolAccount(SolAddr.SYSTEM_PROGRAM, BigInteger.ONE, ByteArray(0))))
        assertEquals(SolRecipientKind.PROGRAM, SolSend.recipientKind(wallet, SolAccount("BPFLoaderUpgradeab1e11111111111111111111111", BigInteger.ONE, ByteArray(36), executable = true)))
        assertEquals(SolRecipientKind.TOKEN_ACCOUNT, SolSend.recipientKind(wallet, SolAccount(SolAddr.TOKEN_PROGRAM, BigInteger.ONE, ByteArray(165))))
        val ext = ByteArray(170).also { it[165] = 2 }
        assertEquals(SolRecipientKind.TOKEN_ACCOUNT, SolSend.recipientKind(wallet, SolAccount(SolAddr.TOKEN_2022_PROGRAM, BigInteger.ONE, ext)))
        assertEquals(SolRecipientKind.MINT, SolSend.recipientKind(wallet, SolAccount(SolAddr.TOKEN_PROGRAM, BigInteger.ONE, ByteArray(82))))
        assertEquals(SolSend.NOT_A_WALLET, SolSend.recipientBlock(wallet, SolAccount(SolAddr.TOKEN_PROGRAM, BigInteger.ONE, ByteArray(82))))
        assertNull(SolSend.recipientBlock(pda, null))
        // A new address must be paid its rent to exist.
        assertEquals("A new address needs at least 0.00089088 SOL to exist.", SolSend.newAddressBlock(null, rent - BigInteger.ONE, rent))
        assertNull(SolSend.newAddressBlock(null, rent, rent))
        assertEquals(
            "A new Solana wallet needs at least 0.00089088 SOL to exist: bridge a little more.",
            SolSend.payoutBlock(null, rent - BigInteger.ONE, rent),
        )
        assertNull(SolSend.payoutBlock(SolAccount(SolAddr.SYSTEM_PROGRAM, BigInteger.ONE, ByteArray(0)), BigInteger.ONE, rent))
    }

    @Test
    fun theBridgesWordsNameNoProvider() {
        assertEquals("Bridge service: bridge solver is busy", Mover.bridgeSays("Bridge service: Relay solver is busy"))
        assertEquals("Bridge service: Amount too low", Mover.bridgeSays("Bridge service: Amount too low"))
        assertEquals("The bridge did not answer.", Mover.bridgeSays(null))
    }

    private class MemoryDisk : MoveDisk {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    @Test
    fun movesSurviveTheAppBeingClosed() {
        val disk = MemoryDisk()
        val store = MoveStore(disk) { 42L }
        val leg = MoveLeg(LegKind.BRIDGE, Chain.BASE.feed, Chain.BSC.feed, "6582973617896588", amountOut = "20000000000000000")
        store.put(Move("m1", MoveKind.BUY, "w1", 1L, Chain.BSC.feed, "0xabc", 25.0, listOf(leg), token = "0xcoin", symbol = "PEPE"))
        store.leg("m1", 0) { it.copy(status = LegStatus.MOVING, hash = "0x" + "ab".repeat(32), requestId = "0x" + "17".repeat(32)) }
        val again = MoveStore(disk).get("m1")
        assertNotNull(again)
        assertEquals(LegStatus.MOVING, again!!.legs[0].status)
        assertEquals("0x" + "ab".repeat(32), again.legs[0].hash)
        assertEquals("PEPE", again.symbol)
        assertEquals(42L, again.updatedAt)
        assertEquals(MoveState.WORKING, again.state)
        assertEquals(listOf("m1"), MoveStore(disk).of("w1").map { it.id })
        assertTrue(MoveStore(disk).of("w2").isEmpty())
    }

    @Test
    fun aWithdrawalEndsWhenEveryLegHas() {
        val send = MoveLeg(LegKind.SEND, "8453", "8453", "1", usdOut = 10.0)
        val bridge = MoveLeg(LegKind.BRIDGE, "56", "8453", "1", usdOut = 5.0)
        fun m(vararg l: MoveLeg) = Move("m", MoveKind.WITHDRAW, "w", 0, "8453", "0xabc", 15.0, l.toList())
        assertEquals(MoveState.WORKING, m(send.copy(status = LegStatus.DONE), bridge.copy(status = LegStatus.MOVING)).settled())
        assertEquals(MoveState.DONE, m(send.copy(status = LegStatus.DONE), bridge.copy(status = LegStatus.DONE)).settled())
        val part = m(send.copy(status = LegStatus.DONE), bridge.copy(status = LegStatus.REFUNDED))
        assertEquals(MoveState.PARTIAL, part.settled())
        assertEquals(10.0, part.arrivedUsd, 0.0)
        assertEquals(MoveState.FAILED, m(send.copy(status = LegStatus.FAILED), bridge.copy(status = LegStatus.FAILED)).settled())
        // Not known is never called failed.
        assertEquals(MoveState.PARTIAL, m(send.copy(status = LegStatus.FAILED), bridge.copy(status = LegStatus.UNKNOWN)).settled())
    }
}
