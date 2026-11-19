package trade.nesty.app.sol

import trade.nesty.app.evm.Side
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.SolAddr.ATA_PROGRAM
import trade.nesty.app.sol.SolAddr.DEFAULT_KEY
import trade.nesty.app.sol.SolAddr.SYSTEM_PROGRAM
import trade.nesty.app.sol.SolAddr.TOKEN_2022_PROGRAM
import trade.nesty.app.sol.SolAddr.TOKEN_PROGRAM
import trade.nesty.app.sol.SolAddr.USDC_MINT
import trade.nesty.app.sol.SolAddr.WSOL_MINT
import trade.nesty.app.sol.SolAddr.associatedTokenAddress
import trade.nesty.app.sol.SolAddr.pda
import trade.nesty.app.sol.SolAddr.seed
import java.math.BigInteger
import kotlin.random.Random

/** What a coin trades against: SOL (as WSOL) or a token, with its token program. */
data class QuoteAsset(val mint: String, val program: String) {
    val isSol: Boolean get() = mint == WSOL_MINT
}

val SOL_QUOTE = QuoteAsset(WSOL_MINT, TOKEN_PROGRAM)

data class Fees(val lpFeeBps: BigInteger, val protocolFeeBps: BigInteger, val creatorFeeBps: BigInteger)

data class FeeTier(val threshold: BigInteger, val fees: Fees)

data class FeeConfig(val flatFees: Fees, val feeTiers: List<FeeTier>, val stableFeeTiers: List<FeeTier>, val exoticFlatFees: Fees)

data class PumpGlobal(
    val poolMigrationFee: BigInteger,
    val feeBasisPoints: BigInteger,
    val creatorFeeBasisPoints: BigInteger,
    /** Where v2 curve trades may send the protocol fee (unset slots left out). */
    val feeRecipients: List<String>,
    /** The same, for mayhem coins. */
    val reservedFeeRecipients: List<String>,
    val buybackFeeRecipients: List<String>,
    val creatorFeeConfigurable: Boolean,
)

data class BondingCurve(
    val virtualTokenReserves: BigInteger,
    val virtualQuoteReserves: BigInteger,
    val realTokenReserves: BigInteger,
    val realQuoteReserves: BigInteger,
    val tokenTotalSupply: BigInteger,
    val complete: Boolean,
    val creator: String,
    val isMayhemMode: Boolean,
    val isCashbackCoin: Boolean,
    /** The default key on SOL curves created before quote mints existed. */
    val quoteMint: String,
    val creatorFeeBps: BigInteger,
)

data class AmmGlobalConfig(
    val lpFeeBasisPoints: BigInteger,
    val protocolFeeBasisPoints: BigInteger,
    val coinCreatorFeeBasisPoints: BigInteger,
    /** Where v1 pool trades may send the protocol fee (unset slots left out). */
    val protocolFeeRecipients: List<String>,
    /** The same, for mayhem pools. */
    val reservedFeeRecipients: List<String>,
    val buybackFeeRecipients: List<String>,
    val creatorFeeConfigurable: Boolean,
)

data class AmmPool(
    val creator: String,
    val baseMint: String,
    val quoteMint: String,
    val poolBaseTokenAccount: String,
    val poolQuoteTokenAccount: String,
    val coinCreator: String,
    val isMayhemMode: Boolean,
    val isCashbackCoin: Boolean,
    /** Signed: added to the quote vault to price trades. */
    val virtualQuoteReserves: BigInteger,
    val creatorFeeBps: BigInteger,
    val protocolFees: BigInteger,
    val creatorFees: BigInteger,
)

sealed class PumpVenue {
    abstract val mint: String
    abstract val tokenProgram: String
    abstract val quote: QuoteAsset
    abstract val feeConfig: FeeConfig
    abstract val mintSupply: BigInteger
}

/** A coin still on its bonding curve. */
data class CurveVenue(
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    val curve: BondingCurve,
    /** The curve's own token balance: prices a buy that runs past the curve. */
    val curveTokenBalance: BigInteger,
    val global: PumpGlobal,
    override val feeConfig: FeeConfig,
    override val mintSupply: BigInteger,
) : PumpVenue()

/** A coin on its canonical PumpSwap pool (after migration). */
data class PoolVenue(
    override val mint: String,
    override val tokenProgram: String,
    override val quote: QuoteAsset,
    val poolAddress: String,
    val pool: AmmPool,
    val baseReserve: BigInteger,
    /** The quote vault balance, before virtual reserves. */
    val quoteReserve: BigInteger,
    val globalConfig: AmmGlobalConfig,
    override val feeConfig: FeeConfig,
    override val mintSupply: BigInteger,
) : PumpVenue()

/**
 * How a coin is reached from what the user pays with: SOL, or the token at the bottom of its
 * quote chain. One hop trades on the coin's own curve or pool; more go through PumpSwap's
 * `multi_hop_swap`, which prices each hop like its direct trade.
 */
data class PumpRoute(val pay: QuoteAsset, val hops: List<PumpVenue>)

/** The trade a route takes. Cashback coins and mayhem pools refuse the newer versions. */
enum class TradeVariant { CURVE_V3, CURVE_V2, POOL_V2, POOL_V1, MULTI_HOP }

/** Which fees a trade charges: all of them, except on the hops of a multi-hop route. */
data class HopFees(val protocol: Boolean, val creator: Boolean, val lp: Boolean)

/** Accounts a trade pays fees into, beyond what the programs derive themselves. */
data class TradeRecipients(
    /** A listed buyback wallet (the trade names it, or its account in the pay currency). */
    val buyback: String,
    /** v2 curve and v1 pool trades: the protocol fee recipient, from the venue's own list. */
    val fee: String? = null,
)

class TradeIxs(val instructions: List<Instruction>, /** What the wallet puts into the trade, in the pay currency (buys). */ val spend: BigInteger)

/** The trade's discriminator, and the flag byte after its amount and minimum, if any. */
class TradeData(val disc: ByteArray, val flag: Int? = null)

/**
 * pump.fun's bonding curve and PumpSwap, ported from the extension's src/background/solana/pump.ts:
 * the accounts, the fee schedule, quotes (synthetic migration included), routes through other
 * pump.fun coins, and the exact instructions of each trade.
 */
