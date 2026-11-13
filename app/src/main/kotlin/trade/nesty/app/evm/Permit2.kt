package trade.nesty.app.evm

import java.math.BigInteger
import java.security.SecureRandom

/** A Permit2 allowance permit (Universal Router sells). */
data class PermitSingle(
    val token: String,
    val amount: BigInteger,
    val expiration: Long,
    val nonce: Long,
    val spender: String,
    val sigDeadline: BigInteger,
)

/** A Permit2 one-time transfer (Nesty Router sells). */
data class TransferPermit(
    val token: String,
    val amount: BigInteger,
    val nonce: BigInteger,
    val deadline: BigInteger,
)

object Permit2 {
    const val ADDRESS = "0x000000000022d473030f116ddee9f6b43ac78ba3"

    /** A permit covers exactly one sell: short on purpose. */
    const val PERMIT_TTL_S = 30 * 60L
    const val TRANSFER_TTL_S = 10 * 60L

    val allowance = AbiFunction(
        "function allowance(address owner, address token, address spender) view returns (uint160 amount, uint48 expiration, uint48 nonce)",
    )

    private val singleTypes = mapOf(
        "PermitSingle" to listOf("details" to "PermitDetails", "spender" to "address", "sigDeadline" to "uint256"),
        "PermitDetails" to listOf("token" to "address", "amount" to "uint160", "expiration" to "uint48", "nonce" to "uint48"),
    )
    private val transferTypes = mapOf(
        "PermitTransferFrom" to listOf(
            "permitted" to "TokenPermissions",
            "spender" to "address",
            "nonce" to "uint256",
            "deadline" to "uint256",
        ),
        "TokenPermissions" to listOf("token" to "address", "amount" to "uint256"),
    )

    private fun domain(chainId: Long) = mapOf("name" to "Permit2", "chainId" to BigInteger.valueOf(chainId), "verifyingContract" to ADDRESS)

    fun build(token: String, spender: String, amount: BigInteger, nonce: Long, nowS: Long) = PermitSingle(
        token = token,
        amount = amount,
        expiration = nowS + PERMIT_TTL_S,
        nonce = nonce,
        spender = spender,
        sigDeadline = BigInteger.valueOf(nowS + PERMIT_TTL_S),
    )

    /** Permit2 nonces are unordered (a bitmap): a random 31-byte one never collides in practice. */
    fun buildTransfer(
        token: String,
        amount: BigInteger,
        nowS: Long,
        random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
    ) = TransferPermit(token, amount, BigInteger(1, random(31)), BigInteger.valueOf(nowS + TRANSFER_TTL_S))

    fun single(chainId: Long, p: PermitSingle) = TypedData(
        singleTypes,
        "PermitSingle",
        domain(chainId),
        mapOf(
            "details" to mapOf(
                "token" to p.token,
                "amount" to p.amount,
                "expiration" to BigInteger.valueOf(p.expiration),
                "nonce" to BigInteger.valueOf(p.nonce),
            ),
            "spender" to p.spender,
            "sigDeadline" to p.sigDeadline,
        ),
    )

    fun transfer(chainId: Long, p: TransferPermit, spender: String) = TypedData(
        transferTypes,
        "PermitTransferFrom",
        domain(chainId),
        mapOf(
            "permitted" to mapOf("token" to p.token, "amount" to p.amount),
            "spender" to spender,
            "nonce" to p.nonce,
            "deadline" to p.deadline,
        ),
    )
}
