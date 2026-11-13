package trade.nesty.app.evm

import trade.nesty.app.keys.Hex
import trade.nesty.app.keys.keccak256
import java.math.BigInteger

/**
 * Solidity ABI encoding and decoding, written to give the bytes viem gives (the extension's
 * engine is the reference, android/tools/vectors). Values are plain Kotlin:
 * integers BigInteger (Int and Long accepted when encoding), addresses "0x…" strings, bool
 * Boolean, bytes and bytesN ByteArray, string String, arrays and tuples List.
 *
 * Decoding is as lenient as viem's: an address is the low 20 bytes of its word, an integer the
 * whole word, signed integers two's complement over 256 bits. A bool must be 0 or 1.
 */
sealed class AbiType {
    abstract val dynamic: Boolean
    abstract val canonical: String

    /** Bytes in the head: 32 for a word or an offset, more for a static tuple or array. */
    open val headSize: Int get() = 32

    data class UInt(val bits: kotlin.Int) : AbiType() {
        override val dynamic = false
        override val canonical get() = "uint$bits"
    }

    data class SInt(val bits: kotlin.Int) : AbiType() {
        override val dynamic = false
        override val canonical get() = "int$bits"
    }

    data object Address : AbiType() {
        override val dynamic = false
        override val canonical = "address"
    }

    data object Bool : AbiType() {
        override val dynamic = false
        override val canonical = "bool"
    }

    data class FixedBytes(val size: kotlin.Int) : AbiType() {
        override val dynamic = false
        override val canonical get() = "bytes$size"
    }

    data object Bytes : AbiType() {
        override val dynamic = true
        override val canonical = "bytes"
    }

    data object Str : AbiType() {
        override val dynamic = true
        override val canonical = "string"
    }

    /** `length` null: a dynamic array `T[]`. */
    data class Array(val item: AbiType, val length: kotlin.Int?) : AbiType() {
        override val dynamic get() = length == null || item.dynamic
        override val canonical get() = "${item.canonical}[${length ?: ""}]"
        override val headSize get() = if (dynamic) 32 else item.headSize * (length ?: 0)
    }

    data class Tuple(val items: List<AbiType>, val names: List<String?>) : AbiType() {
        override val dynamic get() = items.any { it.dynamic }
        override val canonical get() = items.joinToString(",", "(", ")") { it.canonical }
        override val headSize get() = if (dynamic) 32 else items.sumOf { it.headSize }
    }
}

class AbiException(message: String) : Exception(message)

