package trade.nesty.app.sol.dex

import java.math.BigInteger

/**
 * Arithmetic Meteora's DAMM v2 and Dynamic Bonding Curve programs share (MeteoraAg/damm-v2,
 * MeteoraAg/dynamic-bonding-curve): Q64.64 square-root prices over concentrated liquidity, fee
 * numerators over 1e9, the decaying fee schedules, the volatility fee and the rate limiter. Each
 * rounding and each refusal follows the programs, so a quote is what they pay. Ported from
 * dex/meteora-math.ts.
 */
object MeteoraMath {
    val FEE_DENOMINATOR: BigInteger = BigInteger.valueOf(1_000_000_000)
    val ONE_Q64: BigInteger = BigInteger.ONE.shiftLeft(64)
    val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
    private val U128_MAX: BigInteger = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE
    private val BPS = BigInteger.valueOf(10_000)
    private val ONE = BigInteger.ONE
    private val ZERO = BigInteger.ZERO
    private val TWO = BigInteger.valueOf(2)

    fun fitU64(x: BigInteger): BigInteger = if (x.signum() >= 0 && x <= U64_MAX) x else refuse()
    private fun fitU128(x: BigInteger): BigInteger = if (x.signum() >= 0 && x <= U128_MAX) x else refuse()
    private fun divUp(a: BigInteger, b: BigInteger): BigInteger = if (b.signum() == 0) refuse() else (a + b - ONE) / b

    /** The fee a numerator takes from an amount, rounded up. */
    fun feeOn(amount: BigInteger, numerator: BigInteger): BigInteger = fitU64(divUp(amount * numerator, FEE_DENOMINATOR))

    /** Base token over a price move: L·(√upper − √lower) / (√upper·√lower). */
    fun deltaBase(lower: BigInteger, upper: BigInteger, liquidity: BigInteger, up: Boolean): BigInteger {
        val den = lower * upper
        if (den.signum() == 0 || upper < lower) refuse()
        val prod = liquidity * (upper - lower)
        return if (up) divUp(prod, den) else prod / den
    }

    /** Quote token over a price move: L·(√upper − √lower), in Q128. */
    fun deltaQuote(lower: BigInteger, upper: BigInteger, liquidity: BigInteger, up: Boolean): BigInteger {
        if (upper < lower) refuse()
        val prod = liquidity * (upper - lower)
        return if (up) divUp(prod, ONE.shiftLeft(128)) else prod.shiftRight(128)
    }

    /** The price after `amountIn` of the base (price falls) or the quote (price rises). */
    fun nextSqrtFromInput(sqrtPrice: BigInteger, liquidity: BigInteger, amountIn: BigInteger, baseIn: Boolean): BigInteger {
        if (sqrtPrice.signum() == 0 || liquidity.signum() == 0) refuse()
        if (amountIn.signum() == 0) return sqrtPrice
        if (baseIn) return fitU128(divUp(liquidity * sqrtPrice, liquidity + amountIn * sqrtPrice))
        return fitU128(sqrtPrice + amountIn.shiftLeft(128) / liquidity)
    }

    /** base^exp in Q64.64, multiplying bit by bit as the programs do (truncating each step). */
    private fun powQ64(base: BigInteger, exp: BigInteger): BigInteger {
        if (exp.signum() == 0) return ONE_Q64
        if (exp >= BigInteger.valueOf(0x80000)) refuse()
        fun mul(a: BigInteger, b: BigInteger) = fitU128(a * b).shiftRight(64)
        var sq = base
        var result = ONE_Q64
        var invert = false
        if (sq >= result) {
            sq = U128_MAX / sq
            invert = true
        }
        if (exp.testBit(0)) result = mul(result, sq)
        for (bit in 1 until 19) {
            sq = mul(sq, sq)
            if (exp.testBit(bit)) result = mul(result, sq)
        }
        if (result.signum() == 0) refuse()
        return if (invert) U128_MAX / result else result
    }

    /**
     * cliff·(1 − reduction/10⁴)^period. DAMM v2 returns the cliff outright for a zero reduction;
     * the bonding curve program computes it anyway.
     */
    fun feeInPeriod(cliff: BigInteger, reduction: BigInteger, period: BigInteger, zeroIsCliff: Boolean): BigInteger {
        if (zeroIsCliff && reduction.signum() == 0) return cliff
        val bps = reduction.shiftLeft(64) / BPS
        if (bps > ONE_Q64) refuse()
        return fitU64(fitU128(powQ64(ONE_Q64 - bps, period) * cliff).shiftRight(64))
    }

    /** A fee schedule's numerator after `period` periods (linear or exponential). */
    fun scheduledFee(exponential: Boolean, cliff: BigInteger, reduction: BigInteger, period: BigInteger, zeroIsCliff: Boolean): BigInteger {
        if (exponential) return feeInPeriod(cliff, reduction, period, zeroIsCliff)
        val cut = reduction * period
        return if (cut > cliff) refuse() else cliff - cut
    }

    /** A fee given in basis points, as a numerator over 1e9. */
    fun bpsNumerator(bps: BigInteger): BigInteger = fitU64(bps * FEE_DENOMINATOR / BPS)

    /**
     * The rate limiter's fee numerator for an input: the cliff up to the reference amount, then one
     * increment more for each further reference amount, up to `maxFee`.
     */
    fun rateLimitedFee(input: BigInteger, cliff: BigInteger, reference: BigInteger, incrementBps: BigInteger, maxFee: BigInteger): BigInteger {
        if (input <= reference) return cliff
        if (reference.signum() == 0 || maxFee < cliff) refuse()
        val i = bpsNumerator(incrementBps)
        if (i.signum() == 0) refuse()
        val maxIndex = (maxFee - cliff) / i
        val a = (input - reference) / reference
        val b = (input - reference) % reference
        val total = if (a < maxIndex) {
            reference * (cliff + cliff * a + i * a * (a + ONE) / TWO) + b * (cliff + i * (a + ONE))
        } else {
            reference * (cliff + cliff * maxIndex + i * maxIndex * (maxIndex + ONE) / TWO) + ((a - maxIndex) * reference + b) * maxFee
        }
        val fee = fitU64(divUp(total, FEE_DENOMINATOR))
        return fitU64(divUp(fee * FEE_DENOMINATOR, input))
    }

    /** The volatility fee: (accumulator·bin step)²·control / 1e11, rounded up. */
    fun volatilityFee(accumulator: BigInteger, binStep: BigInteger, control: BigInteger): BigInteger {
        val square = fitU128((accumulator * binStep).pow(2))
        return fitU128(fitU128(square * control) + BigInteger.valueOf(99_999_999_999)) / BigInteger.valueOf(100_000_000_000)
    }

    internal val zero get() = ZERO
}
