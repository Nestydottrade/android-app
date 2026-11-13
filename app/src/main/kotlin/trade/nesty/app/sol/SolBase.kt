package trade.nesty.app.sol

import trade.nesty.app.keys.Base58
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Solana's addresses, program addresses and account layouts, ported from the extension's
 * src/background/solana/address.ts and layouts.ts. Addresses are Base58 strings, as there.
 */
object SolAddr {
    const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111"
    const val METADATA_PROGRAM = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"
    const val SYSVAR_CLOCK = "SysvarC1ock11111111111111111111111111111111"
    const val SYSVAR_INSTRUCTIONS = "Sysvar1nstructions1111111111111111111111111"

    /** Wrapped SOL: PumpSwap pools are quoted in it, and pump.fun's trades name it as the quote. */
    const val WSOL_MINT = "So11111111111111111111111111111111111111112"
    const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USD1_MINT = "USD1ttGY1N17NEEHLmELoaybftRBUSErhqYiQzvEmuB"

    /** Dollar stablecoins pump.fun coins are paired with: priced at $1. */
    val STABLE_MINTS: Set<String> = setOf(
        USDC_MINT,
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
        USD1_MINT,
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", // PYUSD (Token-2022)
    )

    /** The all-zero key: `Pubkey::default()` on chain. */
    const val DEFAULT_KEY = SYSTEM_PROGRAM

    /** The coin of a pool's two mints: the one that is not wrapped SOL, else not a stablecoin. */
    fun coinOfPair(a: String, b: String): String = when {
        a == WSOL_MINT -> b
        b == WSOL_MINT -> a
        a in STABLE_MINTS -> b
        else -> a
    }

    fun isTokenProgram(program: String?): Boolean = program == TOKEN_PROGRAM || program == TOKEN_2022_PROGRAM

    fun addressBytes(address: String): ByteArray {
        val b = Base58.decode(address)
        require(b != null && b.size == 32) { "bad address" }
        return b
    }

    fun toAddress(bytes: ByteArray): String {
        require(bytes.size == 32) { "bad address" }
        return Base58.encode(bytes)
    }

    fun isAddress(s: String): Boolean = s.length in 32..44 && Base58.decode(s)?.size == 32

    // ── program addresses ───────────────────────────────────────────────────────────

    private val P: BigInteger = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
    private val HALF: BigInteger = (P - BigInteger.ONE).shiftRight(1)

    /**
     * Whether 32 bytes decode to a point on ed25519. Lenient decoding (zip215), like the
     * runtime's: curve25519-dalek accepts non-canonical encodings. A y decodes when (y²−1)/(d·y²+1)
     * has a square root (Euler's criterion), whatever the sign bit says.
     */
    fun isOnCurve(bytes: ByteArray): Boolean {
        if (bytes.size != 32) return false
        val le = bytes.reversedArray()
        le[0] = (le[0].toInt() and 0x7f).toByte()
        val y = BigInteger(1, le)
        val y2 = y.multiply(y).mod(P)
        val u = (y2 - BigInteger.ONE).mod(P)
        if (u.signum() == 0) return true
        val v = (D.multiply(y2) + BigInteger.ONE).mod(P)
        val w = u.multiply(v.modInverse(P)).mod(P)
        return w.modPow(HALF, P) == BigInteger.ONE
    }

    private val PDA_MARKER = "ProgramDerivedAddress".toByteArray(Charsets.UTF_8)
    private val pdaCache = ConcurrentHashMap<String, Pair<String, Int>>()

