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

    /** And a coin's transfer, with the receiver's account for it opened when missing. */
    const val COIN_UNITS = 80_000

    /**
     * The size of the token account the associated token program opens: 165 bytes; under
     * Token-2022 also the account type and an ImmutableOwner entry (170), and the entries some mint
     * extensions require of every account (of the coins Nesty sends, only Pausable's).
     */
    fun tokenAccountSize(tokenProgram: String, extensions: List<Int> = emptyList()): Int =
        if (tokenProgram != SolAddr.TOKEN_2022_PROGRAM) 165 else 170 + if (Layouts.EXT_PAUSABLE in extensions) 4 else 0

    /** The rent exemption of an account of `size` bytes, as the chain has priced it for years: until it is read. */
    fun rentOf(size: Int): BigInteger = BigInteger.valueOf((128L + size) * 6_960)

    /** Why a coin cannot be sent at all, from its mint; null when it can (transfers.ts sendBlock). */
    fun coinBlock(meta: SolTokenMeta): String? {
        val ext = meta.extensions
        val name = meta.symbol.ifBlank { "this coin" }
        return when {
            Layouts.EXT_NON_TRANSFERABLE in ext -> "${meta.symbol.ifBlank { "This coin" }} cannot be transferred."
            Layouts.EXT_TRANSFER_HOOK in ext -> "Nesty cannot send $name: it runs a program of its own on every transfer."
            Layouts.EXT_TRANSFER_FEE_CONFIG in ext -> "Nesty cannot send $name: it takes a fee on every transfer."
            else -> null
        }
    }

    /**
     * Whether a wallet holding `lamports` can pay a coin send: its fee, and `rent` for the
     * receiver's new account for the coin (zero when they have one). Null when it can. The SOL left
     * must still be zero or at least `minBalance`, for the wallet to exist.
     */
    fun coinSendBlock(lamports: BigInteger, rent: BigInteger, fee: BigInteger, minBalance: BigInteger): String? {
        val left = lamports - fee - rent
        if (left.signum() >= 0 && (left.signum() == 0 || left >= minBalance)) return null
        val what = if (rent.signum() > 0) "the fee and the receiver's account for this coin take" else "the fee takes"
        return "Not enough SOL: $what ${sol(fee + rent)} SOL, and a wallet must keep at least ${sol(minBalance)} SOL."
    }

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
