package trade.nesty.app.evm

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger

data class Routed(val pool: PoolRef, val venue: Venue, val out: BigInteger)

data class RouteChoice(val route: Routed?, val refOut: BigInteger?)

/**
 * Discovers pools across venues (cached a minute) and picks the best pool per amount, ported
 * from the extension's routing.ts without its aggregator fallback.
 */
class Routing(private val venues: List<Venue> = EvmVenues.all) {
    private class FullRound(val at: Long, val won: Set<String>, val routed: List<Boolean>)

    private val mutex = Mutex()
    private val pools = HashMap<String, Pair<Long, List<PoolRef>>>()

    /** Last quote per (chain, token, side, amount), so a tap can reuse the sheet's quote. */
    private val recent = HashMap<String, Pair<Long, Routed>>()

    /** Each refresh's last full round, by chain, token, side and amounts. */
    private val rounds = LinkedHashMap<String, FullRound>()

    private fun quoteKey(chainId: Long, token: String, side: Side, amount: BigInteger) = "$chainId:${token.lowercase()}:$side:$amount"

    private fun poolKey(p: PoolRef) = "${p.venueId}:${p.id}"

    /** A route quoted within `maxAgeMs`, or null. */
    suspend fun cached(ctx: VenueCtx, token: String, side: Side, amount: BigInteger, maxAgeMs: Long): Routed? = mutex.withLock {
        recent[quoteKey(ctx.chain.id, token, side, amount)]?.takeIf { ctx.now() - it.first <= maxAgeMs }?.second
    }

    fun venue(id: String): Venue? = venues.firstOrNull { it.id == id }

    /** Why no pool can trade `token` right now, when a venue knows; else null. */
    suspend fun whyNone(ctx: VenueCtx, token: String): String? = coroutineScope {
        venues.filter { it.supports(ctx.chain.id) }.map { v -> async { orNull { v.idle(ctx, token) } } }.awaitAll()
            .firstOrNull { !it.isNullOrEmpty() }
    }

    suspend fun discover(ctx: VenueCtx, token: String, fresh: Boolean = false): List<PoolRef> {
        val key = "${ctx.chain.id}:${token.lowercase()}"
        if (!fresh) mutex.withLock { pools[key] }?.let { (at, found) -> if (ctx.now() - at < POOL_TTL_MS) return found }
        val found = coroutineScope {
            venues.filter { it.supports(ctx.chain.id) }.map { v -> async { orNull { v.discover(ctx, token) }.orEmpty() } }.awaitAll().flatten()
        }
        mutex.withLock { pools[key] = ctx.now() to found }
        return found
    }

    /** For each amount, the pool with the highest output (null if no pool could quote it). */
    suspend fun best(ctx: VenueCtx, token: String, side: Side, amounts: List<BigInteger>): List<Routed?> =
        bestWithRef(ctx, token, side, null, amounts).map { it.route }

    /**
     * Like best(), but also quotes a tiny reference amount on every pool in the same round, so
     * price impact can be computed against the chosen pool. `refresh`: the same amounts asked
     * again, as the sheet does every few seconds; for a while only the last winners are quoted.
     */
    suspend fun bestWithRef(
        ctx: VenueCtx,
        token: String,
        side: Side,
        ref: BigInteger?,
        amounts: List<BigInteger>,
        refresh: Boolean = false,
    ): List<RouteChoice> {
        if (amounts.isEmpty()) return emptyList()
        val found = discover(ctx, token)
        val chosen = if (refresh) pickAgain(ctx, token, side, ref, amounts, found) else pick(ctx, side, ref, amounts, found)
        val at = ctx.now()
        mutex.withLock {
            chosen.forEachIndexed { i, c -> c.route?.let { recent[quoteKey(ctx.chain.id, token, side, amounts[i])] = at to it } }
        }
        return chosen
    }

    private suspend fun pick(ctx: VenueCtx, side: Side, ref: BigInteger?, amounts: List<BigInteger>, pools: List<PoolRef>): List<RouteChoice> {
        if (pools.isEmpty() || amounts.isEmpty()) return amounts.map { RouteChoice(null, null) }
        val all = if (ref != null) listOf(ref) + amounts else amounts
        val offset = if (ref != null) 1 else 0
        val quotes = coroutineScope {
            pools.map { pool ->
                async {
                    val venue = venue(pool.venueId) ?: return@async all.map { null }
                    orNull { venue.quote(ctx, pool, side, all) } ?: all.map { null }
                }
            }.awaitAll()
        }
        return amounts.indices.map { i ->
            var route: Routed? = null
            var refOut: BigInteger? = null
            pools.forEachIndexed { p, pool ->
                val out = quotes[p].getOrNull(i + offset)
                val venue = venue(pool.venueId)
                if (venue != null && out != null && out.signum() > 0 && (route == null || out > route.out)) {
                    route = Routed(pool, venue, out)
                    refOut = if (ref != null) quotes[p].getOrNull(0) else null
                }
            }
            RouteChoice(route, refOut)
        }
    }

    /** pick() for a refresh: the last full round's winners alone, while that round holds. */
    private suspend fun pickAgain(
        ctx: VenueCtx,
        token: String,
        side: Side,
        ref: BigInteger?,
        amounts: List<BigInteger>,
        pools: List<PoolRef>,
    ): List<RouteChoice> {
        val key = "${ctx.chain.id}:${token.lowercase()}:$side:${ref ?: ""}:${amounts.joinToString(",")}"
        val last = mutex.withLock { rounds[key] }
        if (last != null && ctx.now() - last.at < FULL_ROUND_MS) {
            val winners = pools.filter { poolKey(it) in last.won }
            if (winners.isNotEmpty()) {
                val again = pick(ctx, side, ref, amounts, winners)
                if (again.withIndex().all { (i, r) -> r.route != null || !last.routed[i] }) return again
            }
        }
        val full = pick(ctx, side, ref, amounts, pools)
        mutex.withLock {
            rounds.remove(key)
            if (rounds.size >= ROUNDS_KEPT) rounds.keys.firstOrNull()?.let { rounds.remove(it) }
            rounds[key] = FullRound(ctx.now(), full.mapNotNull { r -> r.route?.let { poolKey(it.pool) } }.toSet(), full.map { it.route != null })
        }
        return full
    }

    /** Quote one pool again (right before sending). */
    suspend fun requote(ctx: VenueCtx, pool: PoolRef, side: Side, amount: BigInteger): BigInteger? {
        val venue = venue(pool.venueId) ?: return null
        return orNull { venue.quote(ctx, pool, side, listOf(amount)) }?.firstOrNull()
    }

    companion object {
        const val POOL_TTL_MS = 60_000L
        const val FULL_ROUND_MS = 15_000L
        const val ROUNDS_KEPT = 256
    }
}
