package trade.nesty.app.move

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import trade.nesty.app.data.Chain
import trade.nesty.app.data.NestyJson
import trade.nesty.app.data.Tax
import trade.nesty.app.evm.parseEther
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * What funds were moved for: a withdrawal to someone, a buy on a chain that was short, or a coin
 * the wallet holds sent to someone on its own chain.
 */
@Serializable
enum class MoveKind { WITHDRAW, BUY, SEND_COIN }

/** One transaction of a move: a plain send on one chain, or a bridge deposit paid out on another. */
@Serializable
enum class LegKind { SEND, BRIDGE }

@Serializable
enum class LegStatus {
    /** Not sent yet. */
    WAITING,

    /** Being checked and signed: nothing is known to be out yet. */
    SENDING,

    /** Sent: waiting for its chain. */
    SENT,

    /** The deposit landed: the bridge is paying it out on the other chain. */
    MOVING,
    DONE,

    /** It did not happen: refused before signing, or failed on chain. */
    FAILED,

    /** The bridge gave it back on the chain it left. */
    REFUNDED,

    /** It may have gone out, but how it ended is not known. */
    UNKNOWN,
    ;

    val final: Boolean get() = this == DONE || this == FAILED || this == REFUNDED || this == UNKNOWN
}

@Serializable
enum class MoveState {
    /** Its transactions are on their way (a buy: the move before it). */
    WORKING,

    /** A buy's funds landed, but its price is no longer fresh (or the app was closed): the user decides. */
    ASK,
    BUYING,
    DONE,

    /** A withdrawal of which only part arrived. */
    PARTIAL,
    FAILED,

    /** The user kept the moved funds on the chain instead of buying. */
    KEPT,
    ;

    val open: Boolean get() = this == WORKING || this == ASK || this == BUYING
}

@Serializable
data class MoveLeg(
    val kind: LegKind,
    /** The chain it leaves (its feed). */
    val from: String,
    /** The chain it arrives on. */
    val to: String,
    /** What leaves, in the smallest unit of `from`'s coin (of the coin sent, for a coin send). */
    val amountIn: String,
    /** What the receiver gets, in the smallest unit of `to`'s coin: as quoted for a bridge. */
    val amountOut: String? = null,
    /** Dollars leaving One Balance, and arriving, at the prices of the moment. */
    val usdIn: Double? = null,
    val usdOut: Double? = null,
    /** The bridge's fee as quoted (origin gas and its own), in dollars. */
    val quoteFeeUsd: Double? = null,
    val timeS: Double? = null,
    /** The bridge's id for the request, and the path its status is read at. */
    val requestId: String? = null,
    val statusPath: String? = null,
    val hash: String? = null,
    val status: LegStatus = LegStatus.WAITING,
    /** The bridge's own word for where it stands. */
    val bridgeStatus: String? = null,
    /** The payout's transaction on `to`. */
    val outHash: String? = null,
    /** The network fee the chain took, in dollars, once known. */
    val gasUsd: Double? = null,
    val error: String? = null,
    val sentAt: Long? = null,
    val endedAt: Long? = null,
) {
    val fromChain: Chain? get() = Chain.of(from)
    val toChain: Chain? get() = Chain.of(to)
}

/**
 * Funds moving out of One Balance, kept on the phone so it survives the app being closed. The
 * receiver's address stays here: it never goes to Nesty's server.
 */
@Serializable
data class Move(
    val id: String,
    val kind: MoveKind,
    /** The vault wallet the funds leave. */
    val walletId: String,
    val at: Long,
    /** The chain it arrives on, and who is paid there (the wallet itself before a buy). */
    val to: String,
    val recipient: String,
    /** A withdrawal: the dollars leaving One Balance. A buy: the buy's amount. A coin send: what it was worth, 0 unpriced. */
    val usd: Double,
    val legs: List<MoveLeg>,
    val state: MoveState = MoveState.WORKING,
    /** A buy or a coin send: the coin, as the screen knew it. */
    val token: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
    val supply: Double? = null,
    val hints: List<String> = emptyList(),
    /** Its listed tax, whether it trades on its curve, its launchpad: for the buy's page. */
    val tax: Tax? = null,
    val onCurve: Boolean = false,
    val launchpad: String? = null,
    val preset: Int = 0,
    /** The buy's swap, once sent, or why it was not made. */
    val buyTx: String? = null,
    val buyError: String? = null,
    val updatedAt: Long = at,
) {
    val toChain: Chain? get() = Chain.of(to)

    /** A coin send: the tokens sent, in their smallest unit. */
    val coinRaw: BigInteger get() = legs.firstOrNull()?.amountIn?.toBigIntegerOrNull() ?: BigInteger.ZERO

    /** A coin send: the tokens sent, whole. */
    val coinTokens: Double get() = BigDecimal(coinRaw).movePointLeft(decimals ?: 18).toDouble()

    /** Dollars of the legs that arrived, as quoted. */
    val arrivedUsd: Double get() = legs.filter { it.status == LegStatus.DONE }.sumOf { it.usdOut ?: 0.0 }

    /** Where a withdrawal stands from its legs once all of them ended; WORKING while one has not. */
    fun settled(): MoveState {
        if (legs.any { !it.status.final }) return MoveState.WORKING
        val done = legs.count { it.status == LegStatus.DONE }
        val unknown = legs.any { it.status == LegStatus.UNKNOWN }
        return when {
            done == legs.size -> MoveState.DONE
            done == 0 && !unknown -> MoveState.FAILED
            else -> MoveState.PARTIAL
        }
    }
}

