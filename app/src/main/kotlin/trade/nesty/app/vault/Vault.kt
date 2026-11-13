package trade.nesty.app.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import trade.nesty.app.evm.EcSignature
import trade.nesty.app.evm.EvmSigner
import trade.nesty.app.evm.Secp256k1
import trade.nesty.app.sol.SolSigner
import trade.nesty.app.keys.Bip39
import trade.nesty.app.keys.Evm
import trade.nesty.app.keys.Hd
import trade.nesty.app.keys.Hex
import trade.nesty.app.keys.Sol
import trade.nesty.app.keys.wipe
import java.security.MessageDigest
import java.security.SecureRandom

/** What a wallet was made from: a phrase gives both sides, a key only its own. */
enum class WalletKind { PHRASE, EVM_KEY, SOL_KEY }

/** A wallet as the screens know it: nothing in it is secret. */
@Serializable
data class WalletInfo(
    val id: String,
    val name: String,
    val kind: WalletKind,
    /** Null for a wallet made from a Solana key. */
    val evm: String? = null,
    /** Null for a wallet made from an EVM key. */
    val sol: String? = null,
    val createdAt: Long = 0,
    /** False until its key was shown for a backup; an imported wallet is backed up already. */
    val backedUp: Boolean = false,
)

/** Which secret of a wallet to show. */
enum class ExportKind { PHRASE, EVM_KEY, SOL_KEY }

enum class VaultError {
    NO_VAULT, EXISTS, LOCKED, BAD_PASSWORD, WEAK_PASSWORD, BAD_PHRASE, BAD_KEY, DUPLICATE,
    NOT_FOUND, LIMIT, TOO_MANY_ATTEMPTS, CORRUPT, NO_SUCH_KEY,
}

class VaultException(val code: VaultError, val retryAfterMs: Long? = null) : Exception(code.name)

/** Failed unlocks: how many, and until when the next try has to wait. */
@Serializable
data class Fails(val count: Int = 0, val until: Long = 0)

/** Where the vault's bytes live. On the phone: a private file. */
interface VaultStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun readFails(): Fails?
    fun writeFails(fails: Fails?)
}

/**
 * A second lock over the whole file, by a key that cannot leave the phone (the Android
 * Keystore). A copied file is then useless elsewhere, whatever the password.
 */
interface KeyWrap {
    fun wrap(plain: ByteArray): ByteArray

    /** Throws when the phone's key is gone or the bytes were changed. */
    fun unwrap(wrapped: ByteArray): ByteArray
}

@Serializable
private data class PublicPart(val wallets: List<WalletInfo> = emptyList(), val active: String? = null)

@Serializable
private data class VaultRecord(
    val version: Int = 1,
    val kdf: KdfParams,
    /** The wallets' names and addresses, sealed: private, and nobody can swap an address in it. */
    val public: Sealed,
    /** Each wallet's secret, sealed on its own and opened only when it is used. */
    val secrets: Map<String, Sealed> = emptyMap(),
)

/**
 * The phone's keys. Every wallet has its own secret (a recovery phrase or one private key),
 * all under one password.
 *
 * - The password goes through Argon2id; AES-256-GCM seals with that key. The KDF's parameters
 *   are bound into every seal, so they cannot be weakened in the file.
 * - While unlocked only the derived key and the wallets' public part are in memory. A secret
 *   is opened for the moment it is needed and wiped after.
 * - Nothing here logs, and nothing derived from a key leaves this class except an address or
 *   the export the user asked for with their password.
 */
