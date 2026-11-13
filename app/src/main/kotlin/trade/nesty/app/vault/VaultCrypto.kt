package trade.nesty.app.vault

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The shortest password a new vault accepts: the extension's rule (src/shared/password.ts). */
const val MIN_PASSWORD_LENGTH = 10

enum class PasswordNeed { LENGTH, UPPER, LOWER, DIGIT }

/**
 * What a new password still lacks, in the order the form names it. Only making a vault checks
 * this; unlocking and exporting take the password it has.
 */
fun passwordNeeds(password: String): List<PasswordNeed> {
    val needs = ArrayList<PasswordNeed>()
    if (password.codePointCount(0, password.length) < MIN_PASSWORD_LENGTH) needs += PasswordNeed.LENGTH
    val points = password.codePoints().toArray()
    if (points.none { Character.isUpperCase(it) }) needs += PasswordNeed.UPPER
    if (points.none { Character.isLowerCase(it) }) needs += PasswordNeed.LOWER
    if (points.none { Character.isDigit(it) || Character.getType(it) == Character.LETTER_NUMBER.toInt() || Character.getType(it) == Character.OTHER_NUMBER.toInt() }) needs += PasswordNeed.DIGIT
    return needs
}

/** Argon2id's parameters, kept beside the ciphertext. */
@Serializable
data class KdfParams(
    val alg: String = "argon2id",
    /** Memory in KiB. */
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int = 1,
    /** Base64. */
    val salt: String,
)

/** AES-256-GCM output: a 12-byte nonce and the ciphertext with its tag, both Base64. */
@Serializable
data class Sealed(val iv: String, val ct: String)

object VaultCrypto {
    /** A phone's app heap is small beside a browser's 64 MiB: 32 MiB, and the count is measured. */
    const val MEMORY_KIB = 32 * 1024
    private const val MIN_ITERATIONS = 2
    private const val MAX_ITERATIONS = 8

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** The 32-byte key of a password. The caller wipes it. */
    fun deriveKey(password: String, params: KdfParams): ByteArray {
        require(params.alg == "argon2id") { "Unknown KDF" }
        val bytes = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(unb64(params.salt))
            .withParallelism(params.parallelism)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(argon)
        val out = ByteArray(32)
        generator.generateBytes(bytes, out)
        bytes.fill(0)
        return out
    }

    /**
     * Picks an iteration count so one derivation takes about `targetMs` on this phone. Measured
     * once, when the vault is made, and stored with it.
     */
    fun calibrate(random: SecureRandom, targetMs: Long = 500, memoryKiB: Int = MEMORY_KIB, now: () -> Long = System::nanoTime): KdfParams {
        val probe = KdfParams(memoryKiB = memoryKiB, iterations = 1, salt = b64(ByteArray(16).also(random::nextBytes)))
        val start = now()
        deriveKey("calibration", probe).fill(0)
        val perIteration = ((now() - start) / 1_000_000).coerceAtLeast(1)
        val iterations = Math.round(targetMs.toDouble() / perIteration).toInt().coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
        return probe.copy(iterations = iterations, salt = b64(ByteArray(16).also(random::nextBytes)))
    }

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray, random: SecureRandom): Sealed {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return Sealed(b64(iv), b64(cipher.doFinal(plain)))
    }

    /** Throws when the key is wrong or anything (ciphertext, nonce, bound data) was changed. */
    fun open(key: ByteArray, sealed: Sealed, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(sealed.iv)))
        cipher.updateAAD(aad)
        return cipher.doFinal(unb64(sealed.ct))
    }
}
