package trade.nesty.app.evm

import org.bouncycastle.asn1.x9.X9IntegerConverter
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import trade.nesty.app.i18n.tr
import trade.nesty.app.keys.keccak256
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** RLP, as Ethereum writes transactions. Items are ByteArray or List of items. */
object Rlp {
    fun encode(item: Any): ByteArray = when (item) {
        is ByteArray -> bytes(item)
        is List<*> -> {
            val body = ByteArrayOutputStream()
            for (x in item) body.write(encode(x ?: ByteArray(0)))
            prefix(body.toByteArray(), 0xc0)
        }
        else -> throw IllegalArgumentException("RLP takes bytes or lists")
    }

    private fun bytes(b: ByteArray): ByteArray =
        if (b.size == 1 && (b[0].toInt() and 0xff) < 0x80) b else prefix(b, 0x80)

    private fun prefix(body: ByteArray, base: Int): ByteArray {
        if (body.size < 56) return byteArrayOf((base + body.size).toByte()) + body
        val len = minimal(BigInteger.valueOf(body.size.toLong()))
        return byteArrayOf((base + 55 + len.size).toByte()) + len + body
    }

    /** An integer as RLP keeps it: big-endian, no leading zeros, zero as no bytes. */
    fun minimal(n: BigInteger): ByteArray {
        require(n.signum() >= 0) { "negative" }
        if (n.signum() == 0) return ByteArray(0)
        val raw = n.toByteArray()
        return if (raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
    }

    fun int(n: Long): ByteArray = minimal(BigInteger.valueOf(n))
}

/** A secp256k1 signature with its recovery id (0 or 1). */
class EcSignature(val r: BigInteger, val s: BigInteger, val recId: Int) {
    /** 65 bytes r ‖ s ‖ v with v = 27 + recId, as wallets sign messages. */
    fun toBytes65(): ByteArray = Abi.word(r) + Abi.word(s) + byteArrayOf((27 + recId).toByte())
}

/**
 * Signing with RFC 6979 deterministic nonces and low s, as viem (noble) signs: the same key
 * and hash always give the same signature.
 */
object Secp256k1 {
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
    private val N: BigInteger = curve.n
    private val HALF_N: BigInteger = N.shiftRight(1)

    fun sign(hash: ByteArray, privateKey: ByteArray): EcSignature {
        require(hash.size == 32) { "hash must be 32 bytes" }
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < N) { "Not a key" }
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val (r, sRaw) = signer.generateSignature(hash).let { it[0] to it[1] }
        val s = if (sRaw > HALF_N) N.subtract(sRaw) else sRaw
        val pub = FixedPointCombMultiplier().multiply(curve.g, d).normalize()
        for (rec in 0..1) {
            val q = recover(hash, r, s, rec)
            if (q != null && q == pub) return EcSignature(r, s, rec)
        }
        throw IllegalStateException("no recovery id")
    }

    /** The public key that made `(r, s)` over `hash`, for recovery id `rec` (0 or 1). */
    fun recover(hash: ByteArray, r: BigInteger, s: BigInteger, rec: Int): ECPoint? {
        if (r.signum() <= 0 || r >= N || s.signum() <= 0 || s >= N) return null
        val x = r // recovery ids 2 and 3 (x ≥ n) never occur in practice
        val conv = X9IntegerConverter()
        val enc = conv.integerToBytes(x, 1 + conv.getByteLength(curve.curve))
        enc[0] = (if (rec and 1 == 1) 0x03 else 0x02).toByte()
        val point = runCatching { curve.curve.decodePoint(enc) }.getOrNull() ?: return null
        if (!point.multiply(N).isInfinity) return null
        val e = BigInteger(1, hash)
        val rInv = r.modInverse(N)
        val u1 = e.negate().mod(N).multiply(rInv).mod(N)
        val u2 = s.multiply(rInv).mod(N)
        return ECAlgorithms.sumOfTwoMultiplies(curve.g, u1, point, u2).normalize()
    }

    /** The address that signed `hash`, for checks. */
    fun recoverAddress(hash: ByteArray, sig: EcSignature): String? {
        val q = recover(hash, sig.r, sig.s, sig.recId) ?: return null
        val pub = q.getEncoded(false)
        return "0x" + trade.nesty.app.keys.Hex.encode(keccak256(pub.copyOfRange(1, 65)).copyOfRange(12, 32))
    }
}

/**
 * Signs 32-byte hashes for one EVM address. The key stays where it is kept (the vault opens it
 * for one signature and wipes it); only the signature comes out.
 */
interface EvmSigner {
    val address: String
    fun signHash(hash: ByteArray): EcSignature
}

