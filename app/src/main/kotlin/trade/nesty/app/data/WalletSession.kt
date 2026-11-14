package trade.nesty.app.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import trade.nesty.app.evm.EvmSigner
import trade.nesty.app.keys.wipe
import trade.nesty.app.sol.SolSigner
import trade.nesty.app.vault.ExportKind
import trade.nesty.app.vault.FingerprintKey
import trade.nesty.app.vault.Vault
import trade.nesty.app.vault.VaultSigner
import trade.nesty.app.vault.VaultSolSigner
import trade.nesty.app.vault.WalletInfo

enum class VaultState {
    /** No password and no wallet yet: the app only reads. */
    NONE,

    /** There is a vault and it is closed: the app shows its lock and nothing else. */
    LOCKED,
    OPEN,
}

/**
 * The vault as the screens see it. The password work (Argon2id) runs off the main thread; what
 * the screens read is refreshed after every change.
 *
 * The keys live in memory only while it is open. It locks when the app has been away for the
 * time picked in the menu, and whenever Android ends the app's process.
 */
@Stable
class WalletSession(private val vault: Vault, private val settings: Settings, private val fingerprint: FingerprintKey) {
    var state by mutableStateOf(if (vault.exists) VaultState.LOCKED else VaultState.NONE)
        private set

    /** Whether a finger opens Nesty (the password always does). */
    var fingerprintOn by mutableStateOf(fingerprint.isOn)
        private set
    var wallets by mutableStateOf<List<WalletInfo>>(emptyList())
        private set
    var active by mutableStateOf<WalletInfo?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val lockLater = Runnable { lock() }
    private var leftAt: Long? = null

    private fun refresh() {
        wallets = vault.wallets()
        active = if (vault.unlocked) vault.active() else null
        state = when {
            vault.unlocked -> VaultState.OPEN
            vault.exists -> VaultState.LOCKED
            else -> VaultState.NONE
        }
    }

    private suspend fun <T> change(block: () -> T): T = try {
        withContext(Dispatchers.Default) { block() }
    } finally {
        refresh()
    }

    suspend fun create(password: String) = change { vault.create(password) }
    suspend fun unlock(password: String) = change { vault.unlock(password) }
    suspend fun addNew(name: String) = change { vault.addNew(name) }
    suspend fun importPhrase(name: String, phrase: String) = change { vault.importPhrase(name, phrase) }
    suspend fun importKey(name: String, key: String) = change { vault.importKey(name, key) }
    suspend fun setActive(id: String) = change { vault.setActive(id) }
    suspend fun export(password: String, id: String, what: ExportKind) = change { vault.export(password, id, what) }

    fun exportKinds(wallet: WalletInfo) = vault.exportKinds(wallet)

    /** The open vault's key for fingerprint unlock to seal, after the password again. The caller wipes it. */
    suspend fun keyForFingerprint(password: String): ByteArray = change { vault.keyForFingerprint(password) }

    /** Opens the vault with the key a fingerprint released, and wipes that key. */
    suspend fun unlockWithKey(key: ByteArray) = change {
        try {
            vault.unlockWithKey(key)
        } finally {
            key.wipe()
        }
    }

    /** Whether a key a fingerprint released is this vault's: a send's confirmation. */
    fun isKey(key: ByteArray): Boolean = vault.isKey(key)

    /** Throws VaultException when `password` is not the vault's (a send's confirmation without a fingerprint). */
    suspend fun checkPassword(password: String) = change { vault.checkPassword(password) }

    fun fingerprintChanged() {
        fingerprintOn = fingerprint.isOn
    }

    fun turnOffFingerprint() {
        fingerprint.turnOff()
        fingerprintChanged()
    }

    /**
     * Signs for the wallet that trades, on its EVM side. The key is opened for each signature and
     * wiped after it; null while the vault is closed or the wallet has no EVM key.
     */
    fun evmSigner(): EvmSigner? {
        if (!vault.unlocked) return null
        val wallet = active ?: return null
        val address = wallet.evm ?: return null
        return VaultSigner(vault, wallet.id, address.lowercase())
    }

    /** The same for its Solana side; null while closed or when the wallet has no Solana key. */
    fun solSigner(): SolSigner? {
        if (!vault.unlocked) return null
        val wallet = active ?: return null
        val address = wallet.sol ?: return null
        return VaultSolSigner(vault, wallet.id, address)
    }

    /** Every EVM address of the user's wallets: a trade may only come from one of these. */
    fun ownEvm(): List<String> = wallets.mapNotNull { it.evm?.lowercase() }

    fun lock() {
        handler.removeCallbacks(lockLater)
        vault.lock()
        refresh()
    }

    private val lockAfterMs: Long get() = settings.lockAfterMinutes * 60_000L

    /** The app left the screen: the keys are dropped once it has been away long enough. */
    fun onStop() {
        if (state != VaultState.OPEN) return
        leftAt = SystemClock.elapsedRealtime()
        handler.postDelayed(lockLater, lockAfterMs)
    }

    fun onStart() {
        handler.removeCallbacks(lockLater)
        val left = leftAt ?: return
        leftAt = null
        // The timer does not run while the phone sleeps: check the clock that does.
        if (state == VaultState.OPEN && SystemClock.elapsedRealtime() - left >= lockAfterMs) lock()
    }
}
