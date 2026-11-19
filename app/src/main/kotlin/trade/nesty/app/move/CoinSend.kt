package trade.nesty.app.move

import trade.nesty.app.data.Chain
import trade.nesty.app.evm.formatUnits
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.SolSend
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** The arithmetic of sending a coin the wallet holds, with no reads: the send screen and its tests share it. */
object CoinSend {
    /** Whole coins before the point, at most: supplies run to the trillions. */
    private const val MAX_WHOLE = 20

    /**
     * Digits and one point as typed, at most `decimals` after it (a comma counts as the point).
     * Null: keep what was there.
     */
    fun clean(v: String, decimals: Int): String? {
        val s = v.replace(',', '.')
        if (s.any { !it.isDigit() && it != '.' }) return null
        val parts = s.split('.')
        if (parts.size > 2) return null
        if (parts.size == 2 && parts[1].length > decimals.coerceAtLeast(0)) return null
        if (parts.size == 2 && decimals <= 0) return null
        if (parts[0].length > MAX_WHOLE) return null
        return s
    }

    /** What was typed, in the coin's smallest unit; null when it is not an amount above zero. */
    fun unitsOf(typed: String, decimals: Int): BigInteger? {
        val d = typed.trim().takeIf { it.isNotEmpty() && it != "." }?.toBigDecimalOrNull() ?: return null
        if (d.signum() <= 0) return null
        return d.movePointRight(decimals).setScale(0, RoundingMode.DOWN).toBigInteger().takeIf { it.signum() > 0 }
    }

    /** An amount as the field shows it: every figure, no trailing zeros. */
    fun typed(raw: BigInteger, decimals: Int): String = formatUnits(raw, decimals)

    /** `bps` of 10,000 of what is held, rounded down; all of it at 10,000. */
    fun share(held: BigInteger, bps: Int): BigInteger = if (bps >= 10_000) held else held * BigInteger.valueOf(bps.toLong()) / BigInteger.valueOf(10_000)

    /** Whole coins of an amount, for its worth and the screens. */
    fun tokens(raw: BigInteger, decimals: Int): Double = BigDecimal(raw).movePointLeft(decimals).toDouble()

    /**
     * Why the chain's own coin, `native` held, cannot pay a coin send costing `fee`: on Solana also
     * `rent` for the receiver's account for the coin, and what stays must be zero or `keep`. Null
     * when it can, or when the fee or the balance is not known yet.
     */
    fun feeBlock(chain: Chain, native: BigInteger?, fee: BigInteger?, rent: BigInteger = BigInteger.ZERO, keep: BigInteger = BigInteger.ZERO): String? {
        if (native == null || fee == null) return null
        if (chain == Chain.SOLANA) return SolSend.coinSendBlock(native, rent, fee, keep)
        return if (native < fee) tr("Not enough {1} for the fee.", chain.native) else null
    }
}