/** An EIP-1559 (type 2) transaction, as the engine signs it. No access list. */
data class Tx1559(
    val chainId: Long,
    val nonce: Long,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val gas: BigInteger,
    val to: String,
    val value: BigInteger,
    val data: ByteArray,
) {
    private fun fields(): List<Any> = listOf(
        Rlp.int(chainId),
        Rlp.int(nonce),
        Rlp.minimal(maxPriorityFeePerGas),
        Rlp.minimal(maxFeePerGas),
        Rlp.minimal(gas),
        to.hexBytes(),
        Rlp.minimal(value),
        data,
        emptyList<Any>(),
    )

    fun signingHash(): ByteArray = keccak256(byteArrayOf(0x02) + Rlp.encode(fields()))

    /** The raw signed transaction, ready to send. */
    fun sign(privateKey: ByteArray): ByteArray = sign { hash -> Secp256k1.sign(hash, privateKey) }

    /** Signed by whoever holds the key: `signHash` gets the 32-byte signing hash only. */
    fun sign(signHash: (ByteArray) -> EcSignature): ByteArray {
        val sig = signHash(signingHash())
        return byteArrayOf(0x02) + Rlp.encode(
            fields() + listOf(Rlp.int(sig.recId.toLong()), Rlp.minimal(sig.r), Rlp.minimal(sig.s)),
        )
    }

    override fun equals(other: Any?): Boolean =
        other is Tx1559 && chainId == other.chainId && nonce == other.nonce &&
            maxPriorityFeePerGas == other.maxPriorityFeePerGas && maxFeePerGas == other.maxFeePerGas &&
            gas == other.gas && to.equals(other.to, ignoreCase = true) && value == other.value &&
            data.contentEquals(other.data)

    override fun hashCode(): Int = (chainId * 31 + nonce).hashCode()
}

/**
 * EIP-712 typed data: structs of atomic fields and nested structs (all Permit2 needs).
 * `types` maps a struct name to its fields in order; values are Maps keyed by field name.
 */
class TypedData(
    val types: Map<String, List<Pair<String, String>>>,
    val primaryType: String,
    val domain: Map<String, Any?>,
    val message: Map<String, Any?>,
) {
    fun digest(): ByteArray {
        val domainFields = buildList {
            if ("name" in domain) add("name" to "string")
            if ("version" in domain) add("version" to "string")
            if ("chainId" in domain) add("chainId" to "uint256")
            if ("verifyingContract" in domain) add("verifyingContract" to "address")
            if ("salt" in domain) add("salt" to "bytes32")
        }
        val all = types + ("EIP712Domain" to domainFields)
        val domainHash = hashStruct(all, "EIP712Domain", domain)
        val msgHash = hashStruct(all, primaryType, message)
        return keccak256(byteArrayOf(0x19, 0x01) + domainHash + msgHash)
    }

    fun sign(privateKey: ByteArray): ByteArray = Secp256k1.sign(digest(), privateKey).toBytes65()

    fun sign(signHash: (ByteArray) -> EcSignature): ByteArray = signHash(digest()).toBytes65()

    companion object {
        private fun deps(types: Map<String, List<Pair<String, String>>>, name: String, into: MutableSet<String>) {
            if (name in into || name !in types) return
            into += name
            for ((_, t) in types.getValue(name)) deps(types, t.substringBefore('['), into)
        }

        fun encodeType(types: Map<String, List<Pair<String, String>>>, name: String): String {
            val found = linkedSetOf<String>()
            deps(types, name, found)
            val rest = (found - name).sorted()
            return (listOf(name) + rest).joinToString("") { n ->
                types.getValue(n).joinToString(",", "$n(", ")") { (f, t) -> "$t $f" }
            }
        }

        fun hashStruct(types: Map<String, List<Pair<String, String>>>, name: String, value: Map<String, Any?>): ByteArray {
            val typeHash = keccak256(encodeType(types, name).toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            out.write(typeHash)
            for ((field, type) in types.getValue(name)) {
                val v = value[field]
                when {
                    type in types -> {
                        @Suppress("UNCHECKED_CAST")
                        out.write(hashStruct(types, type, v as Map<String, Any?>))
                    }
                    type == "string" -> out.write(keccak256((v as String).toByteArray(Charsets.UTF_8)))
                    type == "bytes" -> out.write(keccak256(if (v is ByteArray) v else (v as String).hexBytes()))
                    else -> out.write(Abi.encode(Abi.params(type), listOf(v)))
                }
            }
            return keccak256(out.toByteArray())
        }
    }
}
