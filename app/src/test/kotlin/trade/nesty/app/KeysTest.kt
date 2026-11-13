package trade.nesty.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.NestyJson
import trade.nesty.app.keys.Base58
import trade.nesty.app.keys.Bip39
import trade.nesty.app.keys.Evm
import trade.nesty.app.keys.Hd
import trade.nesty.app.keys.Hex
import trade.nesty.app.keys.Sol
import trade.nesty.app.vault.KdfParams
import trade.nesty.app.vault.PasswordNeed
import trade.nesty.app.vault.VaultCrypto
import trade.nesty.app.vault.passwordNeeds
import java.security.MessageDigest

/**
 * Key derivation against `keyVectors.json`, written by the libraries the extension's vault uses.
 *
 * Every phrase and key in that file and in this test is PUBLIC TEST DATA: the BIP-39
 * specification's vectors and the phrase development tools ship with. They are known to
 * everyone and guard nothing. No key of a person is in here.
 */
class KeysTest {
    private val vectors = NestyJson.parseToJsonElement(Fixtures.text("keyVectors")).jsonObject
    private fun JsonObject.s(name: String) = getValue(name).jsonPrimitive.content
    private val phrases = vectors.getValue("phrases").jsonArray.map { it.jsonObject }
    private val hardhat = phrases.first { it.s("mnemonic").startsWith("test test") }

    @Test
    fun theWordlistIsTheStandardOne() {
        assertEquals(2048, Bip39.words.size)
        assertEquals("abandon", Bip39.words.first())
        assertEquals("zoo", Bip39.words.last())
        val file = (Bip39.words.joinToString("\n") + "\n").toByteArray()
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", Hex.encode(MessageDigest.getInstance("SHA-256").digest(file)))
    }

    @Test
    fun aPhraseGivesTheSameWalletsAsInTheExtension() {
        assertEquals(4, phrases.size)
        for (v in phrases) {
            val phrase = v.s("mnemonic")
            assertTrue(Bip39.isValid(phrase))
            val seed = Bip39.toSeed(phrase)
            assertEquals(v.s("seed"), Hex.encode(seed))
            val evm = Hd.evmKey(seed)
            assertEquals(v.s("evmPrivateKey"), "0x" + Hex.encode(evm))
            assertEquals(v.s("evmAddress"), Evm.address(evm))
            val sol = Hd.solSeed(seed)
            assertEquals(v.s("solSeed"), Hex.encode(sol))
            assertEquals(v.s("solAddress"), Sol.address(sol))
            assertEquals(v.s("solSecretBase58"), Sol.secretKey(sol))
        }
        // The two everyone knows by heart.
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", hardhat.s("evmAddress"))
        assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", phrases.first().s("evmAddress"))
    }

    @Test
    fun entropyBecomesItsPhrase() {
        for (v in vectors.getValue("entropy").jsonArray.map { it.jsonObject }) {
            val phrase = Bip39.fromEntropy(Hex.decode(v.s("entropy"))!!)
            assertEquals(v.s("mnemonic"), phrase)
            assertTrue(Bip39.isValid(phrase))
        }
    }

    @Test
    fun aNewPhraseIsTwelveValidWordsAndNeverTheSame() {
        val a = Bip39.generate()
        val b = Bip39.generate()
        assertEquals(12, Bip39.wordCount(a))
        assertTrue(Bip39.isValid(a) && Bip39.isValid(b))
        assertNotEquals(a, b)
    }