    /** Seed bytes of a text, as `Buffer.from("seed")`. */
    fun seed(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** `Pubkey::find_program_address`: the first bump from 255 down that lands off the curve. */
    fun findProgramAddress(seeds: List<ByteArray>, program: String): String = findProgramAddressWithBump(seeds, program).first

    /** A program address of seeds given as addresses (Base58) or raw bytes. */
    fun pda(program: String, vararg seeds: Any): String = findProgramAddress(seeds.map(::seedBytes), program)

    fun pdaWithBump(program: String, vararg seeds: Any): Pair<String, Int> = findProgramAddressWithBump(seeds.map(::seedBytes), program)

    private fun seedBytes(s: Any): ByteArray = when (s) {
        is String -> addressBytes(s)
        is ByteArray -> s
        else -> throw IllegalArgumentException("bad seed")
    }

    fun findProgramAddressWithBump(seeds: List<ByteArray>, program: String): Pair<String, Int> {
        val key = program + "|" + seeds.joinToString("|") { Base58.encode(it) }
        pdaCache[key]?.let { return it }
        require(seeds.size <= 15 && seeds.all { it.size <= 32 }) { "bad seeds" }
        val programBytes = addressBytes(program)
        for (bump in 255 downTo 0) {
            val h = MessageDigest.getInstance("SHA-256")
            for (s in seeds) h.update(s)
            h.update(byteArrayOf(bump.toByte()))
            h.update(programBytes)
            h.update(PDA_MARKER)
            val out = h.digest()
            if (!isOnCurve(out)) {
                val found = toAddress(out) to bump
                // Bounded: PDAs used by a few hundred trades at most.
                if (pdaCache.size > 2_000) pdaCache.clear()
                pdaCache[key] = found
                return found
            }
        }
        throw IllegalStateException("no program address")
    }

    /** The associated token account of `owner` for `mint` under `tokenProgram`. */
    fun associatedTokenAddress(owner: String, mint: String, tokenProgram: String = TOKEN_PROGRAM): String =
        pda(ATA_PROGRAM, owner, tokenProgram, mint)

    /** The wallet's own wrapped SOL account. */
    fun wsolAccount(owner: String): String = associatedTokenAddress(owner, WSOL_MINT, TOKEN_PROGRAM)

    /** The Metaplex metadata account of a mint. */
    fun metadataAddress(mint: String): String = pda(METADATA_PROGRAM, seed("metadata"), METADATA_PROGRAM, mint)
}

// ── bytes ───────────────────────────────────────────────────────────────────────────

val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

/** Little-endian u64 for instruction data. */
fun u64(n: BigInteger): ByteArray {
    require(n.signum() >= 0 && n.bitLength() <= 64) { "u64 out of range" }
    val out = ByteArray(8)
    var v = n
    for (i in 0 until 8) {
        out[i] = v.toByte()
        v = v.shiftRight(8)
    }
    return out
}

fun u64(n: Long): ByteArray = u64(BigInteger.valueOf(n))

fun u32(n: Long): ByteArray = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

fun u16(n: Int): ByteArray = byteArrayOf(n.toByte(), (n shr 8).toByte())

fun bytesOf(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) {
        System.arraycopy(p, 0, out, o, p.size)
        o += p.size
    }
    return out
}

/** `bytes` little-endian bytes of `data` at `at` (zero past the end). */
fun le(data: ByteArray, at: Int, bytes: Int): BigInteger {
    var v = BigInteger.ZERO
    for (i in bytes - 1 downTo 0) v = v.shiftLeft(8).or(BigInteger.valueOf((data.getOrNull(at + i)?.toInt() ?: 0).toLong() and 0xff))
    return v
}

fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
    data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

val Long.big: BigInteger get() = BigInteger.valueOf(this)
val Int.big: BigInteger get() = BigInteger.valueOf(this.toLong())

/**
 * Little-endian reader over account data. Programs append fields over time and older accounts
 * stay shorter: past the end, fields read as zero / false / the default key.
 */
class Reader(private val b: ByteArray, offset: Int = 0) {
    var offset: Int = offset
        private set

    private fun take(n: Int): ByteArray? {
        val at = offset
        offset += n
        return if (at >= 0 && at + n <= b.size) b.copyOfRange(at, at + n) else null
    }

    fun skip(n: Int): Reader {
        offset += n
        return this
    }

    fun u8(): Int = take(1)?.let { it[0].toInt() and 0xff } ?: 0

    fun bool(): Boolean = u8() != 0

    fun u16(): Int = take(2)?.let { (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8) } ?: 0

    fun u32(): Long = take(4)?.let { le(it, 0, 4).toLong() } ?: 0

    fun i32(): Int = u32().toInt()

    fun u64(): BigInteger = take(8)?.let { le(it, 0, 8) } ?: BigInteger.ZERO

    fun i64(): Long = u64().toLong()

    fun u128(): BigInteger {
        val lo = u64()
        val hi = u64()
        return hi.shiftLeft(64).or(lo)
    }

    fun i128(): BigInteger {
        val v = u128()
        return if (v >= BigInteger.ONE.shiftLeft(127)) v - BigInteger.ONE.shiftLeft(128) else v
    }

    fun pubkey(): String = SolAddr.toAddress(take(32) ?: ByteArray(32))

    /** Borsh string: u32 length, then UTF-8. Fixed-size fields are padded with NULs. */
    fun string(max: Int = 512): String {
        val len = u32()
        require(len <= max) { "string too long" }
        val s = take(len.toInt()) ?: return ""
        return String(s, Charsets.UTF_8).trimEnd('\u0000')
    }
}

/** Anchor accounts start with an 8-byte discriminator. */
fun hasDiscriminator(data: ByteArray, disc: ByteArray): Boolean =
    data.size >= 8 && disc.indices.all { data[it] == disc[it] }

