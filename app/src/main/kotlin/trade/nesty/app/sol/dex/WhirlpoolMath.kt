package trade.nesty.app.sol.dex

import java.math.BigInteger

/**
 * Orca Whirlpool's fixed-point arithmetic (orca-so/whirlpools): Q64.64 square-root prices from
 * tick indexes and back, token amounts over a price move, and one exact-input swap step. Each
 * rounding and each failure follows the program, so a quote is what it pays. Ported from
 * dex/whirlpool-math.ts.
 */
object WhirlpoolMath {
    const val MIN_TICK = -443636
    const val MAX_TICK = 443636
    val MIN_SQRT_PRICE: BigInteger = BigInteger("4295048016")
    val MAX_SQRT_PRICE: BigInteger = BigInteger("79226673515401279992447579055")
    val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
    private val U128_MAX: BigInteger = BigInteger.ONE.shiftLeft(128) - BigInteger.ONE
    private val Q64_MASK = U64_MAX

    /** Fee rates are in millionths. */
    private val FEE_RATE_MUL = BigInteger.valueOf(1_000_000)
    private val ZERO = BigInteger.ZERO
    private val ONE = BigInteger.ONE

    private val POSITIVE = listOf(
        "79236085330515764027303304731",
        "79244008939048815603706035061",
        "79259858533276714757314932305",
        "79291567232598584799939703904",
        "79355022692464371645785046466",
        "79482085999252804386437311141",
        "79736823300114093921829183326",
        "80248749790819932309965073892",
        "81282483887344747381513967011",
        "83390072131320151908154831281",
        "87770609709833776024991924138",
        "97234110755111693312479820773",
        "119332217159966728226237229890",
        "179736315981702064433883588727",
        "407748233172238350107850275304",
        "2098478828474011932436660412517",
        "55581415166113811149459800483533",
        "38992368544603139932233054999993551",
    ).map(::BigInteger)

    private val NEGATIVE = listOf(
        "18444899583751176498",
        "18443055278223354162",
        "18439367220385604838",
        "18431993317065449817",
        "18417254355718160513",
        "18387811781193591352",
        "18329067761203520168",
        "18212142134806087854",
        "17980523815641551639",
        "17526086738831147013",
        "16651378430235024244",
        "15030750278693429944",
        "12247334978882834399",
        "8131365268884726200",
        "3584323654723342297",
        "696457651847595233",
        "26294789957452057",
        "37481735321082",
    ).map(::BigInteger)

    /** The square-root price at a tick, in Q64.64. */
    fun sqrtPriceFromTick(tick: Int): BigInteger {
        if (tick >= 0) {
            var ratio = if (tick and 1 != 0) BigInteger("79232123823359799118286999567") else BigInteger("79228162514264337593543950336")
            POSITIVE.forEachIndexed { i, k -> if (tick and (2 shl i) != 0) ratio = (ratio * k).shiftRight(96) }
            return ratio.shiftRight(32)
        }
        val abs = -tick
        var ratio = if (abs and 1 != 0) BigInteger("18445821805675392311") else BigInteger("18446744073709551616")
        NEGATIVE.forEachIndexed { i, k -> if (abs and (2 shl i) != 0) ratio = (ratio * k).shiftRight(64) }
        return ratio
    }

    private val LOG_B_2_X32 = BigInteger("59543866431248")
    private val LOG_ERR_LOWER_X64 = BigInteger("184467440737095516")
    private val LOG_ERR_UPPER_X64 = BigInteger("15793534762490258745")

    /** The tick a square-root price falls in (rounded down). */
    fun tickFromSqrtPrice(sqrtPrice: BigInteger): Int {
        val msb = sqrtPrice.bitLength() - 1
        val integer = BigInteger.valueOf((msb - 64).toLong()).shiftLeft(32)
        var r = if (msb >= 64) sqrtPrice.shiftRight(msb - 63) else sqrtPrice.shiftLeft(63 - msb)
        var bit = BigInteger.ONE.shiftLeft(63)
        var fraction = ZERO
        var precision = 0
        while (bit.signum() > 0 && precision < 14) {
            r *= r
            val more = r.shiftRight(127)
            r = r.shiftRight(63 + more.toInt())
            fraction += bit * more
            bit = bit.shiftRight(1)
            precision++
        }
        val logB = (integer + fraction.shiftRight(32)) * LOG_B_2_X32
        val low = (logB - LOG_ERR_LOWER_X64).shiftRight(64).toInt()
        val high = (logB + LOG_ERR_UPPER_X64).shiftRight(64).toInt()
        if (low == high) return low
        return if (sqrtPriceFromTick(high) <= sqrtPrice) high else low
    }

