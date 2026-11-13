package trade.nesty.app.evm

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * What a transaction does, for its gas: `transfer` moves native, `tokenTransfer` an ERC-20,
 * `deposit` pays native into a contract, `routed` is a Nesty Router call.
 */
enum class GasKind(val wire: String) {
    SWAP("swap"),
    APPROVE("approve"),
    TRANSFER("transfer"),
    TOKEN_TRANSFER("tokenTransfer"),
    DEPOSIT("deposit"),
    ROUTED("routed"),
    ;

    companion object {
        fun of(wire: String) = entries.first { it.wire == wire }
    }
}

data class Fees(val maxFeePerGas: BigInteger, val maxPriorityFeePerGas: BigInteger)

/** The arithmetic of every trade, ported from the extension's fees.ts, core.ts and trading.ts. */
object TradeMath {
    /** A transaction's deadline: ten minutes. */
    const val DEADLINE_S = 10 * 60L

    /** Slippage on the native → asset leg of a funded buy. */
    const val FUNDING_SLIPPAGE_BPS = 100

    /** Least max-fee headroom over the base fee: only the actual base fee (+ tip) is paid. */
    private val MIN_FEE_MULTIPLIER_BPS = big(12_500)

    /** The least gas worth sending unsimulated on an Arbitrum-style chain. */
    val MIN_UNSIMULATED_GAS: BigInteger = big(200_000)

    private val TEN_K = big(10_000)

    /** Minimum output after a known transfer tax and the slippage tolerance. Never 0. */
    fun minOutAfter(out: BigInteger, taxBps: Int, slippageBps: Int): BigInteger {
        val afterTax = out * big(10_000 - minOf(taxBps, 9_999)) / TEN_K
        val min = afterTax * big(10_000 - slippageBps) / TEN_K
        return if (min.signum() > 0) min else BigInteger.ONE
    }

    /** Allowance to grant a sell spender (which the validator still limits to known contracts). */
    fun approvalTarget(unlimited: Boolean, bufferBps: Int, held: BigInteger): BigInteger =
        if (unlimited) BigInteger.ONE.shiftLeft(256) - BigInteger.ONE else held * big(10_000 + bufferBps) / TEN_K

    /** Price impact against a tiny reference trade on the same pool, in basis points. */
    fun priceImpactBps(amountIn: BigInteger, out: BigInteger, refIn: BigInteger, refOut: BigInteger): Int? {
        if (amountIn.signum() <= 0 || refIn.signum() <= 0 || refOut.signum() <= 0) return null
        val ratioBps = (out * refIn * TEN_K / (amountIn * refOut))
        // Number() of a huge ratio is still above 10 000: no impact.
        val r = if (ratioBps > big(Int.MAX_VALUE)) Int.MAX_VALUE else ratioBps.toInt()
        return maxOf(0, 10_000 - r)
    }

    fun computeFees(chain: EvmChain, baseFee: BigInteger, preset: GasPreset): Fees {
        val prio = if (chain.feeModel == FeeModel.ARBITRUM) BigInteger.ZERO else preset.priorityFeeWei
        val setBps = big(Math.round(preset.maxFeeMultiplier * 10_000))
        val multBps = if (setBps > MIN_FEE_MULTIPLIER_BPS) setBps else MIN_FEE_MULTIPLIER_BPS
        var maxFee = baseFee * multBps / TEN_K + prio
        if (maxFee < prio) maxFee = prio
        if (maxFee.signum() == 0) maxFee = BigInteger.ONE
        return Fees(maxFee, prio)
    }

    /** Gas limits used when simulation is off or impossible (an approval still pending). */
    fun staticGas(chain: EvmChain, kind: GasKind): BigInteger {
        val arbitrum = chain.feeModel == FeeModel.ARBITRUM
        if (kind == GasKind.TRANSFER) return big(if (arbitrum) 300_000 else 21_000)
        val small = kind == GasKind.APPROVE || kind == GasKind.TOKEN_TRANSFER || kind == GasKind.DEPOSIT
        if (kind == GasKind.ROUTED) return big(if (arbitrum) 6_000_000 else 1_200_000)
        if (arbitrum) return big(if (small) 1_000_000 else 3_000_000)
        return big(if (small) 90_000 else 500_000)
    }

    /** Typical gas of a transaction sent without simulation, on chains with a mempool. */
    private fun typicalGas(kind: GasKind): BigInteger = big(
        when (kind) {
            GasKind.SWAP -> 250_000
            GasKind.APPROVE -> 60_000
            GasKind.TRANSFER -> 21_000
            GasKind.TOKEN_TRANSFER -> 65_000
            GasKind.DEPOSIT -> 60_000
            GasKind.ROUTED -> 500_000
        },
    )

    /**
     * What `gas` is expected to cost: the use at base fee + tip, not the cap. Simulated limits
     * carry a 30% buffer; static ones are worst cases.
     */
    fun expectedFeeWei(chain: EvmChain, fees: Fees, base: BigInteger?, gas: BigInteger, simulated: Boolean, kind: GasKind): BigInteger {
        val perGas = if (base == null || base + fees.maxPriorityFeePerGas > fees.maxFeePerGas) fees.maxFeePerGas
        else base + fees.maxPriorityFeePerGas
        val units = when {
            simulated -> gas * BigInteger.TEN / big(13)
            chain.feeModel == FeeModel.ARBITRUM -> gas
            else -> gas.min(typicalGas(kind))
        }
        return units * perGas
    }

    /** Why the expected fee is above the gas limit (USD), or null. */
    fun gasFeeRefusal(feeWei: BigInteger, nativeUsd: Double?, maxGasUsd: Double, symbol: String): String? {
        if (nativeUsd == null) return null
        val usd = formatEther(feeWei).toDouble() * nativeUsd
        if (usd <= maxGasUsd) return null
        return "Gas fee about $${String.format(Locale.US, "%.2f", usd)} (${formatToken(feeWei, 18)} $symbol) is above your gas limit of " +
            "$${plainNumber(maxGasUsd)}. Lower the priority fee or raise the limit in Settings."
    }

    /** An on-chain amount, compactly (format.ts formatToken): 1.28M, 0.0001235. */
    fun formatToken(amount: BigInteger, decimals: Int): String = formatCompact(BigDecimal(amount).movePointLeft(decimals).toDouble())

    private val SUFFIX = listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")

    /**
     * format.ts formatCompact: a suffix from a thousand up, two decimals from 1, four
     * significant digits below; en-US grouping, ties away from zero, as Intl rounds.
     */
    fun formatCompact(value: Double): String {
        if (!value.isFinite()) return "\u2013"
        val abs = Math.abs(value)
        for ((threshold, suffix) in SUFFIX) {
            if (abs >= threshold) {
                val n = value / threshold
                return grouped(n, if (n >= 100) 0 else 2) + suffix
            }
        }
        if (abs == 0.0) return "0"
        if (abs >= 1) return grouped(value, 2)
        return BigDecimal(value.toString()).round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    }

    private fun grouped(v: Double, maxFraction: Int): String {
        val d = BigDecimal(v.toString()).setScale(maxFraction, RoundingMode.HALF_UP).stripTrailingZeros()
        val f = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
        f.maximumFractionDigits = maxFraction
        f.roundingMode = RoundingMode.HALF_UP
        return f.format(d)
    }

    private fun plainNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
