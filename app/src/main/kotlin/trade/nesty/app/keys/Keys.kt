package trade.nesty.app.keys

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(key, "HmacSHA512"))
    return mac.doFinal(data)
}

fun keccak256(data: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(data, 0, data.size)
    return ByteArray(32).also { digest.doFinal(it, 0) }
}

/** EVM keys: secp256k1, addresses as EIP-55 writes them. */
object Evm {
    private val curve = CustomNamedCurves.getByName("secp256k1")
    internal val N: BigInteger = curve.n
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    /** 33 bytes compressed, or 65 bytes starting with 0x04. */
    fun publicKey(privateKey: ByteArray, compressed: Boolean): ByteArray {
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < N) { "Not a key" }
        return FixedPointCombMultiplier().multiply(curve.g, d).normalize().getEncoded(compressed)
    }

    fun address(privateKey: ByteArray): String {
        val pub = publicKey(privateKey, compressed = false)
        val hash = keccak256(pub.copyOfRange(1, 65))
        return checksum(Hex.encode(hash.copyOfRange(12, 32)))
    }

    /** `0x` and 40 hex digits in EIP-55's mixed case. */
    fun checksum(address: String): String {
        val lower = address.removePrefix("0x").removePrefix("0X").lowercase()
        require(lower.length == 40) { "Not an address" }
        val hash = keccak256(lower.toByteArray(Charsets.US_ASCII))
        val out = StringBuilder("0x")
        for ((i, c) in lower.withIndex()) {
            val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0xf
            out.append(if (c in 'a'..'f' && nibble >= 8) c.uppercaseChar() else c)
        }
        return out.toString()
    }

    fun isAddress(s: String): Boolean = ADDRESS.matches(s)

    /**
     * A private key as wallets export it: 64 hex digits, with or without 0x. Returns its 32
     * bytes, or null. The caller wipes them.
     */
    fun parsePrivateKey(input: String): ByteArray? {
        val s = input.trim().removePrefix("0x").removePrefix("0X")
        if (s.length != 64) return null
        val bytes = Hex.decode(s) ?: return null
        val d = BigInteger(1, bytes)
        if (d.signum() <= 0 || d >= N) {
            bytes.wipe()
            return null
        }
        return bytes
    }
}

/** Solana keys: ed25519, addresses in Base58. */
object Sol {
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "A seed is 32 bytes" }
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun address(seed: ByteArray): String = Base58.encode(publicKey(seed))

    /**
     * The ed25519 signature of a transaction's message. The key must be the address's, the fee
     * payer's: anything else would be a vault fault, and nothing is signed.
     */
    fun sign(message: ByteArray, seed: ByteArray, address: String): ByteArray {
        require(seed.size == 32) { "A seed is 32 bytes" }
        val key = Ed25519PrivateKeyParameters(seed, 0)
        if (Base58.encode(key.generatePublicKey().encoded) != address) throw IllegalStateException("Key mismatch")
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun isAddress(s: String): Boolean = s.length in 32..44 && Base58.decode(s)?.size == 32

    /** The key as Phantom, Solflare and Backpack export and import it: Base58 of seed and public key. */
    fun secretKey(seed: ByteArray): String {
        val pair = seed + publicKey(seed)
        return Base58.encode(pair).also { pair.wipe() }
    }

    /**
     * A secret key as wallets export it: Base58 of the 64-byte pair, or the command line's
     * keypair file, a JSON array of those 64 bytes. Returns the 32-byte seed, or null. The second
     * half must be the seed's public key: that catches a mistyped key, and a pasted address
     * never becomes a key (src/background/solana/keys.ts). The caller wipes the seed.
     */
    fun parseSecretKey(input: String): ByteArray? {
        val s = input.trim()
        val full: ByteArray = if (s.startsWith("[")) {
            val parts = s.removePrefix("[").removeSuffix("]").split(",").map { it.trim() }
            if (!s.endsWith("]") || parts.size != 64) return null
            val bytes = ByteArray(64)
            for ((i, p) in parts.withIndex()) {
                val n = p.toIntOrNull()
                if (n == null || n !in 0..255 || p.any { !it.isDigit() }) {
                    bytes.wipe()
                    return null
                }
                bytes[i] = n.toByte()
            }
            bytes
        } else {
            Base58.decode(s) ?: return null
        }
        if (full.size != 64) {
            full.wipe()
            return null
        }
        val seed = full.copyOfRange(0, 32)
        val matches = MessageDigest.isEqual(publicKey(seed), full.copyOfRange(32, 64))
        full.wipe()
        if (!matches) {
            seed.wipe()
            return null
        }
        return seed
    }
}

/**
 * The two key trees of a recovery phrase's seed, on the paths the extension uses, so a phrase
 * shows the same wallets in both: EVM m/44'/60'/0'/0/i (BIP-32), Solana m/44'/501'/i'/0'
 * (SLIP-0010, every step hardened; Phantom's and Solflare's path).
 */
object Hd {
    private const val HARDENED = 0x80000000L

    private fun ser32(i: Long) = byteArrayOf((i shr 24).toByte(), (i shr 16).toByte(), (i shr 8).toByte(), i.toByte())

    private fun to32(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        val out = ByteArray(32)
        val from = maxOf(0, raw.size - 32)
        System.arraycopy(raw, from, out, 32 - (raw.size - from), raw.size - from)
        raw.wipe()
        return out
    }

    /** The EVM private key of account `index`. The caller wipes it. */
    fun evmKey(seed: ByteArray, index: Int = 0): ByteArray {
        require(index >= 0) { "Bad index" }
        var i = hmacSha512("Bitcoin seed".toByteArray(Charsets.US_ASCII), seed)
        var key = i.copyOfRange(0, 32)
        var chain = i.copyOfRange(32, 64)
        i.wipe()
        for (step in longArrayOf(44 + HARDENED, 60 + HARDENED, HARDENED, 0, index.toLong())) {
            val data = if (step >= HARDENED) byteArrayOf(0) + key + ser32(step) else Evm.publicKey(key, compressed = true) + ser32(step)
            i = hmacSha512(chain, data)
            data.wipe()
            val left = BigInteger(1, i.copyOfRange(0, 32))
            val child = left.add(BigInteger(1, key)).mod(Evm.N)
            check(left < Evm.N && child.signum() != 0) { "Unusable key: one in 2^127, never seen" }
            key.wipe()
            chain.wipe()
            key = to32(child)
            chain = i.copyOfRange(32, 64)
            i.wipe()
        }
        chain.wipe()
        return key
    }

    /** The Solana seed of account `index`. The caller wipes it. */
    fun solSeed(seed: ByteArray, index: Int = 0): ByteArray {
        require(index >= 0) { "Bad index" }
        var i = hmacSha512("ed25519 seed".toByteArray(Charsets.US_ASCII), seed)
        var key = i.copyOfRange(0, 32)
        var chain = i.copyOfRange(32, 64)
        i.wipe()
        for (step in longArrayOf(44, 501, index.toLong(), 0)) {
            val data = byteArrayOf(0) + key + ser32(step + HARDENED)
            i = hmacSha512(chain, data)
            data.wipe()
            key.wipe()
            chain.wipe()
            key = i.copyOfRange(0, 32)
            chain = i.copyOfRange(32, 64)
            i.wipe()
        }
        chain.wipe()
        return key
    }
}
