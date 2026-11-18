package trade.nesty.app.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.Chain
import trade.nesty.app.evm.Erc20
import trade.nesty.app.evm.EvmTestKit
import trade.nesty.app.evm.TxDraft
import trade.nesty.app.evm.ValidationContext
import trade.nesty.app.evm.Validator
import trade.nesty.app.keys.Base58
import trade.nesty.app.keys.Sol
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.Jito
import trade.nesty.app.sol.Layouts
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolAsset
import trade.nesty.app.sol.SolSend
import trade.nesty.app.sol.SolTokenMeta
import trade.nesty.app.sol.SolTransferContext
import trade.nesty.app.sol.SolValidator
import trade.nesty.app.sol.SolVectors
import trade.nesty.app.sol.Spl
import java.math.BigInteger

/**
 * Sending a coin the wallet holds: the send screen's arithmetic, Solana's rules for a coin send
 * (the extension's transfers.ts), and both validators against the exact transactions the Mover
 * builds. Every wallet, coin and receiver here is MADE UP; nothing reads or reaches a chain.
 */
class CoinSendTest {
    private fun big(v: Long) = BigInteger.valueOf(v)

    // ── the amount box ────────────────────────────────────────────────────────────

    @Test
    fun theAmountBoxTakesAsManyDecimalsAsTheCoinHas() {
        assertEquals("12.5", CoinSend.clean("12.5", 18))
        assertEquals("12.5", CoinSend.clean("12,5", 6))
        assertEquals("1.123456", CoinSend.clean("1.123456", 6))
        assertEquals(".5", CoinSend.clean(".5", 9))
        assertEquals("", CoinSend.clean("", 18))
        assertEquals("7", CoinSend.clean("7", 0))
        assertEquals("1".repeat(20), CoinSend.clean("1".repeat(20), 18))
        // More decimals than the coin has, none at all for a coin without, a second point, signs, exponents.
        assertNull(CoinSend.clean("1.1234567", 6))
        assertNull(CoinSend.clean("1.5", 0))
        assertNull(CoinSend.clean("1.", 0))
        assertNull(CoinSend.clean("1.2.3", 18))
        assertNull(CoinSend.clean("-1", 18))
        assertNull(CoinSend.clean("1e5", 18))
        assertNull(CoinSend.clean("1".repeat(21), 18))
    }

    @Test
    fun whatWasTypedBecomesTheCoinsSmallestUnit() {
        assertEquals(big(1_500_000), CoinSend.unitsOf("1.5", 6))
        assertEquals(big(2), CoinSend.unitsOf("2", 0))
        assertEquals(BigInteger("1000000000123456789012345678"), CoinSend.unitsOf("1000000000.123456789012345678", 18))
        // Nothing to send: empty, a lone point, zero, or less than the smallest unit.
        assertNull(CoinSend.unitsOf("", 6))
        assertNull(CoinSend.unitsOf(".", 6))
        assertNull(CoinSend.unitsOf("0", 6))
        assertNull(CoinSend.unitsOf("0.0000001", 6))
        // And back: what a share fills in is read again as the same amount.
        val held = BigInteger("123456789012345678901")
        assertEquals("123.456789012345678901", CoinSend.typed(held, 18))
        assertEquals(held, CoinSend.unitsOf(CoinSend.typed(held, 18), 18))
        assertEquals("1.5", CoinSend.typed(big(1_500_000), 6))
        assertEquals("0", CoinSend.typed(BigInteger.ZERO, 6))
        assertEquals(1.5, CoinSend.tokens(big(1_500_000), 6), 1e-12)
    }

    @Test
    fun aShareRoundsDownAndAllIsEveryUnit() {
        assertEquals(big(250), CoinSend.share(big(1_000), 2_500))
        assertEquals(big(499), CoinSend.share(big(999), 5_000))
        assertEquals(big(999), CoinSend.share(big(999), 10_000))
        val odd = BigInteger("1000000000000000000001")
        assertEquals(odd, CoinSend.share(odd, 10_000))
    }