/** Where moves are kept: a private file of the app, never backed up (allowBackup is off). */
interface MoveDisk {
    fun read(): String?
    fun write(text: String)
}

class PrefsMoveDisk(context: Context) : MoveDisk {
    private val prefs = context.getSharedPreferences("moves", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)

    // Written at once, not later: a move must still be known if the app is closed right after a send.
    override fun write(text: String) {
        prefs.edit().putString(KEY, text).commit()
    }

    private companion object {
        const val KEY = "moves"
    }
}

class MoveStore(private val disk: MoveDisk, private val now: () -> Long = System::currentTimeMillis) {
    var moves: List<Move> by mutableStateOf(read())
        private set

    private fun read(): List<Move> = disk.read()?.let { runCatching { NestyJson.decodeFromString<List<Move>>(it) }.getOrNull() } ?: emptyList()

    @Synchronized
    fun put(m: Move) {
        moves = (moves.filterNot { it.id == m.id } + m).sortedByDescending { it.at }.take(MAX)
        disk.write(NestyJson.encodeToString(moves))
    }

    /** Changes one move and keeps it; null when there is no such move. */
    @Synchronized
    fun update(id: String, change: (Move) -> Move): Move? {
        val m = moves.firstOrNull { it.id == id } ?: return null
        val n = change(m).copy(updatedAt = now())
        put(n)
        return n
    }

    @Synchronized
    fun leg(id: String, index: Int, change: (MoveLeg) -> MoveLeg): Move? =
        update(id) { m -> m.copy(legs = m.legs.mapIndexed { i, l -> if (i == index) change(l) else l }) }

    fun get(id: String): Move? = moves.firstOrNull { it.id == id }

    fun of(walletId: String?): List<Move> = if (walletId == null) emptyList() else moves.filter { it.walletId == walletId }

    companion object {
        const val MAX = 200
    }
}

/** A chain's own coin in One Balance: what the wallet holds there, and a whole coin's price. */
data class Funds(val chain: Chain, val raw: BigInteger, val price: Double) {
    val usd: Double get() = usdOf(raw)

    fun usdOf(units: BigInteger): Double = BigDecimal(units).movePointLeft(chain.decimals).toDouble() * price

    /** Smallest units worth `usd`, rounded down. */
    fun unitsOf(usd: Double): BigInteger =
        BigDecimal(usd).divide(BigDecimal(price), MathContext.DECIMAL128).movePointRight(chain.decimals).setScale(0, RoundingMode.DOWN).toBigInteger()
}

/** One chain's share of a withdrawal. */
data class Part(val chain: Chain, val raw: BigInteger, val usd: Double)

/** The arithmetic of moving funds, with no reads: the screens and the Mover share it. */
object Plans {
    /** A withdrawal's share smaller than this is not bridged: the bridge refuses dust. */
    const val MIN_BRIDGE_USD = 0.5

    /** Cents left over are not a shortfall. */
    private const val SLACK_USD = 0.005

    /**
     * What stays on a chain when funds leave it, for its network fees: the extension's bridge
     * reserve (five times as much where the bridge needs a swap first, as on Elysium), and on
     * Solana the rent a wallet must keep to exist plus two fees.
     */
    fun reserve(chain: Chain, rentMin: BigInteger, solFee: BigInteger): BigInteger = when (chain) {
        Chain.ETHEREUM -> parseEther("0.002")
        Chain.BSC -> parseEther("0.0005")
        Chain.ELYSIUM -> parseEther("0.001")
        Chain.SOLANA -> rentMin + solFee * BigInteger.TWO
        else -> parseEther("0.0002")
    }

    /** What may leave a chain: its balance less what stays there. */
    fun movable(f: Funds, reserve: BigInteger): BigInteger = (f.raw - reserve).max(BigInteger.ZERO)

    /**
     * Whether funds can leave `chain` for another chain in this build. Elysium's coin is not held
     * by the bridge: leaving takes a swap into USDC first, which this build does not do yet.
     */
    fun canLeave(chain: Chain): Boolean = chain != Chain.ELYSIUM

    /** The chain id the bridge names a chain by. */
    fun bridgeId(chain: Chain): Long = if (chain == Chain.SOLANA) Relay.SOLANA_CHAIN_ID else chain.feed.toLong()

