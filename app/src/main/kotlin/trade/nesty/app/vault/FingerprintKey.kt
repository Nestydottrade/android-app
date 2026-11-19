package trade.nesty.app.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import trade.nesty.app.i18n.tr

/** What fingerprint unlock has ready: a cipher for the prompt, nothing, or a key Android dropped. */
sealed interface FingerprintOpener {
    class Ready(val cipher: Cipher) : FingerprintOpener

    data object Off : FingerprintOpener

    /** The phone's fingerprints or its screen lock changed, so Android dropped the key. */
    data object Dropped : FingerprintOpener
}

/**
 * Fingerprint unlock's half on the phone: the vault's key, sealed by a Keystore key that works only
 * right after a fingerprint, once for each use (strong biometrics only). Android drops that key when
 * a fingerprint is added or the screen lock is removed, which turns fingerprint unlock off until the
 * password turns it on again. The sealed key is useless without this phone and an enrolled finger,
 * and the password opens Nesty as before.
 */
class FingerprintKey(context: Context, private val alias: String = "nesty.fingerprint.v1") {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val file = File(dir, "fingerprint.bin")

    val isOn: Boolean get() = file.isFile

    /**
     * A cipher that seals with a new Keystore key, replacing any old one. It works once the
     * fingerprint prompt has authorised it. Throws when the phone has no fingerprint enrolled.
     */
    fun sealer(): Cipher {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) }
            .build()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Seals [vaultKey] with an authorised [sealer] and keeps it, written whole or not at all. */
    fun save(sealer: Cipher, vaultKey: ByteArray) {
        sealer.updateAAD(AAD)
        val sealed = sealer.doFinal(vaultKey)
        val bytes = byteArrayOf(VERSION) + sealer.iv + sealed
        val next = File(dir, "fingerprint.bin.next")
        FileOutputStream(next).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        check(next.renameTo(file)) { tr("Fingerprint unlock could not be saved") }
    }

    /** A cipher for the prompt to authorise, to open the kept key. */
    fun opener(): FingerprintOpener {
        val bytes = read() ?: return FingerprintOpener.Off
        if (bytes.size != SIZE || bytes[0] != VERSION) {
            turnOff()
            return FingerprintOpener.Off
        }
        val key = runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()
        if (key == null) {
            turnOff()
            return FingerprintOpener.Dropped
        }
        return try {
            FingerprintOpener.Ready(Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 1, NONCE)) })
        } catch (_: KeyPermanentlyInvalidatedException) {
            turnOff()
            FingerprintOpener.Dropped
        }
    }

    /** The vault's key, from an [opener] cipher the prompt authorised. The caller wipes it. */
    fun open(opener: Cipher): ByteArray {
        val bytes = read() ?: throw IllegalStateException(tr("Fingerprint unlock is off"))
        opener.updateAAD(AAD)
        return opener.doFinal(bytes, 1 + NONCE, bytes.size - 1 - NONCE)
    }

    fun turnOff() {
        file.delete()
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val NONCE = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1

        /** The version byte, the nonce, the vault's 32-byte key and the tag. */
        const val SIZE = 1 + NONCE + 32 + TAG_BITS / 8
        val AAD = "nesty-android-fingerprint|v1".toByteArray(Charsets.UTF_8)
    }
}
