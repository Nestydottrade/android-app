package trade.nesty.app.sol

import trade.nesty.app.evm.formatUnits
import trade.nesty.app.keys.Base58
import java.math.BigInteger

/** What an address is on Solana, from its account (solana/transfers.ts recipientKind). */
enum class SolRecipientKind { UNUSED, PROGRAM_ACCOUNT, PROGRAM, WALLET, TOKEN_ACCOUNT, MINT }

/**
 * The rules of moving SOL out of a wallet, as the extension's Send and bridge apply them
 * (src/background/solana/transfers.ts): Solana's rent rule, and the addresses funds are lost at.
 */
object SolSend {
    /** The base fee of a transaction with one signature. */
    val BASE_FEE: BigInteger = BigInteger.valueOf(5_000)

    /** Compute units a SOL transfer asks for before it is simulated. */
    const val SOL_UNITS = 1_000

    /** Kinds funds are lost at: Nesty refuses to send to them. */
    val LOSING = setOf(SolRecipientKind.PROGRAM, SolRecipientKind.MINT, SolRecipientKind.TOKEN_ACCOUNT)

    const val NOT_A_WALLET = "This address is not a wallet: funds sent there are lost."

    private fun sol(lamports: BigInteger) = formatUnits(lamports, 9)

    /** What one of these transactions costs: the base fee and the priority fee, which the engine prices exactly. */
    fun networkFee(priorityLamports: BigInteger): BigInteger = BASE_FEE + priorityLamports

    /**
     * Whether a wallet holding `lamports` can send `value` of its SOL: null when it can, or why not.
     * Solana takes the fee first and checks the balance then, and again after the transfer: each
     * time it must be zero or at least `minBalance`, the rent exemption.
     */
    fun sendBlock(lamports: BigInteger, value: BigInteger, fee: BigInteger, minBalance: BigInteger): String? {
        val left = lamports - value - fee
        if (left.signum() < 0) return "Amount plus fee is more than the balance."
        if (left.signum() > 0 && left < minBalance) return "Send everything, or leave at least ${sol(minBalance)} SOL: Solana closes a wallet below that."
        // Emptying it: what the fee leaves must still be allowed, for the transfer to take.
        if (left.signum() == 0 && value < minBalance) return "Too little to empty this wallet: that takes at least ${sol(minBalance + fee)} SOL."
        return null
    }

    /** Why a plain SOL transfer of `value` to `to` (its account `acc`) cannot be made, or null. */
    fun newAddressBlock(acc: SolAccount?, value: BigInteger, minBalance: BigInteger): String? =
        if (acc == null && value < minBalance) "A new address needs at least ${sol(minBalance)} SOL to exist." else null

    /** Why a bridge may not pay `minOut` lamports to an address (its account `acc`), or null: a new wallet must be paid its rent. */
    fun payoutBlock(acc: SolAccount?, minOut: BigInteger, minBalance: BigInteger): String? =
        if (acc != null || minOut >= minBalance) null else "A new Solana wallet needs at least ${sol(minBalance)} SOL to exist: bridge a little more."

    /** What an address is on chain, from its account (null when it holds nothing). */
    fun recipientKind(to: String, acc: SolAccount?): SolRecipientKind {
        // An address off the curve has no private key: only a program can move what it holds.
        if (acc == null) {
            val bytes = Base58.decode(to)
            return if (bytes != null && bytes.size == 32 && SolAddr.isOnCurve(bytes)) SolRecipientKind.UNUSED else SolRecipientKind.PROGRAM_ACCOUNT
        }
        if (acc.executable) return SolRecipientKind.PROGRAM
        if (acc.owner == SolAddr.SYSTEM_PROGRAM) return SolRecipientKind.WALLET
        if (SolAddr.isTokenProgram(acc.owner)) {
            // A token account is 165 bytes; under Token-2022 longer, with account type 2 at 165.
            val d = acc.data
            return if (d.size == 165 || (d.size > 165 && d[165].toInt() == 2)) SolRecipientKind.TOKEN_ACCOUNT else SolRecipientKind.MINT
        }
        return SolRecipientKind.PROGRAM_ACCOUNT
    }

    /** Why an outside address may not be paid at all, or null when it may. */
    fun recipientBlock(to: String, acc: SolAccount?): String? = if (recipientKind(to, acc) in LOSING) NOT_A_WALLET else null
}
