package trade.nesty.app.keys

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * Recovery phrases (BIP-39, English). The rules are the extension's (src/background/vault/vault.ts):
 * 12 or 24 words, no extra passphrase, so a phrase gives the same wallets in both.
 */
object Bip39 {
    /** The 2048 words, in order. */
    val words: List<String> by lazy {
        val stream = checkNotNull(Bip39::class.java.getResourceAsStream("/bip39/english.txt")) { "No wordlist" }
        stream.bufferedReader(Charsets.UTF_8).use { r -> r.readLines().filter { it.isNotBlank() } }.also { check(it.size == 2048) { "Bad wordlist" } }
    }
    private val index: Map<String, Int> by lazy { words.withIndex().associate { it.value to it.index } }

    /** Lower case, one space between words. */
    fun normalize(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKD).trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun wordCount(input: String): Int = normalize(input).let { if (it.isEmpty()) 0 else it.split(" ").size }

    /** 12 or 24 words of the list whose checksum holds. */
    fun isValid(input: String): Boolean {
        val list = normalize(input).split(" ")
        if (list.size != 12 && list.size != 24) return false
        val bits = BooleanArray(list.size * 11)
        for ((w, word) in list.withIndex()) {
            val n = index[word] ?: return false
            for (b in 0 until 11) bits[w * 11 + b] = (n shr (10 - b)) and 1 == 1
        }
        val checksumBits = bits.size / 33
        val entropy = ByteArray((bits.size - checksumBits) / 8)
        for (i in entropy.indices) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + b]) 1 else 0)
            entropy[i] = v.toByte()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        entropy.wipe()
        for (b in 0 until checksumBits) {
            val want = (hash[b / 8].toInt() shr (7 - b % 8)) and 1 == 1
            if (bits[bits.size - checksumBits + b] != want) return false
        }
        return true
    }

    /** The phrase of 16 bytes (12 words) or 32 bytes (24 words) of entropy. */
    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size == 16 || entropy.size == 32) { "Entropy is 16 or 32 bytes" }
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4
        val bits = BooleanArray(entropy.size * 8 + checksumBits)
        for (i in 0 until entropy.size * 8) bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        for (b in 0 until checksumBits) bits[entropy.size * 8 + b] = (hash[b / 8].toInt() shr (7 - b % 8)) and 1 == 1
        val out = ArrayList<String>(bits.size / 11)
        for (w in 0 until bits.size / 11) {
            var n = 0
            for (b in 0 until 11) n = (n shl 1) or (if (bits[w * 11 + b]) 1 else 0)
            out += words[n]
        }
        bits.fill(false)
        return out.joinToString(" ")
    }

    /** A new 12-word phrase from the system's secure random source. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val entropy = ByteArray(16)
        random.nextBytes(entropy)
        return fromEntropy(entropy).also { entropy.wipe() }
    }

    /** The 64-byte seed both key trees start from. The caller wipes it. */
    fun toSeed(phrase: String): ByteArray {
        val password = normalize(phrase).toByteArray(Charsets.UTF_8)
        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(password, "mnemonic".toByteArray(Charsets.UTF_8), 2048)
        val seed = (generator.generateDerivedParameters(512) as KeyParameter).key
        password.wipe()
        return seed
    }
}
