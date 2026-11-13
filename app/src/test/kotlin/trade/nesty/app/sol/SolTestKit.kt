package trade.nesty.app.sol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import trade.nesty.app.Fixtures
import trade.nesty.app.evm.big
import trade.nesty.app.evm.bool
import trade.nesty.app.evm.str
import trade.nesty.app.keys.Base58
import trade.nesty.app.keys.Hex
import trade.nesty.app.keys.Sol
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The vectors the extension's Solana engine wrote (android/tools/vectors), read as JSON. */
object SolVectors {
    private val cache = mutableMapOf<String, JsonObject>()

    fun load(name: String): JsonObject = cache.getOrPut(name) { Json.parseToJsonElement(Fixtures.text("sol/$name")).jsonObject }

    /** MADE-UP seed of the vectors (android/tools/vectors/shared.ts): never funded, never on a chain. */
    val MADE_UP_SEED: ByteArray = checkNotNull(Hex.decode("4c1e9a7b".repeat(8)))
    const val OTHER = "k7FaK87WHGVXzkaoHb7CdVPgkKDQhZ29VLDeBVbDfYn"
    const val STRANGER = "swqrv48gsrwpBFbftEwnP2vB4jckpvfGJfXkwaniLCC"

    fun hex(bytes: ByteArray): String = Hex.encode(bytes)

    fun bytes(hex: String): ByteArray = checkNotNull(Hex.decode(hex)) { "not hex: ${hex.take(20)}" }

    fun instruction(o: JsonObject): Instruction = Instruction(
        o.getValue("programId").str(),
        o.getValue("keys").jsonArray.map { k ->
            val m = k.jsonObject
            AccountMeta(m.getValue("pubkey").str(), m.getValue("isSigner").bool(), m.getValue("isWritable").bool())
        },
        bytes(o.getValue("data").str()),
    )

    fun instructions(e: JsonElement): List<Instruction> = e.jsonArray.map { instruction(it.jsonObject) }
}

/**
 * Answers Solana reads from replay.json: every account the extension's engine read on chain,
 * held still, and the recorder's made-up answers for the wallet, the blockhash and the
 * simulation. A test that trades swaps in its own answers. Nothing reaches a network.
 */
class ReplaySolCall(private val replay: JsonObject) : SolCall {
    private val accounts: JsonObject = replay.getValue("accounts").jsonObject
    private val fake: JsonObject = replay.getValue("fake").jsonObject
    val wallet: String = replay.getValue("wallet").str()

    /** Accounts read that the record does not have: answered as missing, and named here. */
    val missing: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Accounts a test sets, ahead of the record (a token the wallet now holds). */
    val overrides = ConcurrentHashMap<String, JsonElement>()
    val simulations = AtomicInteger()
    val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var lamports: (String) -> Long? = { if (it == wallet) fake.getValue("walletLamports").jsonPrimitive.content.toLong() else null }
    var simulation: () -> JsonElement = {
        buildJsonObject {
            put("err", JsonNull)
            put("logs", JsonArray(emptyList()))
            put("unitsConsumed", fake.getValue("unitsConsumed").jsonPrimitive.content.toLong())
        }
    }
    var height: () -> Long = { fake.getValue("blockHeight").jsonPrimitive.content.toLong() }

    /** The status of each signature asked: null while it has not landed. */
    var status: (String) -> JsonElement = { JsonNull }

    /** What getTransaction answers for a signature. */
    var transaction: (String) -> JsonElement = { JsonNull }

    val lastValid: Long get() = fake.getValue("lastValidBlockHeight").jsonPrimitive.content.toLong()
    val blockhash: String get() = fake.getValue("blockhash").str()

