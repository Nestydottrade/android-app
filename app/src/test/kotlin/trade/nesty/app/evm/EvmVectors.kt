package trade.nesty.app.evm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import trade.nesty.app.Fixtures
import java.math.BigInteger

/** The vectors the TypeScript engine wrote (android/tools/vectors), read as JSON. */
object EvmVectors {
    private val cache = mutableMapOf<String, JsonObject>()

    fun load(name: String): JsonObject =
        cache.getOrPut(name) { Json.parseToJsonElement(Fixtures.text("evm/$name")).jsonObject }

    /** A JSON value of the vectors as the ABI codec takes it, guided by its type. */
    fun abiValue(type: AbiType, e: JsonElement): Any? = when (type) {
        is AbiType.UInt, is AbiType.SInt -> BigInteger(e.jsonPrimitive.content)
        is AbiType.Address -> e.jsonPrimitive.content
        is AbiType.Bool -> e.jsonPrimitive.booleanOrNull ?: error("not a bool: $e")
        is AbiType.FixedBytes, is AbiType.Bytes -> e.jsonPrimitive.content.hexBytes()
        is AbiType.Str -> e.jsonPrimitive.content
        is AbiType.Array -> e.jsonArray.map { abiValue(type.item, it) }
        is AbiType.Tuple -> when (e) {
            is JsonObject -> type.items.mapIndexed { i, t -> abiValue(t, e.getValue(type.names[i] ?: error("unnamed"))) }
            is JsonArray -> type.items.mapIndexed { i, t -> abiValue(t, e[i]) }
            else -> error("not a tuple: $e")
        }
    }
}

fun JsonElement.str(): String = jsonPrimitive.content
fun JsonElement.big(): BigInteger = BigInteger(jsonPrimitive.content)
fun JsonElement.long(): Long = jsonPrimitive.content.toLong()
fun JsonElement.int(): Int = jsonPrimitive.content.toInt()
fun JsonElement.bool(): Boolean = jsonPrimitive.booleanOrNull ?: error("not a bool: $this")
fun JsonElement.isNull(): Boolean = this is JsonNull
fun JsonObject.obj(k: String): JsonObject = getValue(k).jsonObject
fun JsonObject.arr(k: String): JsonArray = getValue(k).jsonArray
fun JsonObject.s(k: String): String = getValue(k).str()
fun JsonObject.opt(k: String): JsonElement? = this[k]?.takeIf { it !is JsonNull }
fun JsonPrimitive.text(): String = content
