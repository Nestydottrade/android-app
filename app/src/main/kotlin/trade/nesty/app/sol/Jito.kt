package trade.nesty.app.sol

import java.math.BigInteger

/**
 * The private way a Solana trade goes out: as a bundle of its own through Jito's block engines
 * (sendTransaction with bundleOnly), never into a public queue, and landed only whole. Its
 * transaction carries a tip to one of the engine's tip accounts, and, where it fits, a marker
 * account that makes the engine refuse any bundle that puts it after another transaction, as a
 * sandwich would. The marker works at the block engine only; a validator running its own queue
 * is out of its reach.
 */
object Jito {
    /** The block engine's tip accounts (getTipAccounts). */
    val TIP_ACCOUNTS = listOf(
        "96gYZGLnJYVFmbjzopPSU6QiEV5fGqZNyN9nmNhvrZU5",
        "HFqU5x63VTqvQss8hp11i4wVV8bD44PvwucfZ2bU7gRe",
        "Cw8CFyM9FkoMi7K7Crf6HNQqf4uEMzpKw6QNghXLvLkY",
        "ADaUMid9yfUytqMBgopwjb2DTLSokTSzL1zt6iGPaS49",
        "DfXygSm4jCyNCybVYYK6DwvWqjKee8pbDmJGcLWNDXjh",
        "ADuUkR4vqLUMWXxW9gh6D6L8pMSawimctcNZ5pGwDcEt",
        "DttWaMuVvTiduZRnguLF7jNxTgiMBZ1hyAumKUiL2KRL",
        "3AVi9Tg9Uo68tJfuvoKvqKNWKkC5wPdSSdeBnizKZ6jT",
    )

    /** The engine's own example of the marker: any key starting "jitodontfront" works, read-only. */
    const val DONT_FRONT = "jitodontfront111111111111111111111111111111"

    /** The block engine refuses a bundle that tips less. */
    val MIN_TIP: BigInteger = BigInteger.valueOf(1_000)

    /**
     * Every region's block engine: each takes a request a second from one address, so a send goes
     * to all of them and the first that takes it answers.
     */
    val ENGINES: List<String> = listOf("", "amsterdam.", "dublin.", "frankfurt.", "london.", "ny.", "slc.", "singapore.", "tokyo.")
        .map { "https://${it}mainnet.block-engine.jito.wtf/api/v1/transactions?bundleOnly=true" }

    /** A tip of `lamports` from `wallet` to `account`, one of [TIP_ACCOUNTS]; with the marker when `marked`. */
    fun tip(wallet: String, lamports: BigInteger, account: String, marked: Boolean): Instruction {
        val base = Spl.transfer(wallet, account, lamports)
        return if (marked) Instruction(base.programId, base.keys + AccountMeta(DONT_FRONT, isSigner = false, isWritable = false), base.data) else base
    }

    /** What `ix` tips from `wallet`, when it is exactly a tip [tip] builds; null for anything else. */
    fun tipOf(ix: Instruction, wallet: String): BigInteger? {
        if (ix.programId != SolAddr.SYSTEM_PROGRAM) return null
        val d = ix.data
        if (d.size != 12 || le(d, 0, 4) != BigInteger.valueOf(Spl.SYSTEM_TRANSFER.toLong())) return null
        val to = ix.keys.getOrNull(1)?.pubkey?.takeIf { it in TIP_ACCOUNTS } ?: return null
        val lamports = le(d, 4, 8)
        val plain = tip(wallet, lamports, to, marked = false)
        val marked = tip(wallet, lamports, to, marked = true)
        return if (ix.keys == plain.keys || ix.keys == marked.keys) lamports else null
    }
}