// ── SPL Token ───────────────────────────────────────────────────────────────────────

data class MintInfo(
    val supply: BigInteger,
    val decimals: Int,
    val initialized: Boolean,
    /** Token-2022 extension types present (empty for SPL Token mints). */
    val extensions: List<Int>,
    /** From the Token-2022 metadata extension, when the mint carries its own. */
    val name: String? = null,
    val symbol: String? = null,
    val uri: String? = null,
    /** Token-2022 transfer fee: basis points of every transfer, the newest one set. */
    val transferFeeBps: Int? = null,
    /** The program its transfer hook runs, when one is set (a hook without one runs nothing). */
    val transferHookProgram: String? = null,
)

object Layouts {
    private const val MINT_SIZE = 82
    const val ACCOUNT_SIZE = 165

    /** Token-2022 extension types. */
    const val EXT_TRANSFER_FEE_CONFIG = 1
    const val EXT_TRANSFER_HOOK = 14
    const val EXT_TOKEN_METADATA = 19

    fun decodeMint(data: ByteArray): MintInfo? {
        if (data.size < MINT_SIZE) return null
        val r = Reader(data, 36)
        val supply = r.u64()
        val decimals = r.u8()
        val initialized = r.bool()
        val extensions = ArrayList<Int>()
        var name: String? = null
        var symbol: String? = null
        var uri: String? = null
        var feeBps: Int? = null
        var hook: String? = null
        // Token-2022: account type (1 = mint) at 165, then type-length-value extensions.
        if (data.size > ACCOUNT_SIZE && data[ACCOUNT_SIZE].toInt() == 1) {
            var o = ACCOUNT_SIZE + 1
            fun u16(at: Int) = (data.getOrNull(at)?.toInt()?.and(0xff) ?: 0) or ((data.getOrNull(at + 1)?.toInt()?.and(0xff) ?: 0) shl 8)
            while (o + 4 <= data.size) {
                val type = u16(o)
                val len = u16(o + 2)
                if (type == 0 && len == 0) break
                val value = data.copyOfRange(o + 4, minOf(data.size, o + 4 + len))
                extensions.add(type)
                // Two authorities, the withheld amount, then the older and newer fee (epoch, maximum,
                // basis points): the newer one's basis points are the last two bytes.
                if (type == EXT_TRANSFER_FEE_CONFIG && len >= 108) {
                    feeBps = (value.getOrNull(106)?.toInt()?.and(0xff) ?: 0) or ((value.getOrNull(107)?.toInt()?.and(0xff) ?: 0) shl 8)
                }
                // Its authority, then the program (all zeros for none).
                if (type == EXT_TRANSFER_HOOK && len >= 64 && (32 until 64).any { (value.getOrNull(it)?.toInt() ?: 0) != 0 }) {
                    hook = Reader(value, 32).pubkey()
                }
                if (type == EXT_TOKEN_METADATA) {
                    try {
                        val m = Reader(value, 64)
                        name = m.string(256)
                        symbol = m.string(64)
                        uri = m.string(512)
                    } catch (_: IllegalArgumentException) {
                        // Unreadable metadata: the name falls back to the Metaplex account or a default.
                    }
                }
                o += 4 + len
            }
        }
        return MintInfo(supply, decimals, initialized, extensions, name, symbol, uri, feeBps, hook)
    }

    data class TokenAccount(val mint: String, val owner: String, val amount: BigInteger)

    fun decodeTokenAccount(data: ByteArray): TokenAccount? {
        if (data.size < ACCOUNT_SIZE) return null
        val r = Reader(data)
        return TokenAccount(r.pubkey(), r.pubkey(), r.u64())
    }

    /** The chain's clock, from the Clock sysvar: slot and Unix time. */
    data class ChainClock(val slot: BigInteger, val unixTime: BigInteger)

    fun decodeClock(data: ByteArray): ChainClock? {
        if (data.size < 40) return null
        return ChainClock(Reader(data).u64(), Reader(data, 32).u64())
    }

    data class Metaplex(val name: String, val symbol: String, val uri: String)

    /** Name, symbol and metadata URI from a Metaplex token metadata account. */
    fun decodeMetaplexMetadata(data: ByteArray): Metaplex? = try {
        val r = Reader(data, 1 + 32 + 32)
        val name = r.string(64)
        val symbol = r.string(32)
        val uri = try {
            r.string(256)
        } catch (_: IllegalArgumentException) {
            // The name still counts without it.
            ""
        }
        Metaplex(name, symbol, uri)
    } catch (_: IllegalArgumentException) {
        null
    }
}