class Vault(
    private val store: VaultStore,
    private val wrap: KeyWrap,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Tests pass small parameters; the phone measures its own. */
    private val newKdf: (SecureRandom) -> KdfParams = { VaultCrypto.calibrate(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var key: ByteArray? = null
    private var record: VaultRecord? = null
    private var public: PublicPart = PublicPart()

    val exists: Boolean @Synchronized get() = store.read() != null
    val unlocked: Boolean @Synchronized get() = key != null

    @Synchronized
    fun wallets(): List<WalletInfo> = if (key == null) emptyList() else public.wallets

    @Synchronized
    fun active(): WalletInfo? = public.wallets.firstOrNull { it.id == public.active } ?: public.wallets.firstOrNull()

    /** Makes an empty vault and leaves it unlocked. */
    @Synchronized
    fun create(password: String) {
        if (store.read() != null) throw VaultException(VaultError.EXISTS)
        if (passwordNeeds(password).isNotEmpty()) throw VaultException(VaultError.WEAK_PASSWORD)
        val kdf = newKdf(random)
        val k = VaultCrypto.deriveKey(password, kdf)
        val part = PublicPart()
        val rec = VaultRecord(kdf = kdf, public = sealPublic(k, kdf, part))
        persist(rec)
        key = k
        record = rec
        public = part
        store.writeFails(null)
    }

    /** Opens the vault. Five tries are free; after that each failure doubles the wait, up to 15 minutes. */
    @Synchronized
    fun unlock(password: String) {
        val rec = load()
        checkThrottle()
        val k = VaultCrypto.deriveKey(password, rec.kdf)
        val part = try {
            json.decodeFromString<PublicPart>(String(VaultCrypto.open(k, rec.public, aad(rec.kdf, "public")), Charsets.UTF_8))
        } catch (_: Exception) {
            k.wipe()
            recordFailure()
            throw VaultException(VaultError.BAD_PASSWORD)
        }
        store.writeFails(null)
        key.wipe()
        key = k
        record = rec
        public = part
    }

    @Synchronized
    fun lock() {
        key.wipe()
        key = null
        record = null
        public = PublicPart()
    }

    /** A new wallet with its own fresh phrase: a new EVM address and a new Solana address. */
    @Synchronized
    fun addNew(name: String): WalletInfo {
        val phrase = Bip39.generate(random)
        return add(name, WalletKind.PHRASE, phrase.toByteArray(Charsets.UTF_8), addressesOfPhrase(phrase), backedUp = false)
    }

    @Synchronized
    fun importPhrase(name: String, input: String): WalletInfo {
        if (!Bip39.isValid(input)) throw VaultException(VaultError.BAD_PHRASE)
        val phrase = Bip39.normalize(input)
        return add(name, WalletKind.PHRASE, phrase.toByteArray(Charsets.UTF_8), addressesOfPhrase(phrase), backedUp = true)
    }

    /** An EVM key or a Solana key: the wallet then has that side only. */
    @Synchronized
    fun importKey(name: String, input: String): WalletInfo {
        Evm.parsePrivateKey(input)?.let { k ->
            return add(name, WalletKind.EVM_KEY, k, Evm.address(k) to null, backedUp = true)
        }
        Sol.parseSecretKey(input)?.let { seed ->
            return add(name, WalletKind.SOL_KEY, seed, null to Sol.address(seed), backedUp = true)
        }
        throw VaultException(VaultError.BAD_KEY)
    }

    /** The wallet that trades from now on. Nothing moves between wallets. */
    @Synchronized
    fun setActive(id: String) {
        val k = key ?: throw VaultException(VaultError.LOCKED)
        if (public.wallets.none { it.id == id }) throw VaultException(VaultError.NOT_FOUND)
        savePublic(k, public.copy(active = id), requireRecord().secrets)
    }

    /**
     * A wallet's secret, for a backup. Asks for the password again, and a wrong one counts as a
     * failed unlock. The wallet is marked as backed up.
     */
    @Synchronized
    fun export(password: String, id: String, what: ExportKind): String {
        val k = key ?: throw VaultException(VaultError.LOCKED)
        val rec = requireRecord()
        checkThrottle()
        val again = VaultCrypto.deriveKey(password, rec.kdf)
        val same = MessageDigest.isEqual(again, k)
        again.wipe()
        if (!same) {
            recordFailure()
            throw VaultException(VaultError.BAD_PASSWORD)
        }
        store.writeFails(null)
        val wallet = public.wallets.firstOrNull { it.id == id } ?: throw VaultException(VaultError.NOT_FOUND)
        val secret = openSecret(k, rec, wallet.id)
        try {
            val out = when (wallet.kind) {
                WalletKind.PHRASE -> {
                    val phrase = String(secret, Charsets.UTF_8)
                    when (what) {
                        ExportKind.PHRASE -> phrase
                        ExportKind.EVM_KEY -> withSeed(phrase) { seed -> Hd.evmKey(seed).let { pk -> "0x" + Hex.encode(pk).also { pk.wipe() } } }
                        ExportKind.SOL_KEY -> withSeed(phrase) { seed -> Hd.solSeed(seed).let { s -> Sol.secretKey(s).also { s.wipe() } } }
                    }
                }
                WalletKind.EVM_KEY -> if (what == ExportKind.EVM_KEY) "0x" + Hex.encode(secret) else throw VaultException(VaultError.NO_SUCH_KEY)
                WalletKind.SOL_KEY -> if (what == ExportKind.SOL_KEY) Sol.secretKey(secret) else throw VaultException(VaultError.NO_SUCH_KEY)
            }
            if (!wallet.backedUp) {
                savePublic(k, public.copy(wallets = public.wallets.map { if (it.id == id) it.copy(backedUp = true) else it }), rec.secrets)
            }
            return out
        } finally {
            secret.wipe()
        }
    }

    /**
     * Signs a 32-byte hash with a wallet's EVM key, for a trade the engine has checked. The key
     * is opened for this one signature and wiped after; only the signature leaves. `address` is
     * the wallet the trade was checked for: a vault that has changed since refuses.
     */
    @Synchronized
    fun signEvm(id: String, address: String, hash: ByteArray): EcSignature {
        val k = key ?: throw VaultException(VaultError.LOCKED)
        val rec = requireRecord()
        val wallet = public.wallets.firstOrNull { it.id == id } ?: throw VaultException(VaultError.NOT_FOUND)
        if (wallet.evm == null || !wallet.evm.equals(address, ignoreCase = true)) throw VaultException(VaultError.NO_SUCH_KEY)
        val secret = openSecret(k, rec, wallet.id)
        try {
            return when (wallet.kind) {
                WalletKind.PHRASE -> withSeed(String(secret, Charsets.UTF_8)) { seed ->
                    val pk = Hd.evmKey(seed)
                    try {
                        Secp256k1.sign(hash, pk)
                    } finally {
                        pk.wipe()
                    }
                }
                WalletKind.EVM_KEY -> Secp256k1.sign(hash, secret)
                WalletKind.SOL_KEY -> throw VaultException(VaultError.NO_SUCH_KEY)
            }
        } finally {
            secret.wipe()
        }
    }

    /**
     * Signs a Solana transaction's message for one wallet: its key is opened for this signature
     * and wiped after it. The address must be the wallet's own Solana address.
     */
    @Synchronized
    fun signSol(id: String, address: String, message: ByteArray): ByteArray {
        val k = key ?: throw VaultException(VaultError.LOCKED)
        val rec = requireRecord()
        val wallet = public.wallets.firstOrNull { it.id == id } ?: throw VaultException(VaultError.NOT_FOUND)
        if (wallet.sol == null || wallet.sol != address) throw VaultException(VaultError.NO_SUCH_KEY)
        val secret = openSecret(k, rec, wallet.id)
        try {
            return when (wallet.kind) {
                WalletKind.PHRASE -> withSeed(String(secret, Charsets.UTF_8)) { seed ->
                    val sk = Hd.solSeed(seed)
                    try {
                        Sol.sign(message, sk, address)
                    } finally {
                        sk.wipe()
                    }
                }
                WalletKind.SOL_KEY -> Sol.sign(message, secret, address)
                WalletKind.EVM_KEY -> throw VaultException(VaultError.NO_SUCH_KEY)
            }
        } finally {
            secret.wipe()
        }
    }

    /** What a wallet can export: its phrase if it has one, and the key of each side it has. */
    fun exportKinds(wallet: WalletInfo): List<ExportKind> = listOfNotNull(
        ExportKind.PHRASE.takeIf { wallet.kind == WalletKind.PHRASE },
        ExportKind.EVM_KEY.takeIf { wallet.evm != null },
        ExportKind.SOL_KEY.takeIf { wallet.sol != null },
    )

    private fun add(name: String, kind: WalletKind, secret: ByteArray, addresses: Pair<String?, String?>, backedUp: Boolean): WalletInfo {
        try {
            val k = key ?: throw VaultException(VaultError.LOCKED)
            val rec = requireRecord()
            val (evm, sol) = addresses
            if (public.wallets.size >= MAX_WALLETS) throw VaultException(VaultError.LIMIT)
            // The same address twice would be one wallet listed twice.
            if (public.wallets.any { (evm != null && it.evm.equals(evm, ignoreCase = true)) || (sol != null && it.sol == sol) }) {
                throw VaultException(VaultError.DUPLICATE)
            }
            val id = Hex.encode(ByteArray(8).also(random::nextBytes))
            val info = WalletInfo(id, cleanName(name).ifEmpty { "Wallet ${public.wallets.size + 1}" }, kind, evm, sol, now(), backedUp)
            val secrets = rec.secrets + (id to VaultCrypto.seal(k, secret, aad(rec.kdf, "secret|$id"), random))
            // The first wallet trades; a later one waits until it is picked.
            savePublic(k, public.copy(wallets = public.wallets + info, active = public.active ?: id), secrets)
            return info
        } finally {
            secret.wipe()
        }
    }

    private fun addressesOfPhrase(phrase: String): Pair<String?, String?> = withSeed(phrase) { seed ->
        val evmKey = Hd.evmKey(seed)
        val solSeed = Hd.solSeed(seed)
        (Evm.address(evmKey) to Sol.address(solSeed)).also {
            evmKey.wipe()
            solSeed.wipe()
        }
    }

    private fun <T> withSeed(phrase: String, use: (ByteArray) -> T): T {
        val seed = Bip39.toSeed(phrase)
        try {
            return use(seed)
        } finally {
            seed.wipe()
        }
    }

    private fun openSecret(k: ByteArray, rec: VaultRecord, id: String): ByteArray {
        val sealed = rec.secrets[id] ?: throw VaultException(VaultError.CORRUPT)
        return try {
            VaultCrypto.open(k, sealed, aad(rec.kdf, "secret|$id"))
        } catch (_: Exception) {
            throw VaultException(VaultError.CORRUPT)
        }
    }

    private fun sealPublic(k: ByteArray, kdf: KdfParams, part: PublicPart): Sealed =
        VaultCrypto.seal(k, json.encodeToString(PublicPart.serializer(), part).toByteArray(Charsets.UTF_8), aad(kdf, "public"), random)

    private fun savePublic(k: ByteArray, part: PublicPart, secrets: Map<String, Sealed>) {
        val rec = requireRecord().copy(public = sealPublic(k, requireRecord().kdf, part), secrets = secrets)
        persist(rec)
        record = rec
        public = part
    }

    private fun persist(rec: VaultRecord) {
        store.write(wrap.wrap(json.encodeToString(VaultRecord.serializer(), rec).toByteArray(Charsets.UTF_8)))
    }

    private fun load(): VaultRecord {
        val bytes = store.read() ?: throw VaultException(VaultError.NO_VAULT)
        return try {
            json.decodeFromString<VaultRecord>(String(wrap.unwrap(bytes), Charsets.UTF_8)).also { check(it.version == 1) }
        } catch (_: Exception) {
            throw VaultException(VaultError.CORRUPT)
        }
    }

    private fun requireRecord(): VaultRecord = record ?: throw VaultException(VaultError.LOCKED)

    /** Binds the KDF's parameters and the blob's role to each seal, so neither can be swapped. */
    private fun aad(k: KdfParams, role: String): ByteArray =
        "nesty-android-vault|v1|${k.alg}|${k.memoryKiB}|${k.iterations}|${k.parallelism}|${k.salt}|$role".toByteArray(Charsets.UTF_8)

    private fun checkThrottle() {
        val fails = store.readFails() ?: return
        if (fails.until > now()) throw VaultException(VaultError.TOO_MANY_ATTEMPTS, fails.until - now())
    }

    private fun recordFailure() {
        val count = (store.readFails()?.count ?: 0) + 1
        // Free tries first; then 30 s, 60 s, 120 s … capped at 15 minutes.
        val delay = if (count < FREE_TRIES) 0 else minOf(15 * 60_000L, 30_000L shl minOf(count - FREE_TRIES, 10))
        store.writeFails(Fails(count, now() + delay))
    }

    companion object {
        const val MAX_WALLETS = 50
        const val MAX_NAME = 16
        const val FREE_TRIES = 5

        fun cleanName(name: String): String = name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME)
    }
}

/** The engine's signer for one wallet: each signature opens the key in the vault and wipes it. */
class VaultSigner(private val vault: Vault, private val walletId: String, override val address: String) : EvmSigner {
    override fun signHash(hash: ByteArray): EcSignature = vault.signEvm(walletId, address, hash)
}

/** The Solana engine's signer for one wallet, the same way: opened per signature, then wiped. */
class VaultSolSigner(private val vault: Vault, private val walletId: String, override val address: String) : SolSigner {
    override fun signMessage(message: ByteArray): ByteArray = vault.signSol(walletId, address, message)
}
