package trade.nesty.app.vault

import trade.nesty.app.i18n.tr
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's own lock over the vault file: an AES-256 key made inside the Android Keystore,
 * which never gives it out (on most phones it lives in separate hardware). Without it the file
 * cannot even be tried against a password, so a copy of the file is useless off this phone.
 */
class AndroidKeyWrap(private val alias: String = "nesty.vault.v1") : KeyWrap {
    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // The Keystore picks the nonce itself.
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val key = checkNotNull(key(create = false)) { tr("This phone's key is gone") }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, NONCE))
        return cipher.doFinal(wrapped, NONCE, wrapped.size - NONCE)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val NONCE = 12
    }
}

/**
 * The vault's file, in the app's private folder that Android leaves out of backups and device
 * transfers: the phone's key would not travel with it anyway.
 */
class FileVaultStore(context: Context) : VaultStore {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val file = File(dir, "vault.bin")
    private val failsFile = File(dir, "fails.json")

    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    /** Written beside the old file, then moved over it, so a crash never leaves half a vault. */
    override fun write(bytes: ByteArray) {
        val next = File(dir, "vault.bin.next")
        FileOutputStream(next).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        check(next.renameTo(file)) { tr("The vault could not be saved") }
    }

    override fun readFails(): Fails? =
        if (failsFile.isFile) runCatching { Json.decodeFromString(Fails.serializer(), failsFile.readText()) }.getOrNull() else null

    override fun writeFails(fails: Fails?) {
        if (fails == null) failsFile.delete() else failsFile.writeText(Json.encodeToString(Fails.serializer(), fails))
    }
}