object Abi {
    private val WORD = 32
    private val TWO_256: BigInteger = BigInteger.ONE.shiftLeft(256)
    private val MAX_I256: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)

    // ── type strings ────────────────────────────────────────────────────────

    /** "address a, (uint256 b, bytes c)[] d, uint256" → a tuple of the listed parameters. */
    fun params(spec: String): AbiType.Tuple {
        val p = Parser(spec)
        val t = p.list(end = null)
        p.done()
        return t
    }

    fun type(spec: String): AbiType = params(spec).items.single()

    private class Parser(val s: String) {
        var i = 0

        fun done() {
            skip()
            if (i != s.length) throw AbiException("bad type at ${s.substring(i)}")
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun list(end: Char?): AbiType.Tuple {
            val items = mutableListOf<AbiType>()
            val names = mutableListOf<String?>()
            skip()
            if (end == null && i >= s.length) return AbiType.Tuple(items, names)
            if (end != null && i < s.length && s[i] == end) {
                i++
                return AbiType.Tuple(items, names)
            }
            while (true) {
                skip()
                items += one()
                skip()
                var name: String? = null
                val word = ident()
                if (word == "indexed") {
                    skip()
                    name = ident().ifEmpty { null }
                } else if (word.isNotEmpty()) name = word
                names += name
                skip()
                if (i >= s.length) {
                    if (end != null) throw AbiException("unclosed tuple")
                    break
                }
                when (s[i]) {
                    ',' -> i++
                    end -> {
                        i++
                        break
                    }
                    else -> throw AbiException("bad type at ${s.substring(i)}")
                }
            }
            return AbiType.Tuple(items, names)
        }

        private fun ident(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
            return s.substring(start, i)
        }

        private fun one(): AbiType {
            skip()
            var t: AbiType = if (i < s.length && s[i] == '(') {
                i++
                list(')')
            } else {
                val word = ident()
                if (word == "tuple" && i < s.length && s[i] == '(') {
                    i++
                    list(')')
                } else base(word)
            }
            while (i < s.length && s[i] == '[') {
                i++
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                val len = s.substring(start, i).takeIf { it.isNotEmpty() }?.toInt()
                if (i >= s.length || s[i] != ']') throw AbiException("bad array")
                i++
                t = AbiType.Array(t, len)
            }
            return t
        }

        private fun base(word: String): AbiType = when {
            word == "address" -> AbiType.Address
            word == "bool" -> AbiType.Bool
            word == "string" -> AbiType.Str
            word == "bytes" -> AbiType.Bytes
            word == "uint" -> AbiType.UInt(256)
            word == "int" -> AbiType.SInt(256)
            word.startsWith("uint") -> AbiType.UInt(bits(word.substring(4)))
            word.startsWith("int") -> AbiType.SInt(bits(word.substring(3)))
            word.startsWith("bytes") -> {
                val n = word.substring(5).toIntOrNull() ?: throw AbiException("bad type $word")
                if (n !in 1..32) throw AbiException("bad type $word")
                AbiType.FixedBytes(n)
            }
            else -> throw AbiException("unknown type '$word'")
        }

        private fun bits(n: String): kotlin.Int {
            val b = n.toIntOrNull() ?: throw AbiException("bad integer type")
            if (b !in 8..256 || b % 8 != 0) throw AbiException("bad integer type")
            return b
        }
    }

    // ── encoding ────────────────────────────────────────────────────────────

    fun encode(types: AbiType.Tuple, values: List<Any?>): ByteArray = encodeTuple(types, values)

    fun encode(spec: String, vararg values: Any?): ByteArray = encode(params(spec), values.toList())

    private fun encodeTuple(t: AbiType.Tuple, values: List<Any?>): ByteArray {
        if (values.size != t.items.size) throw AbiException("expected ${t.items.size} values, got ${values.size}")
        return encodeSequence(t.items, values)
    }

    private fun encodeSequence(types: List<AbiType>, values: List<Any?>): ByteArray {
        val heads = ArrayList<ByteArray?>(types.size)
        val tails = ArrayList<ByteArray>(types.size)
        var headLen = 0
        for ((k, type) in types.withIndex()) {
            if (type.dynamic) {
                heads += null
                tails += encodeValue(type, values[k])
                headLen += WORD
            } else {
                val h = encodeValue(type, values[k])
                heads += h
                tails += ByteArray(0)
                headLen += h.size
            }
        }
        val out = java.io.ByteArrayOutputStream()
        var offset = headLen
        for (k in types.indices) {
            val h = heads[k]
            if (h == null) {
                out.write(word(BigInteger.valueOf(offset.toLong())))
                offset += tails[k].size
            } else out.write(h)
        }
        for (tail in tails) out.write(tail)
        return out.toByteArray()
    }

    private fun encodeValue(type: AbiType, v: Any?): ByteArray = when (type) {
        is AbiType.UInt -> {
            val n = int(v)
            if (n.signum() < 0 || n.bitLength() > type.bits) throw AbiException("$n does not fit ${type.canonical}")
            word(n)
        }
        is AbiType.SInt -> {
            val n = int(v)
            val limit = BigInteger.ONE.shiftLeft(type.bits - 1)
            if (n >= limit || n < limit.negate()) throw AbiException("$n does not fit ${type.canonical}")
            word(if (n.signum() < 0) n.add(TWO_256) else n)
        }
        is AbiType.Address -> {
            val b = address(v)
            ByteArray(12) + b
        }
        is AbiType.Bool -> word(if (v as Boolean) BigInteger.ONE else BigInteger.ZERO)
        is AbiType.FixedBytes -> {
            val b = bytes(v)
            if (b.size != type.size) throw AbiException("bytes${type.size} needs ${type.size} bytes")
            b + ByteArray(WORD - b.size)
        }
        is AbiType.Bytes -> dynamicBytes(bytes(v))
        is AbiType.Str -> dynamicBytes((v as String).toByteArray(Charsets.UTF_8))
        is AbiType.Array -> {
            val list = list(v)
            if (type.length != null) {
                if (list.size != type.length) throw AbiException("array needs ${type.length} items")
                encodeSequence(List(list.size) { type.item }, list)
            } else {
                word(BigInteger.valueOf(list.size.toLong())) + encodeSequence(List(list.size) { type.item }, list)
            }
        }
        is AbiType.Tuple -> encodeTuple(type, list(v))
    }

    private fun dynamicBytes(b: ByteArray): ByteArray {
        val padded = (b.size + WORD - 1) / WORD * WORD
        return word(BigInteger.valueOf(b.size.toLong())) + b + ByteArray(padded - b.size)
    }

    fun word(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        val trimmed = if (raw.size > WORD) raw.copyOfRange(raw.size - WORD, raw.size) else raw
        return ByteArray(WORD - trimmed.size) + trimmed
    }

    private fun int(v: Any?): BigInteger = when (v) {
        is BigInteger -> v
        is Long -> BigInteger.valueOf(v)
        is kotlin.Int -> BigInteger.valueOf(v.toLong())
        is String -> if (v.startsWith("0x")) BigInteger(v.substring(2).ifEmpty { "0" }, 16) else BigInteger(v)
        else -> throw AbiException("not an integer: $v")
    }

    private fun address(v: Any?): ByteArray {
        val s = (v as? String) ?: throw AbiException("not an address: $v")
        val hex = s.removePrefix("0x").removePrefix("0X")
        if (hex.length != 40) throw AbiException("not an address: $s")
        return Hex.decode(hex) ?: throw AbiException("not an address: $s")
    }

    private fun bytes(v: Any?): ByteArray = when (v) {
        is ByteArray -> v
        is String -> hex(v)
        else -> throw AbiException("not bytes: $v")
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Any?> = when (v) {
        is List<*> -> v as List<Any?>
        is kotlin.Array<*> -> v.toList()
        else -> throw AbiException("not a list: $v")
    }

    // ── decoding ────────────────────────────────────────────────────────────

    fun decode(types: AbiType.Tuple, data: ByteArray): List<Any?> = decodeSequence(types.items, data, 0)

    fun decode(spec: String, data: ByteArray): List<Any?> = decode(params(spec), data)

    private fun decodeSequence(types: List<AbiType>, data: ByteArray, base: kotlin.Int): List<Any?> {
        var at = base
        val out = ArrayList<Any?>(types.size)
        for (t in types) {
            if (t.dynamic) {
                val offset = readSize(data, at)
                out += decodeValue(t, data, base + offset)
                at += WORD
            } else {
                out += decodeValue(t, data, at)
                at += t.headSize
            }
        }
        return out
    }

    private fun decodeValue(type: AbiType, data: ByteArray, at: kotlin.Int): Any? = when (type) {
        is AbiType.UInt -> readWord(data, at)
        is AbiType.SInt -> readWord(data, at).let { if (it > MAX_I256) it.subtract(TWO_256) else it }
        is AbiType.Address -> {
            need(data, at, WORD)
            "0x" + Hex.encode(data.copyOfRange(at + 12, at + WORD))
        }
        is AbiType.Bool -> when (readWord(data, at)) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> throw AbiException("bad bool")
        }
        is AbiType.FixedBytes -> {
            need(data, at, WORD)
            data.copyOfRange(at, at + type.size)
        }
        is AbiType.Bytes -> readBytes(data, at)
        is AbiType.Str -> String(readBytes(data, at), Charsets.UTF_8)
        is AbiType.Array -> {
            if (type.length != null) decodeSequence(List(type.length) { type.item }, data, at)
            else {
                val n = readSize(data, at)
                if (n > (data.size - at) / 1) throw AbiException("array too long")
                decodeSequence(List(n) { type.item }, data, at + WORD)
            }
        }
        is AbiType.Tuple -> decodeSequence(type.items, data, at)
    }

    private fun need(data: ByteArray, at: kotlin.Int, n: kotlin.Int) {
        if (at < 0 || at + n > data.size) throw AbiException("data too short")
    }

    private fun readWord(data: ByteArray, at: kotlin.Int): BigInteger {
        need(data, at, WORD)
        return BigInteger(1, data.copyOfRange(at, at + WORD))
    }

    private fun readSize(data: ByteArray, at: kotlin.Int): kotlin.Int {
        val n = readWord(data, at)
        if (n.bitLength() > 31) throw AbiException("size out of range")
        return n.toInt()
    }

    private fun readBytes(data: ByteArray, at: kotlin.Int): ByteArray {
        val n = readSize(data, at)
        need(data, at + WORD, n)
        return data.copyOfRange(at + WORD, at + WORD + n)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    fun hex(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X")
        return Hex.decode(h) ?: throw AbiException("not hex: ${s.take(20)}")
    }
}

/** "0x…" for bytes. */
fun ByteArray.toHex(): String = "0x" + Hex.encode(this)

/** Bytes of a "0x…" string. */
fun String.hexBytes(): ByteArray = Abi.hex(this)

/**
 * A contract function from its human-readable signature, as viem's parseAbi takes it:
 * `function name(type a, …) view returns (type, …)`.
 */
class AbiFunction(signature: String) {
    val name: String
    val inputs: AbiType.Tuple
    val outputs: AbiType.Tuple
    val selector: ByteArray

    init {
        var s = signature.trim().removePrefix("function").trim()
        val open = s.indexOf('(')
        name = s.substring(0, open).trim()
        // The input list ends at the parenthesis that closes the first one.
        var depth = 0
        var close = -1
        for (k in open until s.length) {
            when (s[k]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        close = k
                        break
                    }
                }
            }
        }
        inputs = Abi.params(s.substring(open + 1, close))
        s = s.substring(close + 1)
        val ret = s.indexOf("returns")
        outputs = if (ret >= 0) {
            val r = s.substring(ret + 7).trim()
            Abi.params(r.removePrefix("(").removeSuffix(")"))
        } else AbiType.Tuple(emptyList(), emptyList())
        val canonical = "$name${inputs.canonical}"
        selector = keccak256(canonical.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)
    }

    fun encode(vararg args: Any?): ByteArray = selector + Abi.encode(inputs, args.toList())

    fun encodeList(args: List<Any?>): ByteArray = selector + Abi.encode(inputs, args)

    fun decodeResult(data: ByteArray): List<Any?> = Abi.decode(outputs, data)

    /** The arguments of calldata to this function, or null when the selector differs. */
    fun decodeCall(data: ByteArray): List<Any?>? {
        if (data.size < 4 || !data.copyOfRange(0, 4).contentEquals(selector)) return null
        return Abi.decode(inputs, data.copyOfRange(4, data.size))
    }
}