    @Test
    fun theFeeIsPaidInTheChainsOwnCoin() {
        assertEquals("Not enough BNB for the fee.", CoinSend.feeBlock(Chain.BSC, native = big(10), fee = big(11)))
        assertNull(CoinSend.feeBlock(Chain.BSC, native = big(11), fee = big(11)))
        assertEquals("Not enough ETH for the fee.", CoinSend.feeBlock(Chain.BASE, native = BigInteger.ZERO, fee = big(1)))
        // Not read yet: nothing to say.
        assertNull(CoinSend.feeBlock(Chain.BSC, native = null, fee = big(11)))
        assertNull(CoinSend.feeBlock(Chain.BSC, native = big(10), fee = null))
        // On Solana, Solana's own rule.
        assertEquals(
            SolSend.coinSendBlock(big(1_000), rent, fee, minBalance),
            CoinSend.feeBlock(Chain.SOLANA, big(1_000), fee, rent, minBalance),
        )
    }

    // ── Solana's rules for a coin send ─────────────────────────────────────────────

    /** Rent of an empty account and of a token account, as the chain has priced them for years. */
    private val minBalance = big(890_880)
    private val rent = big(2_039_280)
    private val fee = SolSend.networkFee(big(100_000))

    @Test
    fun theReceiversAccountForTheCoinIsSizedAndPricedAsTheChainDoes() {
        assertEquals(165, SolSend.tokenAccountSize(SolAddr.TOKEN_PROGRAM))
        assertEquals(165, SolSend.tokenAccountSize(SolAddr.TOKEN_PROGRAM, listOf(Layouts.EXT_PAUSABLE)))
        assertEquals(170, SolSend.tokenAccountSize(SolAddr.TOKEN_2022_PROGRAM))
        assertEquals(170, SolSend.tokenAccountSize(SolAddr.TOKEN_2022_PROGRAM, listOf(Layouts.EXT_TOKEN_METADATA)))
        assertEquals(174, SolSend.tokenAccountSize(SolAddr.TOKEN_2022_PROGRAM, listOf(Layouts.EXT_TOKEN_METADATA, Layouts.EXT_PAUSABLE)))
        assertEquals(rent, SolSend.rentOf(165))
        assertEquals(minBalance, SolSend.rentOf(0))
    }

    @Test
    fun theWalletPaysTheFeeAndTheAccountAndStillExists() {
        // Plenty: what is left is above what a wallet must keep.
        assertNull(SolSend.coinSendBlock(big(10_000_000), rent, fee, minBalance))
        // Exactly the fee and the account: the wallet empties, which is allowed.
        assertNull(SolSend.coinSendBlock(fee + rent, rent, fee, minBalance))
        // The receiver has the account: only the fee.
        assertNull(SolSend.coinSendBlock(fee + minBalance, BigInteger.ZERO, fee, minBalance))
        // A lamport over leaves the wallet below what it must keep; short of it cannot pay.
        val keep = "and a wallet must keep at least 0.00089088 SOL."
        assertEquals("Not enough SOL: the fee and the receiver's account for this coin take 0.00214428 SOL, $keep", SolSend.coinSendBlock(fee + rent + BigInteger.ONE, rent, fee, minBalance))
        assertEquals("Not enough SOL: the fee and the receiver's account for this coin take 0.00214428 SOL, $keep", SolSend.coinSendBlock(fee + rent - BigInteger.ONE, rent, fee, minBalance))
        assertEquals("Not enough SOL: the fee takes 0.000105 SOL, $keep", SolSend.coinSendBlock(fee - BigInteger.ONE, BigInteger.ZERO, fee, minBalance))
    }

    private fun meta(symbol: String, vararg ext: Int) = SolTokenMeta("Made-up coin", symbol, 6, SolAddr.TOKEN_2022_PROGRAM, big(1_000_000_000), ext.toList())

