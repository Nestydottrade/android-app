package trade.nesty.app.sol

import trade.nesty.app.keys.Base58
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Legacy Solana transactions and the SPL instructions Nesty builds, ported from the extension's
 * src/background/solana/tx.ts and spl.ts.
 */
data class AccountMeta(val pubkey: String, val isSigner: Boolean, val isWritable: Boolean)

/** `meta(pubkey, isWritable, isSigner)`, in tx.ts's argument order. */
fun meta(pubkey: String, isWritable: Boolean = false, isSigner: Boolean = false) = AccountMeta(pubkey, isSigner, isWritable)

class Instruction(val programId: String, val keys: List<AccountMeta>, val data: ByteArray) {
    override fun equals(other: Any?) = other is Instruction && programId == other.programId && keys == other.keys && data.contentEquals(other.data)
    override fun hashCode() = (programId.hashCode() * 31 + keys.hashCode()) * 31 + data.contentHashCode()
}

/** Legacy message, compiled: what is signed and what the account indexes point at. */
class CompiledMessage(val bytes: ByteArray, val accounts: List<String>, val numSigners: Int)

object SolTx {
    /** The most a transaction may take on the wire (one IPv6 packet minus headers). */
    const val MAX_TX_BYTES = 1232

    fun compactU16(n: Int): ByteArray {
        require(n in 0..0xffff) { "bad length" }
        val out = ByteArrayOutputStream()
        var v = n
        while (true) {
            val b = v and 0x7f
            v = v shr 7
            if (v == 0) {
                out.write(b)
                return out.toByteArray()
            }
            out.write(b or 0x80)
        }
    }

    private class Flags(var signer: Boolean, var writable: Boolean)

    /**
     * Compiles a legacy message. Accounts are ordered as the runtime requires: writable signers
     * (the fee payer first), read-only signers, writable others, read-only others.
     */
    fun compileMessage(payer: String, instructions: List<Instruction>, recentBlockhash: String): CompiledMessage {
        val flags = LinkedHashMap<String, Flags>()
        fun note(key: String, signer: Boolean, writable: Boolean) {
            val f = flags[key]
            if (f != null) {
                f.signer = f.signer || signer
                f.writable = f.writable || writable
            } else {
                flags[key] = Flags(signer, writable)
            }
        }
        note(payer, signer = true, writable = true)
        for (ix in instructions) {
            for (k in ix.keys) note(k.pubkey, k.isSigner, k.isWritable)
            note(ix.programId, signer = false, writable = false)
        }
        fun group(signer: Boolean, writable: Boolean) = flags.filter { it.value.signer == signer && it.value.writable == writable }.keys.toList()
        val writableSigners = group(signer = true, writable = true).filter { it != payer }
        val readonlySigners = group(signer = true, writable = false)
        val readonlyOthers = group(signer = false, writable = false)
        val accounts = listOf(payer) + writableSigners + readonlySigners + group(signer = false, writable = true) + readonlyOthers
        val numSigners = 1 + writableSigners.size + readonlySigners.size
        require(accounts.size <= 256) { "too many accounts" }
        val index = HashMap<String, Int>()
        accounts.forEachIndexed { i, k -> index[k] = i }

        val out = ByteArrayOutputStream()
        out.write(numSigners)
        out.write(readonlySigners.size)
        out.write(readonlyOthers.size)
        out.write(compactU16(accounts.size))
        for (k in accounts) out.write(SolAddr.addressBytes(k))
        val hash = Base58.decode(recentBlockhash)
        require(hash != null && hash.size == 32) { "bad blockhash" }
        out.write(hash)
        out.write(compactU16(instructions.size))
        for (ix in instructions) {
            out.write(index.getValue(ix.programId))
            out.write(compactU16(ix.keys.size))
            for (k in ix.keys) out.write(index.getValue(k.pubkey))
            out.write(compactU16(ix.data.size))
            out.write(ix.data)
        }
        return CompiledMessage(out.toByteArray(), accounts, numSigners)
    }

    /** Wire format: the signatures (zeros where missing), then the message. */
    fun serializeTransaction(message: CompiledMessage, signatures: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(compactU16(message.numSigners))
        for (i in 0 until message.numSigners) {
            val s = signatures.getOrNull(i)
            out.write(if (s != null && s.size == 64) s else ByteArray(64))
        }
        out.write(message.bytes)
        val raw = out.toByteArray()
        require(raw.size <= MAX_TX_BYTES) { "transaction too large" }
        return raw
    }
}

/** The SPL instructions a trade builds around its swap (and the validator accepts). */
object Spl {
    /** The most compute a transaction may ask for. */
    const val MAX_COMPUTE_UNITS = 1_400_000

    const val SYSTEM_TRANSFER = 2
    const val TOKEN_CLOSE_ACCOUNT = 9
    const val TOKEN_SYNC_NATIVE = 17
    const val TOKEN_TRANSFER_CHECKED = 12
    const val ATA_CREATE_IDEMPOTENT = 1
    const val CU_SET_LIMIT = 2
    const val CU_SET_PRICE = 3

    fun setComputeUnitLimit(units: Int): Instruction =
        Instruction(SolAddr.COMPUTE_BUDGET_PROGRAM, emptyList(), concat(bytesOf(CU_SET_LIMIT), u32(units.toLong())))

    fun setComputeUnitPrice(microLamports: BigInteger): Instruction =
        Instruction(SolAddr.COMPUTE_BUDGET_PROGRAM, emptyList(), concat(bytesOf(CU_SET_PRICE), u64(microLamports)))

    fun transfer(from: String, to: String, lamports: BigInteger): Instruction =
        Instruction(SolAddr.SYSTEM_PROGRAM, listOf(meta(from, true, true), meta(to, true)), concat(u32(SYSTEM_TRANSFER.toLong()), u64(lamports)))

    /** Creates `owner`'s token account for `mint` unless it exists (paid by `payer`). */
    fun createAtaIdempotent(owner: String, mint: String, tokenProgram: String, payer: String = owner): Instruction =
        Instruction(
            SolAddr.ATA_PROGRAM,
            listOf(
                meta(payer, true, true),
                meta(SolAddr.associatedTokenAddress(owner, mint, tokenProgram), true),
                meta(owner),
                meta(mint),
                meta(SolAddr.SYSTEM_PROGRAM),
                meta(tokenProgram),
            ),
            bytesOf(ATA_CREATE_IDEMPOTENT),
        )

    fun syncNative(account: String): Instruction =
        Instruction(SolAddr.TOKEN_PROGRAM, listOf(meta(account, true)), bytesOf(TOKEN_SYNC_NATIVE))

    /** Closes `owner`'s token account, sending its rent (and any wrapped SOL) back to `owner`. */
    fun closeAccount(account: String, owner: String): Instruction =
        Instruction(SolAddr.TOKEN_PROGRAM, listOf(meta(account, true), meta(owner, true), meta(owner, false, true)), bytesOf(TOKEN_CLOSE_ACCOUNT))
}