    @Test
    fun phrasesThatAreRefused() {
        val twelve = List(12) { "abandon" }.joinToString(" ")
        // The checksum word is wrong.
        assertFalse(Bip39.isValid(twelve))
        assertFalse(Bip39.isValid("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
        assertFalse(Bip39.isValid("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon nesty"))
        assertFalse(Bip39.isValid(""))
        // 15 words is a real length elsewhere; the extension takes 12 or 24 only, and so does the app.
        assertFalse(Bip39.isValid("ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic ozone drill grab"))
        // Capitals and stray spaces are the same phrase.
        assertTrue(Bip39.isValid("  Test test TEST test test\ttest test test test test test   junk \n"))
        assertEquals(12, Bip39.wordCount("  Test test TEST test test\ttest test test test test test   junk \n"))
        assertEquals(0, Bip39.wordCount("   "))
    }

    @Test
    fun anEvmKeyAsWalletsExportIt() {
        val v = vectors.getValue("evmKeys").jsonArray.first().jsonObject
        val key = v.s("privateKey")
        for (input in listOf(key, key.removePrefix("0x"), "  " + key.uppercase().replace("0X", "0x") + "\n")) {
            assertEquals(v.s("address"), Evm.address(Evm.parsePrivateKey(input)!!))
        }
        assertNull(Evm.parsePrivateKey(key.dropLast(1)))
        assertNull(Evm.parsePrivateKey(key + "0"))
        assertNull(Evm.parsePrivateKey("0x" + "0".repeat(64)))
        // The curve's order and above are not keys.
        assertNull(Evm.parsePrivateKey("0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"))
        assertNull(Evm.parsePrivateKey("0x" + "g".repeat(64)))
        assertNull(Evm.parsePrivateKey(hardhat.s("mnemonic")))
    }

    @Test
    fun addressesKeepTheirMixedCase() {
        for (a in listOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359", "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB")) {
            assertEquals(a, Evm.checksum(a.lowercase()))
            assertEquals(a, Evm.checksum(a.uppercase().replace("0X", "0x")))
            assertTrue(Evm.isAddress(a))
        }
        assertFalse(Evm.isAddress("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe"))
    }

    @Test
    fun aSolanaKeyAsWalletsExportIt() {
        val v = phrases.first()
        val seed = Hex.decode(v.s("solSeed"))!!
        assertArrayEquals(seed, Sol.parseSecretKey(v.s("solSecretBase58")))
        assertArrayEquals(seed, Sol.parseSecretKey(" " + v.s("solSecretJson") + " "))
        // An address is public: it never becomes a key.
        assertNull(Sol.parseSecretKey(v.s("solAddress")))
        assertTrue(Sol.isAddress(v.s("solAddress")))
        assertFalse(Sol.isAddress(v.s("evmAddress")))
        // The second half has to be the first half's public key.
        val pair = Base58.decode(v.s("solSecretBase58"))!!
        pair[40] = (pair[40].toInt() xor 1).toByte()
        assertNull(Sol.parseSecretKey(Base58.encode(pair)))
        assertNull(Sol.parseSecretKey("[1,2,3]"))
        assertNull(Sol.parseSecretKey(v.s("solSecretJson").replace("[", "[256,").substringBeforeLast(",") + "]"))
        assertNull(Sol.parseSecretKey("not a key 0OIl"))
    }

    @Test
    fun base58() {
        assertEquals("2NEpo7TZRRrLZSi2U", Base58.encode("Hello World!".toByteArray()))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        assertEquals("", Base58.encode(ByteArray(0)))
        assertArrayEquals(byteArrayOf(0, 0, 1), Base58.decode("112"))
        assertArrayEquals("Hello World!".toByteArray(), Base58.decode("2NEpo7TZRRrLZSi2U"))
        assertNull(Base58.decode("0OIl"))
        val bytes = ByteArray(64) { (it * 7 + 3).toByte() }
        assertArrayEquals(bytes, Base58.decode(Base58.encode(bytes)))
    }

    @Test
    fun thePasswordKeyIsTheExtensionsArgon2id() {
        for (v in vectors.getValue("kdf").jsonArray.map { it.jsonObject }) {
            val params = KdfParams(
                memoryKiB = v.getValue("memoryKiB").jsonPrimitive.int,
                iterations = v.getValue("iterations").jsonPrimitive.int,
                parallelism = v.getValue("parallelism").jsonPrimitive.int,
                salt = v.s("salt"),
            )
            assertEquals(v.s("hash"), Hex.encode(VaultCrypto.deriveKey(v.s("password"), params)))
        }
    }

    @Test
    fun whatANewPasswordNeeds() {
        assertEquals(emptyList<PasswordNeed>(), passwordNeeds("Correct-Horse-9"))
        assertEquals(listOf(PasswordNeed.LENGTH), passwordNeeds("Short1Aa"))
        assertEquals(listOf(PasswordNeed.UPPER), passwordNeeds("alllowercase1"))
        assertEquals(listOf(PasswordNeed.LOWER), passwordNeeds("ALLUPPERCASE1"))
        assertEquals(listOf(PasswordNeed.DIGIT), passwordNeeds("NoDigitsHere"))
        assertEquals(PasswordNeed.entries.toList(), passwordNeeds(""))
        // Characters as people count them: nine eggs and a letter are ten, not nineteen.
        assertTrue(PasswordNeed.LENGTH in passwordNeeds("🥚".repeat(4) + "aA1"))
        assertFalse(PasswordNeed.LENGTH in passwordNeeds("🥚".repeat(7) + "aA1"))
    }
}