    override suspend fun call(method: String, params: JsonArray): JsonElement {
        methods += method
        val slot = buildJsonObject { put("slot", fake.getValue("slot").jsonPrimitive.content.toLong()) }
        return when (method) {
            "getMultipleAccounts" -> buildJsonObject {
                put("context", slot)
                put("value", buildJsonArray {
                    params[0].jsonArray.forEach { k ->
                        val key = k.str()
                        val hit = overrides[key] ?: accounts[key]
                        if (hit == null) missing += key
                        add(hit ?: JsonNull)
                    }
                })
            }
            "getBalance" -> buildJsonObject {
                put("context", slot)
                put("value", lamports(params[0].str()) ?: throw AssertionError("unrecorded balance of ${params[0]}"))
            }
            "getLatestBlockhash" -> buildJsonObject {
                put("context", slot)
                put("value", buildJsonObject {
                    put("blockhash", blockhash)
                    put("lastValidBlockHeight", lastValid)
                })
            }
            "getBlockHeight" -> JsonPrimitive(height())
            "simulateTransaction" -> {
                simulations.incrementAndGet()
                buildJsonObject {
                    put("context", slot)
                    put("value", simulation())
                }
            }
            "getSignatureStatuses" -> buildJsonObject {
                put("context", slot)
                put("value", buildJsonArray { params[0].jsonArray.forEach { add(status(it.str())) } })
            }
            "getTransaction" -> transaction(params[0].str())
            "getMinimumBalanceForRentExemption" ->
                replay.getValue("minimums").jsonObject[params[0].toString()] ?: throw AssertionError("unrecorded rent for ${params[0]}")
            else -> throw AssertionError("unrecorded $method")
        }
    }

    /** A token account of `owner` holding `amount` (as the recorder makes them). */
    fun tokenAccount(mint: String, owner: String, amount: BigInteger, program: String): JsonElement {
        val d = ByteArray(165)
        SolAddr.addressBytes(mint).copyInto(d, 0)
        SolAddr.addressBytes(owner).copyInto(d, 32)
        var a = amount
        for (i in 0 until 8) {
            d[64 + i] = a.and(BigInteger.valueOf(0xff)).toByte()
            a = a.shiftRight(8)
        }
        d[108] = 1
        return buildJsonObject {
            put("data", buildJsonArray {
                add(JsonPrimitive(java.util.Base64.getEncoder().encodeToString(d)))
                add(JsonPrimitive("base64"))
            })
            put("owner", program)
            put("lamports", 2_039_280)
            put("executable", false)
        }
    }
}

/** Keeps the message it is handed and refuses: the trade stops right before its signature. */
class CaptureSigner(override val address: String) : SolSigner {
    val messages: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    override fun signMessage(message: ByteArray): ByteArray {
        messages += message.copyOf()
        throw IllegalStateException("Not signed: the test keeps the message only.")
    }
}

/** Signs with the vectors' MADE-UP seed, over the made-up blockhash: nothing it signs can land. */
class MadeUpSigner : SolSigner {
    override val address: String = Sol.address(SolVectors.MADE_UP_SEED)
    val signed = AtomicInteger()

    override fun signMessage(message: ByteArray): ByteArray {
        signed.incrementAndGet()
        return Sol.sign(message, SolVectors.MADE_UP_SEED, address)
    }
}

/** Takes every transaction "sent" and keeps it; nothing leaves the test. */
class KeepSender : SolSender {
    val raws: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    override suspend fun send(raw: ByteArray): String {
        raws += raw.copyOf()
        return Base58.encode(raw.copyOfRange(1, 65))
    }

    /** The distinct transactions sent (a rebroadcast is the same bytes). */
    fun distinct(): List<ByteArray> = raws.distinctBy { Hex.encode(it) }
}

/** A clock that moves only when the test moves it. */
class TestClock(start: Long) {
    private val at = AtomicLong(start)

    fun now(): Long = at.get()

    fun advance(ms: Long) {
        at.addAndGet(ms)
    }
}

fun JsonElement.bigOrNull(): BigInteger? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { BigInteger(it.content) }

fun JsonObject.bigs(k: String): List<BigInteger?> = getValue(k).jsonArray.map { if (it is JsonNull) null else it.big() }