    /** The chains a withdrawal arriving on `to` takes from, in order: `to` first, then the fullest. */
    fun sources(to: Chain, funds: List<Funds>, reserves: Map<Chain, BigInteger>, only: Chain?): List<Pair<Funds, BigInteger>> {
        fun movableOf(f: Funds) = movable(f, reserves[f.chain] ?: BigInteger.ZERO)
        val usable = funds.filter { f -> f.price > 0 && (f.chain == to || canLeave(f.chain)) }
        val picked = if (only != null) usable.filter { it.chain == only } else usable
        val own = picked.filter { it.chain == to }
        val others = picked.filter { it.chain != to }.sortedByDescending { it.usdOf(movableOf(it)) }
        return (own + others).map { it to movableOf(it) }
            .filter { (f, m) -> m.signum() > 0 && (f.chain == to || f.usdOf(m) >= MIN_BRIDGE_USD) }
    }

    /** The most a withdrawal arriving on `to` can take, in dollars. */
    fun withdrawMax(to: Chain, funds: List<Funds>, reserves: Map<Chain, BigInteger>, only: Chain?): Double =
        sources(to, funds, reserves, only).sumOf { (f, m) -> f.usdOf(m) }

    /**
     * Where a withdrawal of `usd` comes from: the arriving chain first, then the fullest others,
     * each giving all it can before the next. Null when they do not cover it.
     */
    fun withdrawParts(usd: Double, to: Chain, funds: List<Funds>, reserves: Map<Chain, BigInteger>, only: Chain?): List<Part>? {
        if (usd <= 0 || usd.isNaN()) return null
        var left = usd
        val parts = mutableListOf<Part>()
        for ((f, m) in sources(to, funds, reserves, only)) {
            if (left <= SLACK_USD) break
            val all = f.usdOf(m)
            if (all <= left + SLACK_USD) {
                parts += Part(f.chain, m, all)
                left -= all
            } else {
                parts += Part(f.chain, f.unitsOf(left).min(m), left)
                left = 0.0
            }
        }
        return if (left > SLACK_USD) null else parts
    }

    /**
     * What a buy of `wei` on a chain holding `have` needs moved there first; zero when the chain
     * pays it itself. The chain must keep what the buy sheet's Max leaves: 1% for the price moving,
     * twice the fee estimate, and the buy's own reserve (on Solana the rent of the accounts it opens).
     */
    fun buyShortfall(have: BigInteger, wei: BigInteger, feeWei: BigInteger, reserve: BigInteger): BigInteger {
        val need = (wei + feeWei * BigInteger.TWO + reserve) * BigInteger.valueOf(100)
        val needed = need.divide(BigInteger.valueOf(99)) + if (need.mod(BigInteger.valueOf(99)).signum() > 0) BigInteger.ONE else BigInteger.ZERO
        return (needed - have).max(BigInteger.ZERO)
    }

    /**
     * The chain that moves a buy's shortfall worth `shortUsd`: the fullest other chain that alone
     * covers it with room for the bridge's fee (a single move, never a mix). Null when none does.
     */
    fun buySource(to: Chain, shortUsd: Double, funds: List<Funds>, reserves: Map<Chain, BigInteger>): Pair<Funds, BigInteger>? =
        funds.filter { it.chain != to && canLeave(it.chain) && it.price > 0 }
            .map { it to movable(it, reserves[it.chain] ?: BigInteger.ZERO) }
            .sortedByDescending { (f, m) -> f.usdOf(m) }
            .firstOrNull { (f, m) -> f.usdOf(m) >= roomFor(shortUsd) }

    /** What a source must hold to move `usd`: the amount, 3% and ten cents for the bridge. */
    fun roomFor(usd: Double): Double = usd * 1.03 + 0.10

    /** The most a source holding `movableUsd` can move, the bridge's room taken out. */
    fun movableUsd(movableUsd: Double): Double = ((movableUsd - 0.10) / 1.03).coerceAtLeast(0.0)

    /** The most a move may take in, as confirmed: what was shown and 1%, never more than the source can give. */
    fun cap(shownIn: BigInteger, movable: BigInteger): BigInteger = (shownIn * BigInteger.valueOf(101) / BigInteger.valueOf(100)).min(movable)

    /**
     * What a move cost, for the buy's cost basis: what left one chain less what arrived on the
     * other, at the prices of the moment, plus the network fee of the deposit. Falls back on the
     * bridge's quoted fee when a price is missing.
     */
    fun moveCostUsd(leg: MoveLeg): Double {
        val spread = if (leg.usdIn != null && leg.usdOut != null) (leg.usdIn - leg.usdOut).coerceAtLeast(0.0) else null
        return (spread?.let { it + (leg.gasUsd ?: 0.0) } ?: leg.quoteFeeUsd ?: 0.0).coerceAtLeast(0.0)
    }
}
