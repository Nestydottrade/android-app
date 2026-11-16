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
 * What the server's own answer for a coin said, for a screen that has one without a picture: the
 * coin's launchpad as the server names it ("four.meme", "Flap", …), or null for a coin of none.
 */
data class Served(val launchpad: String?)

/**
 * Pictures of the coins a screen has none for (the signals, the positions, a wallet's coins, a
 * launchpad coin whose picture the server could not read): each read once its row is drawn and
 * kept while the app runs, one cache for every screen. Only the coin's address is sent.
 *
 * An EVM coin's picture is what its launchpad names (/image, read by the server to the end), else
 * its listing's (the coin's answer, which also names its launchpad), else, for a four.meme coin,
 * four.meme's own record, read by the phone as four.meme refuses servers. A Solana coin's comes from
 * its coin answer, as /image reads EVM launchpads only. A coin found with none keeps its letter
 * and is asked again after a while, as its launchpad may name one later. Server reads go four at
 * a time. Called on the main thread, as a row's effect.
 */
class CoinImages(
    private val api: NestyApi,
    private val fourMeme: FourMemeRecords? = null,
    private val retryMs: Long = 20_000,
    private val noneMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val found = mutableStateMapOf<String, String>()
    /** When none was last found, by coin. */
    private val none = HashMap<String, Long>()
    /** Being read: a second row of the same coin leaves it to the first. */
    private val reading = HashSet<String>()
    private val gate = Semaphore(4)

    // A Solana mint's letters are not all of one case, so only EVM addresses are folded.
    private fun key(chain: Chain, token: String) = "${chain.feed}:${if (chain.isEvm) token.lowercase() else token}"

    /** The coin's picture once read; null until then, or where there is none. */
    fun of(chain: Chain, token: String): String? = found[key(chain, token)]

    /**
     * Reads the coin's picture unless it is known or none was found a short while ago. `served`:
     * what the server's answer for it said, when the screen has one: only the reads it could not
     * have made are made. A read with no answer is made twice more while the row is drawn, each
     * wait longer; while four.meme rests, it waits for the row to be drawn again.
     */
    suspend fun ask(chain: Chain, token: String, served: Served? = null) {
        val k = key(chain, token)
        if (k in found) return
        val gone = none[k]
        if (gone != null && clock() - gone < noneMs) return
        if (!reading.add(k)) return
        try {
            for (attempt in 1..3) {
                try {
                    val image = picture(chain, token, served)
                    if (image == null) {
                        none[k] = clock()
                    } else {
                        found[k] = image
                        none.remove(k)
                    }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (_: FourMemeRecords.Resting) {
                    return
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

    /** The coin's picture from the first place that has one; null for none, an exception for no answer. */
    private suspend fun picture(chain: Chain, token: String, served: Served?): String? {
        if (!chain.isEvm) return fromServer { api.token(chain, token).image }
        val launchpad = if (served != null) {
            served.launchpad
        } else {
            fromServer { api.image(chain, token) }?.let { return it }
            val coin = gate.withPermit { api.token(chain, token) }
            coin.image.usable()?.let { return it }
            // The server has none: only four.meme's own record may.
            if (!byFourMeme(chain, coin.launchpad)) return null
            coin.launchpad
        }
        return when {
            byFourMeme(chain, launchpad) -> fourMeme?.image(token)
            // A launchpad coin the screen has no picture for: the server reads its metadata to the end.
            launchpad != null -> fromServer { api.image(chain, token) }
            // A coin of no launchpad: its listing names none.
            else -> null
        }
    }

    private suspend fun fromServer(read: suspend () -> String?): String? = gate.withPermit { read() }.usable()

    private fun String?.usable(): String? = this?.takeIf { it.isNotBlank() }

    private fun byFourMeme(chain: Chain, launchpad: String?): Boolean =
        chain == Chain.BSC && (launchpad.equals("four.meme", ignoreCase = true) || launchpad.equals("fourmeme", ignoreCase = true))
}