object Pump {
    // pump.fun's bonding curve program, its AMM (PumpSwap) and the fee program both read.
    const val PUMP_PROGRAM = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    const val PUMP_AMM_PROGRAM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    const val PUMP_FEES_PROGRAM = "pfeeUxB6jkeY1Hxd7CsFCAjcbHA9rWtchMGdZ6VojVZ"

    /** pump PDA ["global"]. */
    const val PUMP_GLOBAL = "4wTV1YmiEkRvAtNtsSGPtUrqRYQMe5SKy2uB4Jjaxnjf"

    /** Pump Fees PDA ["fee_config", pump]. */
    const val PUMP_FEE_CONFIG = "8Wf5TiAheLUqBrKXeYg2JtAFFMWtKdG2BSFgqUcPVwTt"

    /** PumpSwap PDA ["global_config"]. */
    const val AMM_GLOBAL_CONFIG = "ADyA8hdefvWN2dbGGWFotbzWxrAvLW83WG6QCVXvJKqw"

    /** Pump Fees PDA ["fee_config", pump amm]. */
    const val AMM_FEE_CONFIG = "5PHirr8joyTMp9JMm6nW7hNDVyEYdkzDqazxPD7RaTjx"

    /**
     * The buyback fee recipients pump.fun lists for every coin (docs: FEE_RECIPIENTS.md), in the
     * programs' order. A trade names one of them; Nesty only ever names one from this list.
     */
    val BUYBACK_FEE_RECIPIENTS: List<String> = listOf(
        "5YxQFdt3Tr9zJLvkFccqXVUwhdTWJQc1fFg2YPbxvxeD",
        "9M4giFFMxmFGXtc3feFzRai56WbBqehoSeRE5GK7gf7",
        "GXPFM2caqTtQYC2cJ5yJRi9VDkpsYZXzYdwYpGnLmtDL",
        "3BpXnfJaUTiwXnJNe7Ej1rcbzqTTQUvLShZaWazebsVR",
        "5cjcW9wExnJJiqgLjq7DEG75Pm6JBgE1hNv4B2vHXUW6",
        "EHAAiTxcdDwQ3U4bU6YcMsQGaekdzLS3B5SmYo46kJtL",
        "5eHhjP8JaYkz83CWwvGU2uMUXefd3AazWGx4gpcuEEYD",
        "A7hAgCzFw14fejgCp387JUJRMNyz4j89JKnhtKU8piqW",
    )

    private val DISC_GLOBAL = bytesOf(167, 232, 232, 177, 200, 108, 114, 127)
    private val DISC_BONDING_CURVE = bytesOf(23, 183, 248, 55, 96, 216, 172, 96)
    private val DISC_FEE_CONFIG = bytesOf(143, 52, 146, 187, 219, 123, 76, 155)
    val DISC_POOL = bytesOf(241, 154, 109, 4, 17, 177, 109, 188)
    private val DISC_GLOBAL_CONFIG = bytesOf(149, 8, 156, 202, 160, 252, 176, 217)

    val IX_BUY_EXACT_QUOTE_IN_V3 = bytesOf(225, 247, 80, 30, 213, 179, 132, 136)
    val IX_SELL_V3 = bytesOf(28, 146, 222, 119, 38, 196, 105, 213)

    /** pump's curve and PumpSwap share these two: same names, same discriminators. */
    val IX_BUY_EXACT_QUOTE_IN_V2 = bytesOf(194, 171, 28, 70, 104, 77, 91, 47)
    val IX_SELL_V2 = bytesOf(93, 246, 130, 60, 231, 233, 64, 178)

    /** PumpSwap v1: the only trades cashback and mayhem pools take. */
    val IX_AMM_BUY_EXACT_QUOTE_IN = bytesOf(198, 46, 21, 82, 180, 217, 232, 112)
    val IX_AMM_SELL = bytesOf(51, 230, 133, 164, 1, 127, 131, 173)
    val IX_MULTI_HOP_SWAP = bytesOf(43, 100, 73, 19, 233, 246, 111, 148)

    /** pump's market-cap basis for every coin but mayhem ones (1 billion, 6 decimals). */
    private val ONE_BILLION_SUPPLY = BigInteger("1000000000000000")
    private val BPS = BigInteger.valueOf(10_000)
    private val ONE = BigInteger.ONE
    private val ZERO = BigInteger.ZERO
    private val TWO = BigInteger.valueOf(2)

    /**
     * The longest route that fits a legacy transaction (1232 bytes): a 2-hop SOL buy takes about
     * 1130, a 3-hop one 1240–1265.
     */
    const val MAX_HOPS = 2

    // ── PDAs ────────────────────────────────────────────────────────────────────────

    fun bondingCurveAddress(mint: String) = pda(PUMP_PROGRAM, seed("bonding-curve"), mint)
    fun pumpEventAuthority() = pda(PUMP_PROGRAM, seed("__event_authority"))
    fun pumpVolumeAccumulator(user: String) = pda(PUMP_PROGRAM, seed("user_volume_accumulator"), user)
    fun pumpGlobalVolumeAccumulator() = pda(PUMP_PROGRAM, seed("global_volume_accumulator"))

    /** Where v2 curve trades pay a coin's creator fee. */
    fun creatorVaultAddress(creator: String) = pda(PUMP_PROGRAM, seed("creator-vault"), creator)
    fun sharingConfigAddress(mint: String) = pda(PUMP_FEES_PROGRAM, seed("sharing-config"), mint)
    fun poolAuthority(mint: String) = pda(PUMP_PROGRAM, seed("pool-authority"), mint)

    /** The pool a pump coin migrates to: index 0, created by its pool authority, in its quote. */
    fun canonicalPoolAddress(mint: String, quoteMint: String = WSOL_MINT) =
        pda(PUMP_AMM_PROGRAM, seed("pool"), bytesOf(0, 0), poolAuthority(mint), mint, quoteMint)

    fun ammEventAuthority() = pda(PUMP_AMM_PROGRAM, seed("__event_authority"))
    fun ammVolumeAccumulator(user: String) = pda(PUMP_AMM_PROGRAM, seed("user_volume_accumulator"), user)
    fun ammGlobalVolumeAccumulator() = pda(PUMP_AMM_PROGRAM, seed("global_volume_accumulator"))

