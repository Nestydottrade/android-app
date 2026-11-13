package trade.nesty.app.keys

/** Best-effort wipe: the JVM gives no guarantee, but it shortens the life of a copy. */
fun ByteArray?.wipe() {
    this?.fill(0)
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for ((i, b) in bytes.withIndex()) {
            out[i * 2] = DIGITS[(b.toInt() shr 4) and 0xf]
            out[i * 2 + 1] = DIGITS[b.toInt() and 0xf]
        }
        return String(out)
    }

    /** Null for anything that is not an even run of hex digits. */
    fun decode(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                out.wipe()
                return null
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Base58 as Bitcoin and Solana write it. Works on byte arrays only, so a key can be wiped after. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { for ((i, c) in ALPHABET.withIndex()) it[c.code] = i }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        val work = input.copyOf()
        val out = CharArray(input.size * 2)
        var at = out.size
        var start = zeros
        while (start < work.size) {
            // Divide the number in `work` by 58 in place; the remainder is the next digit.
            var remainder = 0
            for (i in start until work.size) {
                val digit = (work[i].toInt() and 0xff) + remainder * 256
                work[i] = (digit / 58).toByte()
                remainder = digit % 58
            }
            out[--at] = ALPHABET[remainder]
            if (work[start].toInt() == 0) start++
        }
        while (at < out.size && out[at] == ALPHABET[0]) at++
        repeat(zeros) { out[--at] = ALPHABET[0] }
        work.wipe()
        return String(out, at, out.size - at).also { out.fill('\u0000') }
    }

    /** Null for a character outside the alphabet. */
    fun decode(input: String): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        val digits = ByteArray(input.length)
        for ((i, c) in input.withIndex()) {
            val d = if (c.code < 128) INDEX[c.code] else -1
            if (d < 0) {
                digits.wipe()
                return null
            }
            digits[i] = d.toByte()
        }
        var zeros = 0
        while (zeros < digits.size && digits[zeros].toInt() == 0) zeros++
        val out = ByteArray(input.length)
        var at = out.size
        var start = zeros
        while (start < digits.size) {
            var remainder = 0
            for (i in start until digits.size) {
                val digit = digits[i].toInt() + remainder * 58
                digits[i] = (digit / 256).toByte()
                remainder = digit % 256
            }
            out[--at] = remainder.toByte()
            if (digits[start].toInt() == 0) start++
        }
        while (at < out.size && out[at].toInt() == 0) at++
        val result = ByteArray(zeros + out.size - at)
        System.arraycopy(out, at, result, zeros, out.size - at)
        digits.wipe()
        out.wipe()
        return result
    }
}