    @Test
    fun coinsThatAPlainTransferCannotMoveAreRefusedAsTheExtensionDoes() {
        assertEquals("KILN cannot be transferred.", SolSend.coinBlock(meta("KILN", Layouts.EXT_NON_TRANSFERABLE)))
        assertEquals("This coin cannot be transferred.", SolSend.coinBlock(meta("", Layouts.EXT_NON_TRANSFERABLE)))
        assertEquals("Nesty cannot send KILN: it runs a program of its own on every transfer.", SolSend.coinBlock(meta("KILN", Layouts.EXT_TRANSFER_HOOK)))
        assertEquals("Nesty cannot send KILN: it takes a fee on every transfer.", SolSend.coinBlock(meta("KILN", Layouts.EXT_TRANSFER_FEE_CONFIG)))
        assertEquals("Nesty cannot send this coin: it takes a fee on every transfer.", SolSend.coinBlock(meta("", Layouts.EXT_TRANSFER_FEE_CONFIG)))
        // Non-transferable first, as the extension checks it.
        assertEquals("KILN cannot be transferred.", SolSend.coinBlock(meta("KILN", Layouts.EXT_TRANSFER_FEE_CONFIG, Layouts.EXT_NON_TRANSFERABLE)))
        assertNull(SolSend.coinBlock(meta("KILN")))
        assertNull(SolSend.coinBlock(meta("KILN", Layouts.EXT_TOKEN_METADATA, Layouts.EXT_PAUSABLE)))
    }

    // ── the Solana validator, on the Mover's transaction ──────────────────────────

    private val wallet = Sol.address(SolVectors.MADE_UP_SEED)
    private val to = SolVectors.OTHER
    private val stranger = SolVectors.STRANGER

    /** A MADE-UP mint: 32 bytes, no coin behind it. */
    private val mint = Base58.encode(ByteArray(32) { 0x4d })
    private val amount = big(1_234_567)
    private val maxPriority = big(100_000)

    private fun asset(program: String = SolAddr.TOKEN_PROGRAM) = SolAsset(mint, program, 6, "KILN")

    private fun ctx(asset: SolAsset? = asset(), recipient: String = to, maxPriorityLamports: BigInteger = maxPriority) =
        SolTransferContext(wallet = wallet, to = recipient, asset = asset, amount = amount, maxPriorityLamports = maxPriorityLamports)

    /** What the Mover builds, inside the compute budget the trader puts around it (80K units at 1 lamport each, 80,000 lamports). */
    private fun send(
        program: String = SolAddr.TOKEN_PROGRAM,
        opens: String = to,
        payer: String = wallet,
        recipient: String = to,
        sent: BigInteger = amount,
        decimals: Int = 6,
    ): List<Instruction> = listOf(
        Spl.setComputeUnitLimit(SolSend.COIN_UNITS),
        Spl.setComputeUnitPrice(big(1_000_000)),
        Spl.createAtaIdempotent(opens, mint, program, payer = payer),
        Spl.transferChecked(wallet, recipient, mint, program, sent, decimals),
    )

    private fun reasons(ixs: List<Instruction>, c: SolTransferContext = ctx()) = SolValidator.validateTransfer(ixs, c).actions.mapNotNull { it.reason }

    @Test
    fun theMoversCoinSendPassesOnBothTokenPrograms() {
        for (program in listOf(SolAddr.TOKEN_PROGRAM, SolAddr.TOKEN_2022_PROGRAM)) {
            val r = SolValidator.validateTransfer(send(program), ctx(asset(program)))
            assertTrue("$program: ${r.actions}", r.ok)
            assertEquals(
                listOf("Compute limit 80000", "Open the recipient's KILN account", "Send 1.234567 KILN to ${to.take(6)}…${to.takeLast(4)}", "Priority fee 0.00008 SOL"),
                r.actions.map { it.label },
            )
        }
        // The receiver's account may be there already: the transfer alone passes too.
        assertTrue(SolValidator.validateTransfer(send().filterNot { it.programId == SolAddr.ATA_PROGRAM }, ctx()).ok)
    }