    /** Owns the quote account v1 pool trades pay the creator fee into. */
    fun ammCreatorVaultAuthority(coinCreator: String) = pda(PUMP_AMM_PROGRAM, seed("creator_vault"), coinCreator)
    fun poolV2Address(mint: String) = pda(PUMP_AMM_PROGRAM, seed("pool-v2"), mint)

    // ── accounts ────────────────────────────────────────────────────────────────────

    private fun fees(r: Reader) = Fees(r.u64(), r.u64(), r.u64())

    private fun tiers(r: Reader): List<FeeTier> {
        val n = r.u32()
        require(n <= 64) { "bad fee tiers" }
        return (0 until n.toInt()).map { FeeTier(r.u128(), fees(r)) }
    }

    private fun keys(r: Reader, n: Int) = (0 until n).map { r.pubkey() }
    private fun listed(list: List<String>) = list.filter { it != DEFAULT_KEY }

    fun decodeGlobal(data: ByteArray): PumpGlobal? {
        if (!hasDiscriminator(data, DISC_GLOBAL)) return null
        val r = Reader(data, 8)
        r.skip(1 + 32)
        val feeRecipient = r.pubkey()
        r.u64() // initial virtual token reserves
        r.u64() // initial virtual SOL reserves
        r.u64() // initial real token reserves
        r.u64() // token total supply
        val feeBasisPoints = r.u64()
        r.skip(32 + 1)
        val poolMigrationFee = r.u64()
        val creatorFeeBasisPoints = r.u64()
        val feeRecipients = keys(r, 7)
        r.skip(32 + 32)
        r.bool() // create_v2 enabled
        r.skip(32)
        val reservedFeeRecipient = r.pubkey()
        r.skip(1)
        val reservedFeeRecipients = keys(r, 7)
        r.skip(1)
        val buybackFeeRecipients = keys(r, 8)
        r.skip(8 + 8 + 32)
        val creatorFeeConfigurable = r.bool()
        return PumpGlobal(
            poolMigrationFee = poolMigrationFee,
            feeBasisPoints = feeBasisPoints,
            creatorFeeBasisPoints = creatorFeeBasisPoints,
            feeRecipients = listed(listOf(feeRecipient) + feeRecipients),
            reservedFeeRecipients = listed(listOf(reservedFeeRecipient) + reservedFeeRecipients),
            buybackFeeRecipients = buybackFeeRecipients,
            creatorFeeConfigurable = creatorFeeConfigurable,
        )
    }

    fun decodeBondingCurve(data: ByteArray): BondingCurve? {
        if (!hasDiscriminator(data, DISC_BONDING_CURVE)) return null
        val r = Reader(data, 8)
        return BondingCurve(
            virtualTokenReserves = r.u64(),
            virtualQuoteReserves = r.u64(),
            realTokenReserves = r.u64(),
            realQuoteReserves = r.u64(),
            tokenTotalSupply = r.u64(),
            complete = r.bool(),
            creator = r.pubkey(),
            isMayhemMode = r.bool(),
            isCashbackCoin = r.bool(),
            quoteMint = r.pubkey(),
            creatorFeeBps = r.u64(),
        )
    }

