package trade.nesty.app

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import trade.nesty.app.data.NestyJson
import trade.nesty.app.evm.Secp256k1
import trade.nesty.app.keys.Bip39
import trade.nesty.app.keys.Evm
import trade.nesty.app.keys.Hd
import trade.nesty.app.keys.Sol
import trade.nesty.app.keys.keccak256
import trade.nesty.app.vault.ExportKind
import trade.nesty.app.vault.Fails
import trade.nesty.app.vault.KdfParams
import trade.nesty.app.vault.KeyWrap
import trade.nesty.app.vault.Vault
import trade.nesty.app.vault.VaultCrypto
import trade.nesty.app.vault.VaultError
import trade.nesty.app.vault.VaultException
import trade.nesty.app.vault.VaultStore
import trade.nesty.app.vault.WalletKind
import trade.nesty.app.vault.WatchSigner
import trade.nesty.app.vault.WatchSolSigner
import trade.nesty.app.vault.missingSide
import java.security.SecureRandom

/**
 * The vault, over a store in memory. The phrases and keys are the PUBLIC TEST DATA of
 * `keyVectors.json` (see KeysTest); a wallet the vault makes itself is read back only to check
 * it and is never written anywhere.
 */
class VaultTest {
    private class MemoryStore : VaultStore {
        var bytes: ByteArray? = null
        var fails: Fails? = null
        override fun read() = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes }
        override fun readFails() = fails
        override fun writeFails(fails: Fails?) { this.fails = fails }
    }

    /** Stands in for the phone's Keystore: a key held outside the store. */
    private class PhoneKey(private val key: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : KeyWrap {
        private val aad = "wrap".toByteArray()
        override fun wrap(plain: ByteArray): ByteArray = VaultCrypto.seal(key, plain, aad, SecureRandom()).let { "${it.iv}.${it.ct}".toByteArray() }
        override fun unwrap(wrapped: ByteArray): ByteArray =
            String(wrapped).split(".").let { VaultCrypto.open(key, trade.nesty.app.vault.Sealed(it[0], it[1]), aad) }
    }

    /** No second lock, to look at what the password alone protects. */
    private object NoWrap : KeyWrap {
        override fun wrap(plain: ByteArray) = plain
        override fun unwrap(wrapped: ByteArray) = wrapped
    }

    private val password = "Correct-Horse-9"
    private val vectors = NestyJson.parseToJsonElement(Fixtures.text("keyVectors")).jsonObject
    private val phrases = vectors.getValue("phrases").jsonArray.map { it.jsonObject }
    private fun phrase(i: Int, field: String) = phrases[i].getValue(field).jsonPrimitive.content
    private val sampleKey = vectors.getValue("evmKeys").jsonArray.first().jsonObject

    private var clock = 1_000_000L
    private val store = MemoryStore()
    private val phone = PhoneKey()

    private fun vault(store: VaultStore = this.store, wrap: KeyWrap = phone) =
        Vault(store, wrap, now = { clock }, newKdf = { r -> KdfParams(memoryKiB = 64, iterations = 1, salt = VaultCrypto.b64(ByteArray(16).also(r::nextBytes))) })

    private fun refused(code: VaultError, block: () -> Unit): VaultException {
        try {
            block()
        } catch (e: VaultException) {
            assertEquals(code, e.code)
            return e
        }
        fail("Not refused with $code")
        throw IllegalStateException()
    }

    @Test
    fun aNewVaultIsEmptyAndOpen() {
        val v = vault()
        assertFalse(v.exists)
        refused(VaultError.WEAK_PASSWORD) { v.create("password") }
        assertFalse(v.exists)
        v.create(password)
        assertTrue(v.exists && v.unlocked)
        assertTrue(v.wallets().isEmpty())
        assertNull(v.active())
        refused(VaultError.EXISTS) { v.create(password) }
    }

    @Test
    fun anImportedPhraseHasBothAddresses() {
        val v = vault().apply { create(password) }
        val w = v.importPhrase("  Dev   wallet  ", "  " + phrase(1, "mnemonic").uppercase() + " ")
        assertEquals("Dev wallet", w.name)
        assertEquals(WalletKind.PHRASE, w.kind)
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", w.evm)
        assertEquals(phrase(1, "solAddress"), w.sol)
        assertTrue(w.backedUp)
        assertEquals(w.id, v.active()!!.id)
        assertEquals(listOf(ExportKind.PHRASE, ExportKind.EVM_KEY, ExportKind.SOL_KEY), v.exportKinds(w))
        assertEquals(phrase(1, "mnemonic"), v.export(password, w.id, ExportKind.PHRASE))
        assertEquals(phrase(1, "evmPrivateKey"), v.export(password, w.id, ExportKind.EVM_KEY))
        assertEquals(phrase(1, "solSecretBase58"), v.export(password, w.id, ExportKind.SOL_KEY))
    }

    @Test
    fun aNewWalletHasItsOwnPhraseAndWaitsForItsBackup() {
        val v = vault().apply { create(password) }
        val first = v.addNew("")
        val second = v.addNew("Sniper")
        assertEquals("Wallet 1", first.name)
        assertEquals(WalletKind.PHRASE, second.kind)
        assertTrue(Evm.isAddress(second.evm!!) && second.sol!!.length in 32..44)
        assertFalse(second.backedUp)
        assertTrue(first.evm != second.evm && first.sol != second.sol)
        // The first wallet keeps trading: a new one waits until it is picked.
        assertEquals(first.id, v.active()!!.id)
        // Its phrase is its own, and gives its address back.
        val words = v.export(password, second.id, ExportKind.PHRASE)
        assertTrue(Bip39.isValid(words))
        assertEquals(second.evm, Evm.address(Hd.evmKey(Bip39.toSeed(words))))
        assertTrue(words != v.export(password, first.id, ExportKind.PHRASE))
        // Showing it counts as the backup.
        assertTrue(v.wallets().first { it.id == second.id }.backedUp)
    }

    @Test
    fun aKeyCoversItsOwnSideOnly() {
        val v = vault().apply { create(password) }
        val key = sampleKey.getValue("privateKey").jsonPrimitive.content
        val evm = v.importKey("EVM only", key.removePrefix("0x").uppercase())
        assertEquals(WalletKind.EVM_KEY, evm.kind)
        assertEquals(sampleKey.getValue("address").jsonPrimitive.content, evm.evm)
        assertNull(evm.sol)
        assertEquals(listOf(ExportKind.EVM_KEY), v.exportKinds(evm))
        assertEquals(key, v.export(password, evm.id, ExportKind.EVM_KEY))
        refused(VaultError.NO_SUCH_KEY) { v.export(password, evm.id, ExportKind.SOL_KEY) }
        refused(VaultError.NO_SUCH_KEY) { v.export(password, evm.id, ExportKind.PHRASE) }

        val sol = v.importKey("Sol only", phrase(0, "solSecretBase58"))
        assertEquals(WalletKind.SOL_KEY, sol.kind)
        assertEquals(phrase(0, "solAddress"), sol.sol)
        assertNull(sol.evm)
        assertEquals(phrase(0, "solSecretBase58"), v.export(password, sol.id, ExportKind.SOL_KEY))
        assertEquals(phrase(2, "solAddress"), v.importKey("", phrase(2, "solSecretJson")).sol)
    }

    @Test
    fun aWalletThatIsAlreadyThereIsRefused() {
        val v = vault().apply { create(password) }
        v.importPhrase("One", phrase(1, "mnemonic"))
        refused(VaultError.DUPLICATE) { v.importPhrase("Again", phrase(1, "mnemonic")) }
        // Its keys are the same wallet's two sides.
        refused(VaultError.DUPLICATE) { v.importKey("Its EVM key", phrase(1, "evmPrivateKey")) }
        refused(VaultError.DUPLICATE) { v.importKey("Its Solana key", phrase(1, "solSecretBase58")) }
        assertEquals(1, v.wallets().size)
        refused(VaultError.BAD_PHRASE) { v.importPhrase("x", "abandon abandon abandon") }
        refused(VaultError.BAD_KEY) { v.importKey("x", "0x1234") }
        refused(VaultError.BAD_KEY) { v.importKey("x", phrase(0, "solAddress")) }
        assertEquals(1, v.wallets().size)
    }

    @Test
    fun lockingDropsEverythingAndThePasswordBringsItBack() {
        val v = vault().apply { create(password) }
        val a = v.importPhrase("A", phrase(0, "mnemonic"))
        val b = v.importPhrase("B", phrase(1, "mnemonic"))
        v.setActive(b.id)
        refused(VaultError.NOT_FOUND) { v.setActive("nope") }
        v.lock()
        assertFalse(v.unlocked)
        assertTrue(v.wallets().isEmpty())
        refused(VaultError.LOCKED) { v.addNew("x") }
        refused(VaultError.LOCKED) { v.export(password, a.id, ExportKind.PHRASE) }
        refused(VaultError.LOCKED) { v.setActive(a.id) }
        refused(VaultError.BAD_PASSWORD) { v.unlock("Wrong-Horse-9") }
        assertFalse(v.unlocked)
        v.unlock(password)
        assertEquals(listOf("A", "B"), v.wallets().map { it.name })
        assertEquals(b.id, v.active()!!.id)

        // Another start of the app: the same file, nothing in memory.
        val again = vault()
        assertTrue(again.exists && !again.unlocked)
        again.unlock(password)
        assertEquals(b.id, again.active()!!.id)
        assertEquals(phrase(0, "mnemonic"), again.export(password, a.id, ExportKind.PHRASE))
    }

    @Test
    fun wrongPasswordsHaveToWait() {
        val v = vault().apply { create(password) }
        val w = v.importPhrase("A", phrase(0, "mnemonic"))
        v.lock()
        repeat(4) { refused(VaultError.BAD_PASSWORD) { v.unlock("Wrong-Horse-$it") } }
        // Four are free. The fifth starts the wait, also for the right password.
        refused(VaultError.BAD_PASSWORD) { v.unlock("Wrong-Horse-4") }
        val wait = refused(VaultError.TOO_MANY_ATTEMPTS) { v.unlock(password) }
        assertEquals(30_000L, wait.retryAfterMs)
        clock += 29_999
        refused(VaultError.TOO_MANY_ATTEMPTS) { v.unlock(password) }
        clock += 1
        refused(VaultError.BAD_PASSWORD) { v.unlock("Wrong-Horse-5") }
        // Each failure after that doubles it. Closing the app does not reset it: it is in the store.
        assertEquals(60_000L, refused(VaultError.TOO_MANY_ATTEMPTS) { vault().unlock(password) }.retryAfterMs)
        clock += 60_000
        v.unlock(password)
        assertNull(store.fails)
        // A wrong password on an export counts the same.
        repeat(5) { refused(VaultError.BAD_PASSWORD) { v.export("Wrong-Horse-9", w.id, ExportKind.PHRASE) } }
        refused(VaultError.TOO_MANY_ATTEMPTS) { v.export(password, w.id, ExportKind.PHRASE) }
        // Never longer than 15 minutes.
        store.fails = Fails(count = 40, until = 0)
        refused(VaultError.BAD_PASSWORD) { v.export("Wrong-Horse-9", w.id, ExportKind.PHRASE) }
        assertEquals(15 * 60_000L, store.fails!!.until - clock)
    }

    @Test
    fun theFileShowsNothingAndCannotBeChanged() {
        val bare = MemoryStore()
        val v = vault(bare, NoWrap).apply { create(password) }
        val w = v.importPhrase("Dev wallet", phrase(1, "mnemonic"))
        v.importKey("Key", sampleKey.getValue("privateKey").jsonPrimitive.content)
        val text = String(bare.bytes!!)
        // Even without the phone's lock: no word of the phrase, no key, no address, no name.
        for (secret in listOf("junk", "test test", w.evm!!, w.evm.lowercase().removePrefix("0x"), w.sol!!, "Dev wallet", "4c1e9a7b")) {
            assertFalse(text.contains(secret, ignoreCase = true))
        }
        // Weakening the password's cost in the file breaks every seal made with it.
        val iterations = Regex("\"iterations\":(\\d+)")
        assertTrue(iterations.containsMatchIn(text))
        bare.bytes = text.replace(iterations, "\"iterations\":2").toByteArray()
        refused(VaultError.BAD_PASSWORD) { vault(bare, NoWrap).unlock(password) }
        bare.fails = null
        bare.bytes = text.toByteArray()
        vault(bare, NoWrap).unlock(password)
        // A secret moved under another wallet's name does not open.
        val ids = Regex("\"secrets\":\\{\"([0-9a-f]{16})\":(\\{[^}]*\\}),\"([0-9a-f]{16})\":(\\{[^}]*\\})").find(text)!!.groupValues
        bare.bytes = text.replace(ids[2], "SWAP").replace(ids[4], ids[2]).replace("SWAP", ids[4]).toByteArray()
        val swapped = vault(bare, NoWrap).apply { unlock(password) }
        refused(VaultError.CORRUPT) { swapped.export(password, w.id, ExportKind.PHRASE) }
    }

    @Test
    fun aCopiedFileIsUselessOnAnotherPhone() {
        val v = vault().apply { create(password) }
        v.importPhrase("A", phrase(0, "mnemonic"))
        val copy = MemoryStore().also { it.bytes = store.bytes!!.copyOf() }
        // The right password, but not this phone's key.
        refused(VaultError.CORRUPT) { vault(copy, PhoneKey()).unlock(password) }
        // One changed byte on the right phone.
        copy.bytes!![copy.bytes!!.size / 2] = (copy.bytes!![copy.bytes!!.size / 2].toInt() xor 1).toByte()
        refused(VaultError.CORRUPT) { vault(copy, phone).unlock(password) }
        refused(VaultError.NO_VAULT) { vault(MemoryStore()).unlock(password) }
    }

    @Test
    fun namesAreKeptShort() {
        assertEquals("A very long wall", Vault.cleanName("  A   very long wallet name "))
        assertEquals("", Vault.cleanName("   "))
        val v = vault().apply { create(password) }
        v.importPhrase("A", phrase(0, "mnemonic"))
        assertEquals("Wallet 2", v.importPhrase("  ", phrase(1, "mnemonic")).name)
    }

    @Test
    fun signsTradesWithoutTheKeyLeaving() {
        val v = vault().apply { create(password) }
        val hash = trade.nesty.app.keys.keccak256("a trade".toByteArray())
        // A key wallet signs as the key itself does (RFC 6979: the same signature).
        val key = sampleKey.getValue("privateKey").jsonPrimitive.content
        val k = v.importKey("Key", key)
        val signer = trade.nesty.app.vault.VaultSigner(v, k.id, k.evm!!)
        val sig = signer.signHash(hash)
        val direct = trade.nesty.app.evm.Secp256k1.sign(hash, Evm.parsePrivateKey(key)!!)
        assertEquals(direct.r, sig.r)
        assertEquals(direct.s, sig.s)
        assertEquals(direct.recId, sig.recId)
        // A phrase wallet signs with its EVM key: the signature recovers to its address.
        val p = v.importPhrase("Phrase", phrase(0, "mnemonic"))
        val ps = v.signEvm(p.id, p.evm!!, hash)
        assertEquals(phrase(0, "evmAddress").lowercase(), trade.nesty.app.evm.Secp256k1.recoverAddress(hash, ps)?.lowercase())
    }

    @Test
    fun refusesToSignForAnotherWalletOrWhenLocked() {
        val v = vault().apply { create(password) }
        val hash = ByteArray(32) { 1 }
        val k = v.importKey("Key", sampleKey.getValue("privateKey").jsonPrimitive.content)
        // Checked for one address, signed by another wallet: refused.
        refused(VaultError.NO_SUCH_KEY) { v.signEvm(k.id, "0x000000000000000000000000000000000000dead", hash) }
        // A Solana key has no EVM side.
        val sol = v.importKey("Sol", phrase(0, "solSecretBase58"))
        refused(VaultError.NO_SUCH_KEY) { v.signEvm(sol.id, k.evm!!, hash) }
        refused(VaultError.NOT_FOUND) { v.signEvm("nope", k.evm!!, hash) }
        v.lock()
        refused(VaultError.LOCKED) { v.signEvm(k.id, k.evm!!, hash) }
    }

    @Test
    fun lockingWithExitPlansOnKeepsOnlyTheirSellsSignable() {
        val watch = PhoneKey()
        val v = Vault(store, phone, now = { clock }, newKdf = { r -> KdfParams(memoryKiB = 64, iterations = 1, salt = VaultCrypto.b64(ByteArray(16).also(r::nextBytes))) }, watchWrap = watch)
        v.create(password)
        val hash = ByteArray(32) { 7 }
        val k = v.importKey("Key", sampleKey.getValue("privateKey").jsonPrimitive.content)
        val sol = v.importKey("Sol", phrase(0, "solSecretBase58"))
        val open = v.signEvm(k.id, k.evm!!, hash)
        v.lockKeepingWatch()
        // Locked for the screens: no wallets, no plain signature, no export.
        assertFalse(v.unlocked)
        assertTrue(v.watching)
        assertTrue(v.wallets().isEmpty())
        refused(VaultError.LOCKED) { v.signEvm(k.id, k.evm!!, hash) }
        refused(VaultError.LOCKED) { v.export(password, k.id, ExportKind.EVM_KEY) }
        // The watch still signs, the same signature, for the wallet it was checked for only.
        val kept = WatchSigner(v, k.id, k.evm!!).signHash(hash)
        assertEquals(open.r, kept.r)
        assertEquals(open.s, kept.s)
        refused(VaultError.NO_SUCH_KEY) { v.signEvmForWatch(k.id, "0x000000000000000000000000000000000000dead", hash) }
        assertEquals(64, WatchSolSigner(v, sol.id, sol.sol!!).signMessage(ByteArray(10)).size)
        // The password opens it as before, and a full lock drops the sealed key too.
        v.unlock(password)
        assertFalse(v.watching)
        v.lockKeepingWatch()
        v.lock()
        assertFalse(v.watching)
        refused(VaultError.LOCKED) { v.signEvmForWatch(k.id, k.evm!!, hash) }
    }

    @Test
    fun withoutAPhoneKeyForTheWatchLockingLocksAll() {
        val v = vault().apply { create(password) }
        val k = v.importKey("Key", sampleKey.getValue("privateKey").jsonPrimitive.content)
        v.lockKeepingWatch()
        assertFalse(v.watching)
        refused(VaultError.LOCKED) { v.signEvmForWatch(k.id, k.evm!!, ByteArray(32) { 1 }) }
    }

    @Test
    fun fingerprintKeepsTheVaultsKeyBehindThePassword() {
        val v = vault().apply { create(password) }
        val w = v.importPhrase("Phrase", phrase(0, "mnemonic"))
        // Turning it on asks for the password again; a wrong one counts as a failed unlock.
        refused(VaultError.BAD_PASSWORD) { v.keyForFingerprint("Wrong-Horse-9") }
        assertEquals(1, store.fails?.count)
        val kept = v.keyForFingerprint(password)
        assertEquals(32, kept.size)
        assertNull(store.fails)
        assertTrue(v.isKey(kept))
        assertFalse(v.isKey(ByteArray(32)))

        // The kept key opens the vault with no password, and the wallets are back.
        v.lock()
        assertFalse(v.isKey(kept))
        v.unlockWithKey(kept)
        assertTrue(v.unlocked)
        assertEquals(listOf(w.id), v.wallets().map { it.id })
        // It is the same vault: it still signs, and the caller's copy can be wiped.
        kept.fill(0)
        assertTrue(v.signSol(w.id, w.sol!!, ByteArray(8)).size == 64)
        v.lock()
        refused(VaultError.LOCKED) { v.keyForFingerprint(password) }
    }

    @Test
    fun aKeyThatDoesNotOpenTheVaultChangesNothing() {
        val v = vault().apply { create(password) }
        v.importPhrase("Phrase", phrase(0, "mnemonic"))
        v.lock()
        refused(VaultError.STALE_KEY) { v.unlockWithKey(ByteArray(32) { 7 }) }
        assertFalse(v.unlocked)
        // A key from another vault (made again after the app's data was cleared) is refused too.
        val other = vault(store = MemoryStore()).apply { create(password) }
        val foreign = other.keyForFingerprint(password)
        refused(VaultError.STALE_KEY) { v.unlockWithKey(foreign) }
        // And the password still opens it.
        v.unlock(password)
        assertTrue(v.unlocked)
    }

    @Test
    fun aKeyWalletGetsANewKeyForTheSideItLacks() {
        val v = vault().apply { create(password) }
        val key = sampleKey.getValue("privateKey").jsonPrimitive.content
        val evm = v.importKey("EVM only", key)
        assertEquals(WalletKind.SOL_KEY, evm.missingSide)
        val both = v.createSide(evm.id)
        // The same wallet, now with a Solana address of its own; the new key waits for its backup.
        assertEquals(evm.id, both.id)
        assertEquals(WalletKind.EVM_KEY, both.kind)
        assertEquals(evm.evm, both.evm)
        assertTrue(Sol.isAddress(both.sol!!))
        assertNull(both.missingSide)
        assertFalse(both.backedUp)
        assertEquals(listOf(ExportKind.EVM_KEY, ExportKind.SOL_KEY), v.exportKinds(both))
        assertEquals(ExportKind.SOL_KEY, v.firstExport(both))
        refused(VaultError.EXISTS) { v.createSide(evm.id) }
        refused(VaultError.EXISTS) { v.importSide(evm.id, phrase(0, "solSecretBase58")) }
        // Both sides sign: the EVM key as before, the new key for its own address.
        val hash = keccak256("a trade".toByteArray())
        assertEquals(evm.evm!!.lowercase(), Secp256k1.recoverAddress(hash, v.signEvm(both.id, both.evm!!, hash))?.lowercase())
        assertEquals(64, v.signSol(both.id, both.sol!!, ByteArray(10)).size)
        // Showing the EVM key is no backup of the new key; showing the new key is.
        assertEquals(key, v.export(password, both.id, ExportKind.EVM_KEY))
        assertFalse(v.wallets().single().backedUp)
        val made = Sol.parseSecretKey(v.export(password, both.id, ExportKind.SOL_KEY))!!
        assertEquals(both.sol, Sol.address(made))
        made.fill(0)
        assertTrue(v.wallets().single().backedUp)
        assertEquals(ExportKind.EVM_KEY, v.firstExport(v.wallets().single()))

        // A Solana key's wallet gets a new EVM key the same way.
        val sol = v.importKey("Sol only", phrase(0, "solSecretBase58"))
        val withEvm = v.createSide(sol.id)
        assertTrue(Evm.isAddress(withEvm.evm!!))
        assertEquals(sol.sol, withEvm.sol)
        assertEquals(withEvm.evm!!.lowercase(), Secp256k1.recoverAddress(hash, v.signEvm(sol.id, withEvm.evm!!, hash))?.lowercase())
        assertEquals(phrase(0, "solSecretBase58"), v.export(password, sol.id, ExportKind.SOL_KEY))
        val evmKey = Evm.parsePrivateKey(v.export(password, sol.id, ExportKind.EVM_KEY))!!
        assertEquals(withEvm.evm, Evm.address(evmKey))
        evmKey.fill(0)

        // It is in the file: another start of the app has both, and still signs with them.
        val again = vault().apply { unlock(password) }
        assertEquals(listOf(both.sol, withEvm.sol), again.wallets().map { it.sol })
        assertEquals(64, again.signSol(both.id, both.sol!!, ByteArray(10)).size)
        again.lock()
        refused(VaultError.LOCKED) { again.createSide(both.id) }
    }

    @Test
    fun theSideItLacksCanBeTheUsersOwnKeyOrPhrase() {
        val v = vault().apply { create(password) }
        // A Solana key's wallet takes an EVM key for its EVM side, and nothing else.
        val sol = v.importKey("Sol", phrase(0, "solSecretBase58"))
        refused(VaultError.BAD_KEY) { v.importSide(sol.id, phrase(1, "solSecretBase58")) }
        refused(VaultError.BAD_KEY) { v.importSide(sol.id, "0x1234") }
        refused(VaultError.BAD_KEY) { v.importSide(sol.id, "abandon abandon abandon") }
        val key = sampleKey.getValue("privateKey").jsonPrimitive.content
        val both = v.importSide(sol.id, key)
        assertEquals(sampleKey.getValue("address").jsonPrimitive.content, both.evm)
        assertEquals(phrase(0, "solAddress"), both.sol)
        // The user's own key: backed up already.
        assertTrue(both.backedUp)
        assertEquals(key, v.export(password, sol.id, ExportKind.EVM_KEY))
        assertEquals(phrase(0, "solSecretBase58"), v.export(password, sol.id, ExportKind.SOL_KEY))
        // Its address is taken now: a wallet of that key alone would be the same wallet twice.
        refused(VaultError.DUPLICATE) { v.importKey("Again", key) }

        // An EVM key's wallet takes a phrase for its Solana side: the phrase's first Solana
        // address, whose key alone is kept.
        val evm = v.importKey("EVM", phrase(2, "evmPrivateKey"))
        val fromPhrase = v.importSide(evm.id, phrase(3, "mnemonic"))
        assertEquals(phrase(2, "evmAddress"), fromPhrase.evm)
        assertEquals(phrase(3, "solAddress"), fromPhrase.sol)
        assertEquals(phrase(3, "solSecretBase58"), v.export(password, evm.id, ExportKind.SOL_KEY))
        refused(VaultError.NO_SUCH_KEY) { v.export(password, evm.id, ExportKind.PHRASE) }

        // An address another wallet has is refused, and the wallet stays as it was.
        v.importPhrase("Phrase", phrase(1, "mnemonic"))
        val lone = v.importKey("Lone", phrase(0, "evmPrivateKey"))
        refused(VaultError.DUPLICATE) { v.importSide(lone.id, phrase(1, "solSecretBase58")) }
        refused(VaultError.DUPLICATE) { v.importSide(lone.id, phrase(3, "mnemonic")) }
        assertNull(v.wallets().first { it.id == lone.id }.sol)
        assertEquals(4, v.wallets().size)
    }

    @Test
    fun aFingerprintUnlockClearsThePasswordsWait() {
        val v = vault().apply { create(password) }
        val kept = v.keyForFingerprint(password)
        v.lock()
        repeat(Vault.FREE_TRIES) { refused(VaultError.BAD_PASSWORD) { v.unlock("Wrong-Horse-9") } }
        refused(VaultError.TOO_MANY_ATTEMPTS) { v.unlock(password) }
        // The phone limits fingerprint tries itself; a finger that opens the vault ends the wait.
        v.unlockWithKey(kept)
        assertNull(store.fails)
    }
}