    @Test
    fun anythingButThatSendIsRefused() {
        val notIt = "Not the transfer you asked for"
        assertEquals(listOf(notIt), reasons(send(sent = amount + BigInteger.ONE)))
        assertEquals(listOf(notIt), reasons(send(decimals = 9)))
        assertEquals(listOf(notIt), reasons(send(recipient = stranger).filterNot { it.programId == SolAddr.ATA_PROGRAM }))
        assertEquals(listOf("Not the recipient's account for this token"), reasons(send(opens = stranger)))
        // A second transfer, even of the same coin to the same receiver.
        assertEquals(listOf("Exactly one transfer per transaction"), reasons(send() + send().last()))
        // Built for one token program, checked for the other.
        assertFalse(SolValidator.validateTransfer(send(SolAddr.TOKEN_PROGRAM), ctx(asset(SolAddr.TOKEN_2022_PROGRAM))).ok)
        // The receiver's account paid by anyone but the wallet: someone else would sign.
        assertTrue("Only your wallet may sign" in reasons(send(payer = stranger)))
        // A SOL send's context does not let a coin move.
        assertEquals(listOf("Not the recipient's account for this token", notIt), reasons(send(), ctx(asset = null)))
        // To the wallet itself, above the priority fee asked, or with any other program.
        assertTrue("This is the wallet you are sending from" in reasons(send(recipient = wallet, opens = wallet), ctx(recipient = wallet)))
        assertEquals(listOf("Above your max network fee"), reasons(send(), ctx(maxPriorityLamports = big(79_999))))
        val other = Instruction(stranger, emptyList(), byteArrayOf(1))
        assertEquals(listOf("Program is not on the allowlist"), reasons(send() + other))
    }

    @Test
    fun aSendTakesNoTip() {
        // Plain sends go untipped through the public RPCs (SolTrader.sendPlain): a tip in one is refused.
        val tip = Jito.tip(wallet, big(50_000), Jito.TIP_ACCOUNTS[0], marked = true)
        assertFalse(SolValidator.validateTransfer(send() + tip, ctx()).ok)
        assertFalse(SolValidator.validateTransfer(listOf(Spl.transfer(wallet, to, amount), tip), ctx(asset = null)).ok)
    }

    // ── the EVM validator, on the Mover's transaction ────────────────────────────

    private val bsc = EvmTestKit.chain(56)
    private val coin = "0x00000000000000000000000000000000000000c0"
    private val payee = "0x0000000000000000000000000000000000c0ffee"

    private fun evmCtx(payee: String? = this.payee, from: String = EvmTestKit.MADE_UP_WALLET) = ValidationContext(
        chain = bsc,
        from = from,
        ownWallets = listOf(EvmTestKit.MADE_UP_WALLET),
        token = coin,
        maxValue = BigInteger.ZERO,
        payee = payee,
        nowS = EvmTestKit.NOW_S,
    )

    private fun transfer(to: String = payee, value: BigInteger = BigInteger.ZERO, token: String = coin) =
        TxDraft(token, Erc20.transfer.encode(to, amount), value)

    @Test
    fun anEvmCoinSendGoesOnlyToTheAddressTyped() {
        val ok = Validator.validateTx(transfer(), evmCtx())
        assertTrue(ok.actions.toString(), ok.ok)
        assertEquals(listOf("Transfer 1234567 to 0x0000…ffee"), ok.actions.map { it.label })
        // The address as typed, checksummed: the same address.
        assertTrue(Validator.validateTx(transfer(), evmCtx(payee = payee.uppercase().replace("0X", "0x"))).ok)
        // Anyone else, or no confirmed address at all (a trade's context).
        assertEquals("Recipient is not one of your wallets", Validator.validateTx(transfer(to = EvmTestKit.STRANGER), evmCtx()).reason)
        assertEquals("Recipient is not one of your wallets", Validator.validateTx(transfer(), evmCtx(payee = null)).reason)
    }

    @Test
    fun anEvmCoinSendCarriesNothingElse() {
        // Native value with it: over the limit of zero, and a token call may carry none.
        val valued = Validator.validateTx(transfer(value = BigInteger.ONE), evmCtx())
        assertFalse(valued.ok)
        assertEquals(listOf("Above your per-transaction limit", "Token calls must not carry native value"), valued.actions.mapNotNull { it.reason })
        // Another coin's contract, an approval instead, or another sender.
        assertEquals("Contract is not on the allowlist", Validator.validateTx(transfer(token = "0x00000000000000000000000000000000000000c1"), evmCtx()).reason)
        val approve = TxDraft(coin, Validator.erc20Approve.encode(payee, amount), BigInteger.ZERO)
        assertEquals("Spender is not Permit2 or an allowlisted launchpad", Validator.validateTx(approve, evmCtx()).reason)
        assertEquals("Sender is not one of your wallets", Validator.validateTx(transfer(), evmCtx(from = EvmTestKit.STRANGER)).reason)
    }
}