    private fun ordered(p0: BigInteger, p1: BigInteger) = if (p0 > p1) p1 to p0 else p0 to p1

    /** Token A over a price move, or null past u64 (where the program reports it exceeds). */
    private fun tryDeltaA(p0: BigInteger, p1: BigInteger, liquidity: BigInteger, up: Boolean): BigInteger? {
        val (lower, upper) = ordered(p0, p1)
        val product = liquidity * (upper - lower)
        if (product.shiftRight(192).signum() != 0) refuse()
        val numerator = product.shiftLeft(64)
        val denominator = upper * lower
        if (denominator.signum() == 0) refuse()
        val (div, rem) = numerator.divideAndRemainder(denominator)
        val q = if (up && rem.signum() != 0) div + ONE else div
        return if (q > U64_MAX) null else q
    }

    /** Token B over a price move, or null past u64. */
    private fun tryDeltaB(p0: BigInteger, p1: BigInteger, liquidity: BigInteger, up: Boolean): BigInteger? {
        val (lower, upper) = ordered(p0, p1)
        val diff = upper - lower
        if (liquidity.signum() == 0 || diff.signum() == 0) return ZERO
        val p = liquidity * diff
        if (p > U128_MAX) return null
        val result = p.shiftRight(64)
        val round = up && p.and(Q64_MASK).signum() > 0
        if (round && result == U64_MAX) return null
        return if (round) result + ONE else result
    }

    private fun deltaA(p0: BigInteger, p1: BigInteger, l: BigInteger, up: Boolean) = tryDeltaA(p0, p1, l, up) ?: refuse()
    private fun deltaB(p0: BigInteger, p1: BigInteger, l: BigInteger, up: Boolean) = tryDeltaB(p0, p1, l, up) ?: refuse()

    /** The price after `amount` of token A goes in, rounded up. */
    private fun nextSqrtFromA(sqrtPrice: BigInteger, liquidity: BigInteger, amount: BigInteger): BigInteger {
        if (amount.signum() == 0) return sqrtPrice
        val product = liquidity * sqrtPrice
        if (product.shiftRight(192).signum() != 0) refuse()
        val numerator = product.shiftLeft(64)
        val denominator = liquidity.shiftLeft(64) + sqrtPrice * amount
        val (q, rem) = numerator.divideAndRemainder(denominator)
        val price = if (rem.signum() != 0) q + ONE else q
        if (price > U128_MAX || price < MIN_SQRT_PRICE || price > MAX_SQRT_PRICE) refuse()
        return price
    }

    /** The price after `amount` of token B goes in, rounded down. */
    private fun nextSqrtFromB(sqrtPrice: BigInteger, liquidity: BigInteger, amount: BigInteger): BigInteger {
        if (liquidity.signum() == 0) refuse()
        val price = sqrtPrice + amount.shiftLeft(64) / liquidity
        return if (price > U128_MAX) refuse() else price
    }

    class SwapStep(val amountIn: BigInteger, val amountOut: BigInteger, val nextPrice: BigInteger, val fee: BigInteger)

    /** One step of an exact-input swap toward `target` (`compute_swap`). */
    fun computeSwapIn(remaining: BigInteger, feeRate: BigInteger, liquidity: BigInteger, current: BigInteger, target: BigInteger, aToB: Boolean): SwapStep {
        val initial = if (aToB) tryDeltaA(current, target, liquidity, true) else tryDeltaB(current, target, liquidity, true)
        val calc = remaining * (FEE_RATE_MUL - feeRate) / FEE_RATE_MUL
        val nextPrice = if (initial != null && initial <= calc) {
            target
        } else if (aToB) {
            nextSqrtFromA(current, liquidity, calc)
        } else {
            nextSqrtFromB(current, liquidity, calc)
        }
        val isMax = nextPrice == target
        val amountOut = if (aToB) deltaB(current, nextPrice, liquidity, false) else deltaA(current, nextPrice, liquidity, false)
        val amountIn = if (!isMax || initial == null) {
            if (aToB) deltaA(current, nextPrice, liquidity, true) else deltaB(current, nextPrice, liquidity, true)
        } else {
            initial
        }
        val fee: BigInteger
        if (!isMax) {
            fee = remaining - amountIn
            if (fee.signum() < 0) refuse()
        } else {
            val p = amountIn * feeRate
            val d = FEE_RATE_MUL - feeRate
            val (div, rem) = p.divideAndRemainder(d)
            fee = if (rem.signum() > 0) div + ONE else div
            if (fee > U64_MAX) refuse()
        }
        return SwapStep(amountIn, amountOut, nextPrice, fee)
    }

    /** Floor division of tick arithmetic (the program's `floor_division`). */
    fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
}
