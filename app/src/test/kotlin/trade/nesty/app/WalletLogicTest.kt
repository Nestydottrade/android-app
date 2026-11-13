package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Balances
import trade.nesty.app.data.BalancesAnswer
import trade.nesty.app.data.Chain
import trade.nesty.app.data.ChainBalance
import trade.nesty.app.data.WalletBalance
import trade.nesty.app.ui.Fmt
import trade.nesty.app.ui.menu.inFours
import trade.nesty.app.ui.menu.oneBalanceText
import trade.nesty.app.ui.vault.SecretLooks
import trade.nesty.app.ui.vault.looksLike
import trade.nesty.app.ui.vault.secretNote
import trade.nesty.app.ui.vault.whereOf
import trade.nesty.app.vault.WalletInfo
import trade.nesty.app.vault.WalletKind

/**
 * One Balance and the wallet forms' words. `balancesEvm` and `balancesSol` are real answers for
 * the public test phrase's two addresses (KeysTest says what that phrase is).
 */
class WalletLogicTest {
    private val evm = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val sol = "oeYf6KAJkLYhBuR8CiGc6L4D4Xtfepr85fuDgA9kq96"
    private val both = WalletInfo("a", "Main", WalletKind.PHRASE, evm, sol)
    private val evmOnly = WalletInfo("b", "Key", WalletKind.EVM_KEY, evm, null)
    private val solOnly = WalletInfo("c", "Sol", WalletKind.SOL_KEY, null, sol)

    @Test
    fun aCoinInItsSmallestUnitBecomesDollars() {
        assertEquals(2000.0, Balances.usd("1000000000000000000", 18, 2000.0)!!, 1e-9)
        assertEquals(0.5 * 650.25, Balances.usd("500000000000000000", 18, 650.25)!!, 1e-9)
        assertEquals(80.0, Balances.usd("1000000000", 9, 80.0)!!, 1e-9)
        assertEquals(0.0, Balances.usd("0", 18, 2000.0)!!, 0.0)
        // More than a double holds exactly: read as a whole number first.
        assertEquals(123456789.123456789 * 2, Balances.usd("123456789123456789000000000", 18, 2.0)!!, 1e-3)
        assertNull(Balances.usd("1", 18, null))
        assertNull(Balances.usd("not a number", 18, 1.0))
        assertNull(Balances.usd("-5", 18, 1.0))
    }

    @Test
    fun oneBalanceSumsTheChainsAWalletIsOn() {
        val e = Fixtures.read<BalancesAnswer>("balancesEvm")
        val s = Fixtures.read<BalancesAnswer>("balancesSol")
        val b = Balances.of(both, e, s)
        assertEquals(Chain.entries.toSet(), b.chains.keys)
        assertTrue(b.unread.isEmpty())
        val lamports = s.chains.single()
        assertEquals(lamports.native.toDouble() / 1e9 * lamports.usd!!, b.chains[Chain.SOLANA]!!, 1e-9)
        assertEquals(b.chains.values.sum(), b.total, 0.0)
        // A key covers its own side only.
        assertEquals(setOf(Chain.SOLANA), Balances.of(solOnly, null, s).chains.keys)
        assertEquals(Chain.entries.filter { it.isEvm }, Balances.chainsOf(evmOnly))
        assertTrue(Chain.SOLANA !in Balances.of(evmOnly, e, s).chains)
    }

    @Test
    fun aChainThatWasNotReadIsNotAZero() {
        val e = BalancesAnswer(
            evm,
            listOf(
                ChainBalance("56", "1000000000000000000", 600.0),
                ChainBalance("8453", "0", 2000.0, failed = true),
                ChainBalance("1", "1000000000000000000", null),
            ),
        )
        val first = Balances.of(both, e, null)
        assertEquals(mapOf(Chain.BSC to 600.0), first.chains)
        assertEquals(setOf(Chain.BASE, Chain.ETHEREUM, Chain.ROBINHOOD, Chain.ELYSIUM, Chain.SOLANA), first.unread)
        // The next read fails altogether: what was read stays on screen, marked as not read now.
        val second = Balances.of(both, null, null, first)
        assertEquals(600.0, second.total, 0.0)
        assertTrue(Chain.BSC in second.unread)
    }

    @Test
    fun oneBalanceAsAFigure() {
        assertEquals("$0.00", oneBalanceText(null, null))
        assertEquals(Fmt.DASH, oneBalanceText(both, null))
        assertEquals("$223.17", oneBalanceText(both, WalletBalance(mapOf(Chain.BSC to 200.0, Chain.SOLANA to 23.17), emptySet())))
    }

    @Test
    fun aDepositAddressIsWrittenInFours() {
        assertEquals("0xf39F d6e5 1aad 88F6 F4ce 6aB8 8272 79cf fFb9 2266", inFours(evm))
        assertEquals("oeYf 6KAJ kLYh BuR8 CiGc 6L4D 4Xtf epr8 5fuD gA9k q96", inFours(sol))
        assertEquals(evm, inFours(evm).replace(" ", ""))
    }

    @Test
    fun whereAWalletLives() {
        assertEquals("${Fmt.address(evm)} · ${Fmt.address(sol)}", whereOf(both))
        assertEquals("${Fmt.address(evm)} · EVM only", whereOf(evmOnly))
        assertEquals("${Fmt.address(sol)} · Solana only", whereOf(solOnly))
    }

    /** The phrase and keys here are the public test data of `keyVectors.json`. */
    @Test
    fun theLineUnderTheImportBox() {
        val phrase = "test test test test test test test test test test test junk"
        assertEquals(SecretLooks.EMPTY, looksLike("  ", phrase = true))
        assertEquals(SecretLooks.PHRASE, looksLike(phrase, phrase = true))
        assertEquals(SecretLooks.SHORT_PHRASE, looksLike("test test test", phrase = true))
        assertEquals(SecretLooks.BAD_PHRASE, looksLike(phrase.replace("junk", "test"), phrase = true))
        assertEquals(SecretLooks.EVM_KEY, looksLike("0x" + "4c1e9a7b".repeat(8), phrase = false))
        assertEquals(SecretLooks.BAD_KEY, looksLike(phrase, phrase = false))
        assertEquals(SecretLooks.BAD_KEY, looksLike(sol, phrase = false))
        assertEquals("12 words. It gets its EVM address and its Solana address.", secretNote(SecretLooks.PHRASE, true, 12))
        assertEquals("3 words so far. A phrase has 12 or 24.", secretNote(SecretLooks.SHORT_PHRASE, true, 3))
        assertEquals("1 word so far. A phrase has 12 or 24.", secretNote(SecretLooks.SHORT_PHRASE, true, 1))
        assertEquals("An EVM key or a Solana key", secretNote(SecretLooks.EMPTY, false, 0))
        assertTrue(secretNote(SecretLooks.SOL_KEY, false, 0).startsWith("Solana key. This wallet will trade on Solana only"))
    }
}