    fun decodeFeeConfig(data: ByteArray): FeeConfig? {
        if (!hasDiscriminator(data, DISC_FEE_CONFIG)) return null
        return try {
            val r = Reader(data, 8 + 1 + 32)
            val flatFees = fees(r)
            val feeTiers = tiers(r)
            val stableFeeTiers = tiers(r)
            val exoticFlatFees = fees(r)
            if (feeTiers.isNotEmpty()) FeeConfig(flatFees, feeTiers, stableFeeTiers, exoticFlatFees) else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decodeAmmGlobalConfig(data: ByteArray): AmmGlobalConfig? {
        if (!hasDiscriminator(data, DISC_GLOBAL_CONFIG)) return null
        val r = Reader(data, 8 + 32)
        val lpFeeBasisPoints = r.u64()
        val protocolFeeBasisPoints = r.u64()
        r.skip(1)
        val protocolFeeRecipients = keys(r, 8)
        val coinCreatorFeeBasisPoints = r.u64()
        r.skip(32 + 32)
        val reservedFeeRecipient = r.pubkey()
        r.skip(1)
        val reservedFeeRecipients = keys(r, 7)
        r.skip(1)
        val buybackFeeRecipients = keys(r, 8)
        r.skip(8 + 32 + 1)
        val creatorFeeConfigurable = r.bool()
        return AmmGlobalConfig(
            lpFeeBasisPoints = lpFeeBasisPoints,
            protocolFeeBasisPoints = protocolFeeBasisPoints,
            coinCreatorFeeBasisPoints = coinCreatorFeeBasisPoints,
            protocolFeeRecipients = listed(protocolFeeRecipients),
            reservedFeeRecipients = listed(listOf(reservedFeeRecipient) + reservedFeeRecipients),
            buybackFeeRecipients = buybackFeeRecipients,
            creatorFeeConfigurable = creatorFeeConfigurable,
        )
    }

    fun decodePool(data: ByteArray): AmmPool? {
        if (!hasDiscriminator(data, DISC_POOL)) return null
        val r = Reader(data, 8 + 1 + 2)
        val creator = r.pubkey()
        val baseMint = r.pubkey()
        val quoteMint = r.pubkey()
        r.skip(32)
        val poolBaseTokenAccount = r.pubkey()
        val poolQuoteTokenAccount = r.pubkey()
        r.skip(8)
        val coinCreator = r.pubkey()
        val isMayhemMode = r.bool()
        val isCashbackCoin = r.bool()
        val virtualQuoteReserves = r.i128()
        val creatorFeeBps = r.u64()
        r.skip(2)
        val protocolFees = r.u64()
        val creatorFees = r.u64()
        return AmmPool(
            creator, baseMint, quoteMint, poolBaseTokenAccount, poolQuoteTokenAccount, coinCreator,
            isMayhemMode, isCashbackCoin, virtualQuoteReserves, creatorFeeBps, protocolFees, creatorFees,
        )
    }

    // ── venues ──────────────────────────────────────────────────────────────────────

    /** A curve's quote mint: SOL curves store the default key (older coins) or WSOL. */
    fun curveQuoteMint(c: BondingCurve): String = if (c.quoteMint == DEFAULT_KEY) WSOL_MINT else c.quoteMint

    fun venueLabel(v: PumpVenue): String = if (v is CurveVenue) "pump.fun" else "PumpSwap"

    private fun isCashback(v: PumpVenue) = when (v) {
        is CurveVenue -> v.curve.isCashbackCoin
        is PoolVenue -> v.pool.isCashbackCoin
    }

    private fun isMayhem(v: PumpVenue) = when (v) {
        is CurveVenue -> v.curve.isMayhemMode
        is PoolVenue -> v.pool.isMayhemMode
    }

    /** The Token-2022 native mint: SOL-like to the fee program, never a curve's quote. */
    private const val NATIVE_MINT_2022 = "9pan9bMn5HatX4EJdBwg9VgCa7Uz5HL8N1m5D3NdXejP"
    private fun isSolLike(mint: String) = mint == DEFAULT_KEY || mint == WSOL_MINT || mint == NATIVE_MINT_2022

    private fun feeTier(tiers: List<FeeTier>, marketCap: BigInteger): Fees {
        val first = tiers.firstOrNull() ?: throw IllegalStateException("no fee tiers")
        if (marketCap < first.threshold) return first.fees
        // The highest tier the market cap has reached.
        return (tiers.lastOrNull { marketCap >= it.threshold } ?: first).fees
    }

    private fun isZeroFees(f: Fees) = f.lpFeeBps.signum() == 0 && f.protocolFeeBps.signum() == 0 && f.creatorFeeBps.signum() == 0

    /** pump-fees `fees_for_quote_mint` for a canonical pump curve or pool. */
    private fun pumpSchedule(config: FeeConfig, quoteMint: String, marketCap: BigInteger): Fees = when {
        isSolLike(quoteMint) -> feeTier(config.feeTiers, marketCap)
        quoteMint == USDC_MINT -> feeTier(config.stableFeeTiers.ifEmpty { config.feeTiers }, marketCap)
        else -> if (isZeroFees(config.exoticFlatFees)) config.flatFees else config.exoticFlatFees
    }

    private fun ceilFee(amount: BigInteger, bps: BigInteger) = (amount * bps + BPS - ONE) / BPS

    val ALL_FEES = HopFees(protocol = true, creator = true, lp = true)

    /**
     * pump-amm's `multi_hop_swap` charges each fee once per route: the protocol fee on the hop
     * trading the user's currency, the creator fee (and a pool's LP fee) on the far coin's hop.
     * `i` counts hops in trade order; a lone hop charges everything.
     */
    fun hopFees(side: Side, i: Int, n: Int, isPool: Boolean): HopFees {
        val first = i == 0
        val last = i == n - 1
        val (protocol, target) = if (side == Side.BUY) first to last else last to first
        return HopFees(protocol, target, target && isPool)
    }

    private class CurveRates(val protocolFeeBps: BigInteger, val creatorFeeBps: BigInteger)

    private fun curveRates(v: CurveVenue, mask: HopFees): CurveRates {
        val c = v.curve
        val supply = if (c.isMayhemMode) v.mintSupply else ONE_BILLION_SUPPLY
        val marketCap = c.virtualQuoteReserves * supply / c.virtualTokenReserves
        val schedule = pumpSchedule(v.feeConfig, c.quoteMint, marketCap)
        val creatorRate = if (v.global.creatorFeeConfigurable && c.creatorFeeBps.signum() > 0) c.creatorFeeBps else schedule.creatorFeeBps
        return CurveRates(
            if (mask.protocol) schedule.protocolFeeBps else ZERO,
            if (mask.creator && c.creator != DEFAULT_KEY) creatorRate else ZERO,
        )
    }

    private fun curveFees(amount: BigInteger, r: CurveRates) = ceilFee(amount, r.protocolFeeBps) + ceilFee(amount, r.creatorFeeBps)

    /** pump `exact_quote_in_fees`: what a budget leaves for the curve once fees are taken. */
    private fun curveNetOf(budget: BigInteger, r: CurveRates): BigInteger {
        val total = r.protocolFeeBps + r.creatorFeeBps
        var net = if (total.signum() == 0) budget else budget * BPS / (total + BPS)
        val cost = net + curveFees(net, r)
        if (cost > budget) net -= cost - budget
        return net
    }

    /** Why a curve takes no trades right now, or null when it does. */
    fun curveIdle(c: BondingCurve): String? =
        if (c.complete || c.virtualTokenReserves.signum() == 0) tr("The pump.fun curve is complete. Trading opens on PumpSwap once it migrates.") else null

    /**
     * Tokens a curve buy gives for `amountIn` of its quote. With `synthetic` (v3 buys and route
     * hops), the part of a buy past the curve's end buys from the pool its migration opens.
     * Otherwise, and always on mayhem coins, such a buy fails: it is quoted 0.
     */
    fun curveBuyQuote(v: CurveVenue, amountIn: BigInteger, mask: HopFees = ALL_FEES, synthetic: Boolean = true): BigInteger {
        val c = v.curve
        if (amountIn.signum() <= 0 || curveIdle(c) != null) return ZERO
        val r = curveRates(v, mask)
        val net = curveNetOf(amountIn, r)
        if (net <= ONE) return ZERO
        val tokens = (net - ONE) * c.virtualTokenReserves / (c.virtualQuoteReserves + net - ONE)
        val remaining = c.realTokenReserves
        if (tokens <= remaining) return tokens
        if (!synthetic || c.isMayhemMode) return ZERO

        // Synthetic migration: the rest of the budget buys from the pool the migration will open.
        val curveNet = remaining * c.virtualQuoteReserves / (c.virtualTokenReserves - remaining) + ONE
        val budgetLeft = amountIn - (curveNet + curveFees(curveNet, r))
        if (budgetLeft.signum() <= 0) return remaining
        val total = r.protocolFeeBps + r.creatorFeeBps
        val afterFees = if (total.signum() == 0) budgetLeft else budgetLeft * BPS / (total + BPS)
        if (afterFees < TWO) return remaining
        val legNet = curveNetOf(budgetLeft, r)
        val poolBase = v.curveTokenBalance - remaining
        // The migration fee comes out of a SOL curve's SOL; a token-quoted curve pays none.
        val migrationFee = if (v.quote.isSol) v.global.poolMigrationFee else ZERO
        val poolQuote = c.realQuoteReserves + curveNet - migrationFee
        if (poolBase.signum() <= 0 || poolQuote.signum() <= 0) return remaining
        return remaining + (legNet - ONE) * poolBase / (poolQuote + legNet - ONE)
    }

    /** What a curve sell pays in its quote for `tokens`, after fees. */
    fun curveSellQuote(v: CurveVenue, tokens: BigInteger, mask: HopFees = ALL_FEES): BigInteger {
        val c = v.curve
        if (tokens.signum() <= 0 || curveIdle(c) != null) return ZERO
        val gross = tokens * c.virtualQuoteReserves / (c.virtualTokenReserves + tokens)
        val out = gross - curveFees(gross, curveRates(v, mask))
        return if (out.signum() > 0) out else ZERO
    }

    private fun poolFees(v: PoolVenue, mask: HopFees): Fees {
        val p = v.pool
        val effQuote = v.quoteReserve + p.virtualQuoteReserves
        val isPumpPool = p.creator == poolAuthority(v.mint)
        val supply = if (p.isMayhemMode) ONE_BILLION_SUPPLY else v.mintSupply
        val marketCap = if (v.baseReserve.signum() > 0) effQuote * supply / v.baseReserve else ZERO
        val schedule = if (isPumpPool) pumpSchedule(v.feeConfig, p.quoteMint, marketCap) else v.feeConfig.flatFees
        val creatorFeeBps = if (v.globalConfig.creatorFeeConfigurable && p.creatorFeeBps.signum() > 0) p.creatorFeeBps else schedule.creatorFeeBps
        return Fees(
            lpFeeBps = if (mask.lp) schedule.lpFeeBps else ZERO,
            protocolFeeBps = if (mask.protocol) schedule.protocolFeeBps else ZERO,
            creatorFeeBps = if (mask.creator && p.coinCreator != DEFAULT_KEY) creatorFeeBps else ZERO,
        )
    }

    /** Tokens a pool buy gives for `amountIn` of its quote. */
    fun poolBuyQuote(v: PoolVenue, amountIn: BigInteger, mask: HopFees = ALL_FEES): BigInteger {
        val effQuote = v.quoteReserve + v.pool.virtualQuoteReserves
        if (amountIn.signum() <= 0 || v.baseReserve.signum() == 0 || effQuote.signum() <= 0) return ZERO
        val f = poolFees(v, mask)
        val total = f.lpFeeBps + f.protocolFeeBps + f.creatorFeeBps
        var net = amountIn * BPS / (BPS + total)
        val cost = net + ceilFee(net, f.lpFeeBps) + ceilFee(net, f.protocolFeeBps) + ceilFee(net, f.creatorFeeBps)
        if (cost > amountIn) net -= cost - amountIn
        if (net <= ONE) return ZERO
        return v.baseReserve * (net - ONE) / (effQuote + net - ONE)
    }

    /** What a pool sell pays in its quote for `tokens`, after fees; 0 when the vault cannot. */
    fun poolSellQuote(v: PoolVenue, tokens: BigInteger, mask: HopFees = ALL_FEES): BigInteger {
        val p = v.pool
        val effQuote = v.quoteReserve + p.virtualQuoteReserves
        if (tokens.signum() <= 0 || effQuote.signum() <= 0) return ZERO
        val gross = effQuote * tokens / (v.baseReserve + tokens)
        val f = poolFees(v, mask)
        val withoutLp = gross - ceilFee(gross, f.lpFeeBps)
        // Sells are paid from the vault net of the fees v2 trades left in it.
        if (v.quoteReserve - p.protocolFees - p.creatorFees < withoutLp) return ZERO
        val out = withoutLp - ceilFee(gross, f.protocolFeeBps) - ceilFee(gross, f.creatorFeeBps)
        return if (out.signum() > 0) out else ZERO
    }

    /** Quote units per raw coin unit at the current price, as a float (for impact and USD). */
    fun spotPrice(v: PumpVenue): Double = when (v) {
        is CurveVenue -> if (v.curve.virtualTokenReserves.signum() > 0) v.curve.virtualQuoteReserves.toDouble() / v.curve.virtualTokenReserves.toDouble() else 0.0
        is PoolVenue -> {
            val effQuote = v.quoteReserve + v.pool.virtualQuoteReserves
            if (v.baseReserve.signum() > 0) effQuote.toDouble() / v.baseReserve.toDouble() else 0.0
        }
    }

    // ── routes ──────────────────────────────────────────────────────────────────────

    fun routeCoin(r: PumpRoute): PumpVenue = r.hops.last()

    private fun inTradeOrder(r: PumpRoute, side: Side) = if (side == Side.BUY) r.hops else r.hops.reversed()

    fun routeVariant(r: PumpRoute): TradeVariant {
        if (r.hops.size > 1) return TradeVariant.MULTI_HOP
        return when (val v = routeCoin(r)) {
            is CurveVenue -> if (v.curve.isCashbackCoin) TradeVariant.CURVE_V2 else TradeVariant.CURVE_V3
            is PoolVenue -> if (v.pool.isCashbackCoin || v.pool.isMayhemMode) TradeVariant.POOL_V1 else TradeVariant.POOL_V2
        }
    }

    /**
     * Whether a route's hops chain from its pay currency, and (past one hop) whether
     * `multi_hop_swap` takes them: canonical pools, no mayhem coins, and a cashback coin only on
     * the hop next to the user's currency.
     */
    fun routeOk(r: PumpRoute): Boolean {
        if (r.hops.isEmpty() || r.hops.size > MAX_HOPS) return false
        var quote = r.pay.mint
        for (v in r.hops) {
            if (v.quote.mint != quote) return false
            quote = v.mint
        }
        if (r.hops.size == 1) return true
        return r.hops.withIndex().all { (i, v) ->
            !isMayhem(v) && (i == 0 || !isCashback(v)) && when (v) {
                is CurveVenue -> curveIdle(v.curve) == null
                is PoolVenue -> v.pool.creator == poolAuthority(v.mint) && v.poolAddress == canonicalPoolAddress(v.mint, v.quote.mint)
            }
        }
    }

    /**
     * The route a coin trades by, from its quote chain: `chain[0]` the coin's own venue, each
     * next one the venue of the one before's quote coin. The longest the programs allow, so the
     * user pays with SOL or a token they likely hold rather than an intermediate coin.
     */
    fun pickRoute(chain: List<PumpVenue>): PumpRoute? {
        for (d in minOf(chain.size, MAX_HOPS) downTo 1) {
            val hops = chain.take(d).reversed()
            val route = PumpRoute(hops[0].quote, hops)
            if (routeOk(route)) return route
        }
        return null
    }

    /** What `amountIn` of the pay currency buys of the coin. */
    fun routeBuyQuote(r: PumpRoute, amountIn: BigInteger): BigInteger {
        val synthetic = routeVariant(r) != TradeVariant.CURVE_V2
        val n = r.hops.size
        var amount = amountIn
        for ((i, v) in r.hops.withIndex()) {
            val mask = hopFees(Side.BUY, i, n, v is PoolVenue)
            amount = when (v) {
                is CurveVenue -> curveBuyQuote(v, amount, mask, synthetic)
                is PoolVenue -> poolBuyQuote(v, amount, mask)
            }
            if (amount.signum() <= 0) return ZERO
        }
        return amount
    }

    /** What selling `tokens` of the coin pays in the pay currency. */
    fun routeSellQuote(r: PumpRoute, tokens: BigInteger): BigInteger {
        val hops = inTradeOrder(r, Side.SELL)
        var amount = tokens
        for ((i, v) in hops.withIndex()) {
            val mask = hopFees(Side.SELL, i, hops.size, v is PoolVenue)
            amount = when (v) {
                is CurveVenue -> curveSellQuote(v, amount, mask)
                is PoolVenue -> poolSellQuote(v, amount, mask)
            }
            if (amount.signum() <= 0) return ZERO
        }
        return amount
    }

    /** Pay units per raw coin unit at the current prices. */
    fun routeSpotPrice(r: PumpRoute): Double = r.hops.fold(1.0) { price, v -> price * spotPrice(v) }

    fun routeIdle(r: PumpRoute): String? = r.hops.firstNotNullOfOrNull { if (it is CurveVenue) curveIdle(it.curve) else null }

    // ── instructions ────────────────────────────────────────────────────────────────

    private fun ata(owner: String, mint: String, program: String) = associatedTokenAddress(owner, mint, program)

    /** Where a v2 curve or v1 pool trade on `v` may send the protocol fee. */
    fun feeRecipients(v: PumpVenue): List<String> = when (v) {
        is CurveVenue -> if (v.curve.isMayhemMode) v.global.reservedFeeRecipients else v.global.feeRecipients
        is PoolVenue -> if (v.pool.isMayhemMode) v.globalConfig.reservedFeeRecipients else v.globalConfig.protocolFeeRecipients
    }

    /**
     * Whether the trade pays the buyback slice into an account of the listed wallet (token quotes,
     * routes) rather than to the wallet itself. Such an account must exist, so the trade creates
     * it when missing, as pump.fun's own client does.
     */
    fun buybackPaysAccount(r: PumpRoute): Boolean = !r.pay.isSol || r.hops.size > 1

    /**
     * Fee accounts for a trade on `r`, or null when pump.fun lists none Nesty knows. `pick` draws
     * an index below its argument (Math.random in the extension; fixed in tests).
     */
    fun pickRecipients(r: PumpRoute, pick: (Int) -> Int = { Random.nextInt(it) }): TradeRecipients? {
        val v = routeCoin(r)
        val onChain = when (v) {
            is CurveVenue -> v.global.buybackFeeRecipients
            is PoolVenue -> v.globalConfig.buybackFeeRecipients
        }
        val ok = BUYBACK_FEE_RECIPIENTS.filter { it in onChain }
        if (ok.isEmpty()) return null
        // Accounts of the first listed wallet are the ones pump.fun keeps open; a wallet itself
        // takes SOL at any of them.
        val buyback = if (buybackPaysAccount(r)) ok[0] else ok[pick(ok.size)]
        val variant = routeVariant(r)
        if (variant != TradeVariant.CURVE_V2 && variant != TradeVariant.POOL_V1) return TradeRecipients(buyback)
        val list = feeRecipients(v)
        if (list.isEmpty()) return null
        return TradeRecipients(buyback, list[pick(list.size)])
    }

    /** The 17 accounts of a v3 curve trade. `buyback`: the wallet on SOL, else its quote account. */
    private fun curveV3Accounts(v: CurveVenue, user: String, buyback: String): List<AccountMeta> {
        val q = v.quote
        val curve = bondingCurveAddress(v.mint)
        return listOf(
            meta(PUMP_GLOBAL),
            meta(v.mint),
            meta(q.mint),
            meta(v.tokenProgram),
            meta(q.program),
            meta(curve, true),
            meta(ata(curve, v.mint, v.tokenProgram), true),
            meta(ata(curve, q.mint, q.program), true),
            meta(user, true, true),
            meta(ata(user, v.mint, v.tokenProgram), true),
            meta(ata(user, q.mint, q.program), true),
            meta(pumpVolumeAccumulator(user), true),
            meta(PUMP_FEE_CONFIG),
            meta(buyback, true),
            meta(SYSTEM_PROGRAM),
            meta(pumpEventAuthority()),
            meta(PUMP_PROGRAM),
        )
    }

    /** The accounts of a v2 curve trade (27 for a buy, 26 for a sell). */
    private fun curveV2Accounts(v: CurveVenue, side: Side, user: String, fee: String, buyback: String): List<AccountMeta> {
        val q = v.quote
        val curve = bondingCurveAddress(v.mint)
        val vault = creatorVaultAddress(v.curve.creator)
        val accumulator = pumpVolumeAccumulator(user)
        return listOfNotNull(
            meta(PUMP_GLOBAL),
            meta(v.mint),
            meta(q.mint),
            meta(v.tokenProgram),
            meta(q.program),
            meta(ATA_PROGRAM),
            meta(fee, true),
            meta(ata(fee, q.mint, q.program), true),
            meta(buyback, true),
            meta(ata(buyback, q.mint, q.program), true),
            meta(curve, true),
            meta(ata(curve, v.mint, v.tokenProgram), true),
            meta(ata(curve, q.mint, q.program), true),
            meta(user, true, true),
            meta(ata(user, v.mint, v.tokenProgram), true),
            meta(ata(user, q.mint, q.program), true),
            meta(vault, true),
            meta(ata(vault, q.mint, q.program), true),
            meta(sharingConfigAddress(v.mint)),
            if (side == Side.BUY) meta(pumpGlobalVolumeAccumulator()) else null,
            meta(accumulator, true),
            meta(ata(accumulator, q.mint, q.program), true),
            meta(PUMP_FEE_CONFIG),
            meta(PUMP_FEES_PROGRAM),
            meta(SYSTEM_PROGRAM),
            meta(pumpEventAuthority()),
            meta(PUMP_PROGRAM),
        )
    }

    /** The 17 accounts of a v2 trade on the coin's canonical pool. */
    private fun poolV2Accounts(v: PoolVenue, user: String, buyback: String): List<AccountMeta> {
        val q = v.quote
        return listOf(
            meta(canonicalPoolAddress(v.mint, q.mint), true),
            meta(user, true, true),
            meta(AMM_GLOBAL_CONFIG),
            meta(v.mint),
            meta(q.mint),
            meta(ata(user, v.mint, v.tokenProgram), true),
            meta(ata(user, q.mint, q.program), true),
            // The pool's own vaults: the program checks them against the pool.
            meta(v.pool.poolBaseTokenAccount, true),
            meta(v.pool.poolQuoteTokenAccount, true),
            meta(v.tokenProgram),
            meta(q.program),
            meta(SYSTEM_PROGRAM),
            meta(ammVolumeAccumulator(user), true),
            meta(AMM_FEE_CONFIG),
            meta(ata(buyback, q.mint, q.program), true),
            meta(ammEventAuthority()),
            meta(PUMP_AMM_PROGRAM),
        )
    }

    /**
     * The accounts of a v1 pool trade: 23 named ones for a buy (21 for a sell), then the cashback
     * accounts, the pool's v2 account when it has a coin creator, and the buyback pair.
     */
    private fun poolV1Accounts(v: PoolVenue, side: Side, user: String, fee: String, buyback: String): List<AccountMeta> {
        val q = v.quote
        val p = v.pool
        val authority = ammCreatorVaultAuthority(p.coinCreator)
        val accumulator = ammVolumeAccumulator(user)
        val accumulatorQuote = meta(ata(accumulator, q.mint, q.program), true)
        val out = mutableListOf(
            meta(canonicalPoolAddress(v.mint, q.mint), true),
            meta(user, true, true),
            meta(AMM_GLOBAL_CONFIG),
            meta(v.mint),
            meta(q.mint),
            meta(ata(user, v.mint, v.tokenProgram), true),
            meta(ata(user, q.mint, q.program), true),
            meta(p.poolBaseTokenAccount, true),
            meta(p.poolQuoteTokenAccount, true),
            meta(fee),
            meta(ata(fee, q.mint, q.program), true),
            meta(v.tokenProgram),
            meta(q.program),
            meta(SYSTEM_PROGRAM),
            meta(ATA_PROGRAM),
            meta(ammEventAuthority()),
            meta(PUMP_AMM_PROGRAM),
            meta(ata(authority, q.mint, q.program), true),
            meta(authority),
        )
        if (side == Side.BUY) {
            out += meta(ammGlobalVolumeAccumulator())
            out += meta(accumulator, true)
        }
        out += meta(AMM_FEE_CONFIG)
        out += meta(PUMP_FEES_PROGRAM)
        if (p.isCashbackCoin) {
            out += accumulatorQuote
            if (side == Side.SELL) out += meta(accumulator, true)
        }
        if (p.coinCreator != DEFAULT_KEY) out += meta(poolV2Address(v.mint))
        out += meta(buyback)
        out += meta(ata(buyback, q.mint, q.program), true)
        return out
    }

    /** One hop of a route: its two mints, the curve or canonical pool, and that venue's vaults. */
    private fun hopAccounts(v: PumpVenue): List<AccountMeta> {
        val venue = if (v is PoolVenue) canonicalPoolAddress(v.mint, v.quote.mint) else bondingCurveAddress(v.mint)
        return listOf(
            meta(v.mint),
            meta(v.quote.mint),
            meta(venue, true),
            meta(ata(venue, v.mint, v.tokenProgram), true),
            meta(ata(venue, v.quote.mint, v.quote.program), true),
        )
    }

    /** The 16 fixed accounts of a `multi_hop_swap`, then 5 per hop in trade order. */
    private fun multiHopAccounts(r: PumpRoute, side: Side, user: String, buyback: String): List<AccountMeta> {
        val coin = routeCoin(r)
        val held = QuoteAsset(coin.mint, coin.tokenProgram)
        val (input, output) = if (side == Side.BUY) r.pay to held else held to r.pay
        return listOf(
            meta(user, true, true),
            meta(ata(user, input.mint, input.program), true),
            meta(ata(user, output.mint, output.program), true),
            meta(AMM_GLOBAL_CONFIG),
            meta(AMM_FEE_CONFIG),
            meta(ammVolumeAccumulator(user), true),
            meta(ata(buyback, r.pay.mint, r.pay.program), true),
            meta(TOKEN_PROGRAM),
            meta(TOKEN_2022_PROGRAM),
            meta(SYSTEM_PROGRAM),
            meta(ammEventAuthority()),
            meta(PUMP_AMM_PROGRAM),
            meta(PUMP_PROGRAM),
            meta(PUMP_GLOBAL),
            meta(PUMP_FEE_CONFIG),
            meta(pumpEventAuthority()),
        ) + inTradeOrder(r, side).flatMap(::hopAccounts)
    }

    /** Discriminator and trailing flag byte of each trade: amount and minimum sit between. */
    fun tradeData(variant: TradeVariant, side: Side): TradeData = when (variant) {
        // partial_fill = false: a buy the curve cannot fill fails instead.
        TradeVariant.CURVE_V3 -> if (side == Side.BUY) TradeData(IX_BUY_EXACT_QUOTE_IN_V3, 0) else TradeData(IX_SELL_V3)
        TradeVariant.CURVE_V2 -> if (side == Side.BUY) TradeData(IX_BUY_EXACT_QUOTE_IN_V2, 0) else TradeData(IX_SELL_V2)
        TradeVariant.POOL_V2 -> if (side == Side.BUY) TradeData(IX_BUY_EXACT_QUOTE_IN_V2) else TradeData(IX_SELL_V2)
        // track_volume = true, as pump.fun's client sends it: cashback accrues on tracked volume.
        TradeVariant.POOL_V1 -> if (side == Side.BUY) TradeData(IX_AMM_BUY_EXACT_QUOTE_IN, 1) else TradeData(IX_AMM_SELL)
        TradeVariant.MULTI_HOP -> TradeData(IX_MULTI_HOP_SWAP)
    }

    fun tradeProgram(variant: TradeVariant): String =
        if (variant == TradeVariant.CURVE_V3 || variant == TradeVariant.CURVE_V2) PUMP_PROGRAM else PUMP_AMM_PROGRAM

    /** The accounts of the trade instruction a route takes, for `user` and these recipients. */
    fun tradeAccounts(r: PumpRoute, side: Side, user: String, rec: TradeRecipients): List<AccountMeta> {
        val v = routeCoin(r)
        val variant = routeVariant(r)
        if (variant == TradeVariant.MULTI_HOP) return multiHopAccounts(r, side, user, rec.buyback)
        return when (v) {
            is CurveVenue -> if (variant == TradeVariant.CURVE_V2) {
                curveV2Accounts(v, side, user, rec.fee ?: "", rec.buyback)
            } else {
                val q = v.quote
                curveV3Accounts(v, user, if (q.isSol) rec.buyback else ata(rec.buyback, q.mint, q.program))
            }
            is PoolVenue -> if (variant == TradeVariant.POOL_V1) poolV1Accounts(v, side, user, rec.fee ?: "", rec.buyback) else poolV2Accounts(v, user, rec.buyback)
        }
    }

    /**
     * The recipients a trade instruction names, read back from its accounts (buyback accounts
     * resolved to their listed wallet). Null when they are not where the route puts them, or the
     * buyback is not a listed wallet's.
     */
    fun readRecipients(r: PumpRoute, keys: List<AccountMeta>): TradeRecipients? {
        val pay = r.pay
        fun at(i: Int) = keys.getOrNull(i)?.pubkey ?: ""
        fun wallet(key: String) = if (key in BUYBACK_FEE_RECIPIENTS) key else null
        fun owner(account: String) = BUYBACK_FEE_RECIPIENTS.firstOrNull { ata(it, pay.mint, pay.program) == account }
        var fee: String? = null
        val buyback: String? = when (routeVariant(r)) {
            TradeVariant.CURVE_V3 -> if (pay.isSol) wallet(at(13)) else owner(at(13))
            TradeVariant.CURVE_V2 -> wallet(at(8)).also { fee = at(6) }
            TradeVariant.POOL_V2 -> owner(at(14))
            TradeVariant.POOL_V1 -> wallet(at(keys.size - 2)).also { fee = at(9) }
            TradeVariant.MULTI_HOP -> owner(at(6))
        }
        if (buyback == null) return null
        return TradeRecipients(buyback, fee)
    }

    private fun tradeInstruction(r: PumpRoute, side: Side, user: String, amount: BigInteger, minOut: BigInteger, rec: TradeRecipients): Instruction {
        val variant = routeVariant(r)
        val d = tradeData(variant, side)
        return Instruction(
            tradeProgram(variant),
            tradeAccounts(r, side, user, rec),
            concat(d.disc, u64(amount), u64(minOut), d.flag?.let { bytesOf(it) } ?: ByteArray(0)),
        )
    }

    /** Creates the buyback wallet's account in the pay currency when the trade pays into it. */
    private fun buybackAccount(r: PumpRoute, user: String, rec: TradeRecipients): List<Instruction> =
        if (buybackPaysAccount(r)) listOf(Spl.createAtaIdempotent(rec.buyback, r.pay.mint, r.pay.program, user)) else emptyList()

    /**
     * Whether a SOL route needs the wallet's WSOL account: pools trade WSOL, and a route reads the
     * account even where a curve moves SOL itself.
     */
    private fun usesWsol(r: PumpRoute, end: PumpVenue) = r.pay.isSol && (end is PoolVenue || r.hops.size > 1)

    /** Buy for exactly `amountIn` of the pay currency (fees included), receiving at least `minOut`. */
    fun buyInstructions(r: PumpRoute, user: String, amountIn: BigInteger, minOut: BigInteger, rec: TradeRecipients): TradeIxs {
        val coin = routeCoin(r)
        val first = r.hops[0]
        val ownAta = Spl.createAtaIdempotent(user, coin.mint, coin.tokenProgram)
        val trade = tradeInstruction(r, Side.BUY, user, amountIn, minOut, rec)
        val wsol = SolAddr.wsolAccount(user)
        // SOL into a pool is wrapped first; a curve takes it as lamports.
        val wraps = r.pay.isSol && first is PoolVenue
        val opens = usesWsol(r, first)
        val ixs = ArrayList<Instruction>()
        if (opens) ixs += Spl.createAtaIdempotent(user, WSOL_MINT, TOKEN_PROGRAM)
        if (wraps) {
            ixs += Spl.transfer(user, wsol, amountIn)
            ixs += Spl.syncNative(wsol)
        }
        ixs += ownAta
        ixs += buybackAccount(r, user, rec)
        ixs += trade
        if (opens) ixs += Spl.closeAccount(wsol, user)
        return TradeIxs(ixs, amountIn)
    }

    /** Sell exactly `tokens`, receiving at least `minOut` of the pay currency. */
    fun sellInstructions(r: PumpRoute, user: String, tokens: BigInteger, minOut: BigInteger, rec: TradeRecipients): TradeIxs {
        val trade = tradeInstruction(r, Side.SELL, user, tokens, minOut, rec)
        val pay = r.pay
        if (!pay.isSol) {
            return TradeIxs(listOf(Spl.createAtaIdempotent(user, pay.mint, pay.program)) + buybackAccount(r, user, rec) + trade, ZERO)
        }
        // A pool pays WSOL, unwrapped after; a curve pays lamports.
        val opens = usesWsol(r, r.hops[0])
        val wsol = SolAddr.wsolAccount(user)
        val ixs = ArrayList<Instruction>()
        if (opens) ixs += Spl.createAtaIdempotent(user, WSOL_MINT, TOKEN_PROGRAM)
        ixs += buybackAccount(r, user, rec)
        ixs += trade
        if (opens) ixs += Spl.closeAccount(wsol, user)
        return TradeIxs(ixs, ZERO)
    }
}
