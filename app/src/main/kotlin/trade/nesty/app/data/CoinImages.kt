package trade.nesty.app.data

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** /image: a launchpad coin's picture as its launchpad names it; null where none is found. */
@Serializable
data class ImageAnswer(val image: String? = null)

/**
 * Pictures of the coins a list comes without, as the signals do: each read from Nesty's server
 * once its row is drawn, four at a time, and kept while the app runs. Only the coin's address is
 * sent. A coin the server finds none for keeps its letter and is asked again after a while, as
 * its launchpad may name one later. Called on the main thread, as a row's effect.
 */
class CoinImages(
    private val api: NestyApi,
    private val retryMs: Long = 20_000,
    private val noneMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val found = mutableStateMapOf<String, String>()
    /** When the server last found none, by coin. */
    private val none = HashMap<String, Long>()
    /** Being read: a second row of the same coin leaves it to the first. */
    private val reading = HashSet<String>()
    private val gate = Semaphore(4)

    private fun key(chain: Chain, token: String) = "${chain.feed}:${token.lowercase()}"

    /** The coin's picture once read; null until then, or where there is none. */
    fun of(chain: Chain, token: String): String? = found[key(chain, token)]

    /**
     * Reads the coin's picture unless it is known or the server found none a short while ago.
     * A read with no answer is made twice more while the row is drawn, each wait longer.
     */
    suspend fun ask(chain: Chain, token: String) {
        val k = key(chain, token)
        if (k in found) return
        val gone = none[k]
        if (gone != null && clock() - gone < noneMs) return
        if (!reading.add(k)) return
        try {
            for (attempt in 1..3) {
                try {
                    val image = gate.withPermit { api.image(chain, token) }
                    if (image.isNullOrBlank()) {
                        none[k] = clock()
                    } else {
                        found[k] = image
                        none.remove(k)
                    }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // A coin or chain the server cannot read: asking again would not help.
                    if (e.status in 400..499 && !e.busy) {
                        none[k] = clock()
                        return
                    }
                } catch (_: Exception) {
                }
                if (attempt < 3) delay(attempt * retryMs)
            }
        } finally {
            reading.remove(k)
        }
    }
}
