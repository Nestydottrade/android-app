package trade.nesty.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Where four.meme's rest is kept, so a restart does not read inside it: the phone's preferences, or memory in tests. */
interface RestDisk {
    fun restUntil(): Long
    fun rest(until: Long)
}

class PrefsRestDisk(context: Context) : RestDisk {
    private val prefs = context.getSharedPreferences("fourmeme", Context.MODE_PRIVATE)
    override fun restUntil(): Long = prefs.getLong("rest", 0)
    override fun rest(until: Long) = prefs.edit().putLong("rest", until).apply()
}

/**
 * four.meme's own record of a coin it launched, read for the coin's picture. four.meme refuses
 * servers, so Nesty's server has no picture for its coins; the phone reads the record itself, as
 * the site's pages do in the browser. Only the coin's address is sent, and nothing is logged.
 * One read a second, the whole app over: four.meme refuses an address that reads in bursts (a 429)
 * for 15 minutes, and no read is made in that time.
 */
class FourMemeRecords(
    private val http: OkHttpClient,
    private val disk: RestDisk? = null,
    private val gapMs: Long = 1_000,
    private val restMs: Long = 15 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val url: HttpUrl = URL.toHttpUrl(),
) {
    /** four.meme asked for a rest: no read until it ends. */
    class Resting : IOException("four.meme is resting")

    private val turn = Mutex()
    /** When the last read was made. */
    private var lastAt: Long? = null
    // A rest kept longer than one is a clock that moved: it ends a whole rest from now at the latest.
    @Volatile
    private var restUntil = minOf(disk?.restUntil() ?: 0L, clock() + restMs)

    /**
     * The coin's picture as four.meme keeps it: null when the record is another coin's or names no
     * usable picture; an exception when there is no answer (offline, refused, resting).
     */
    suspend fun image(token: String): String? {
        turn.withLock {
            if (clock() < restUntil) throw Resting()
            val wait = lastAt?.let { it + gapMs - clock() } ?: 0L
            if (wait > 0) pause(wait)
            if (clock() < restUntil) throw Resting()
            lastAt = clock()
        }
        val request = Request.Builder()
            .url(url.newBuilder().setQueryParameter("address", token).build())
            .header("Accept", "application/json")
            .build()
        val text = http.newCall(request).await().use { r ->
            if (r.code == 429) {
                rest()
                throw Resting()
            }
            if (!r.isSuccessful) throw IOException("four.meme: HTTP ${r.code}")
            withContext(Dispatchers.IO) { r.peekBody(MAX_BYTES).string() }
        }
        return pictureIn(text, token)
    }

    private fun rest() {
        restUntil = clock() + restMs
        disk?.rest(restUntil)
    }

    companion object {
        const val URL = "https://four.meme/meme-api/v1/private/token/get/v2"
        private const val MAX_BYTES = 256L * 1024

        /**
         * The picture a record of `token` names. Null for another coin's record, or a picture that
         * is no web address; an exception for no record, or one without its picture yet.
         */
        fun pictureIn(text: String, token: String): String? {
            val o = runCatching { NestyJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw IOException("four.meme: answer off its shape")
            if ((o["code"] as? JsonPrimitive)?.intOrNull != 0) throw IOException("four.meme: no record")
            val d = o["data"] as? JsonObject ?: throw IOException("four.meme: no record")
            val address = (d["address"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("four.meme: no record")
            if (!address.equals(token, ignoreCase = true)) return null
            val image = (d["image"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            // A record without its picture yet: four.meme may add it.
            if (image.isEmpty()) throw IOException("four.meme: no picture yet")
            return image.takeIf { it.length <= 600 && (it.startsWith("https://") || it.startsWith("http://")) }
        }
    }
}
