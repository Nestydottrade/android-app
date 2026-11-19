package trade.nesty.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import trade.nesty.app.i18n.tr

/** One answer to a fetch: its status and its body, closed together. */
class ApkBody(val status: Int, val stream: InputStream?, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        runCatching { stream?.close() }
        onClose()
    }
}

/** Where an APK's bytes come from: the network, or a fake in tests. `from`: the bytes already here. */
fun interface ApkSource {
    fun open(url: String, from: Long): ApkBody
}

/**
 * Over the network, as it is: no compression (it would shift a resumed range) and no
 * redirects, so the file can only come from the address the release names.
 */
class HttpApkSource(http: OkHttpClient) : ApkSource {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override fun open(url: String, from: Long): ApkBody {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        val response = client.newCall(request).execute()
        return ApkBody(response.code, response.body.byteStream()) { response.close() }
    }
}

/**
 * Fetches an APK into a part file, going on from what an earlier try left there, and never
 * past the size the release gave.
 */
class ApkFetch(private val source: ApkSource) {
    /**
     * Fills `part` up to `size` bytes and returns how many it holds. `progress` hears the bytes
     * so far; `goOn` false stops it (the screen left, the app is closing). Throws on a refused
     * answer, a stop before the end, or more bytes than published.
     */
    fun fetch(url: String, part: File, size: Long, progress: (Long) -> Unit = {}, goOn: () -> Boolean = { true }): Long {
        part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        if (have > size) {
            part.delete()
            have = 0
        }
        if (have == size) return have
        source.open(url, have).use { body ->
            when (body.status) {
                206 -> if (have == 0L) throw IOException(tr("A part came back for a whole file"))
                200 -> have = 0
                416 -> {
                    // What is on disk no longer fits the file served: start again next time.
                    part.delete()
                    throw IOException(tr("The server refused the rest of the file"))
                }
                else -> throw IOException("HTTP ${body.status}")
            }
            val input = body.stream ?: throw IOException(tr("No file came back"))
            FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                var told = have
                progress(have)
                while (goOn()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (have + n > size) {
                        out.close()
                        part.delete()
                        throw IOException(tr("The file is larger than published"))
                    }
                    out.write(buf, 0, n)
                    have += n
                    if (have - told >= STEP_BYTES) {
                        progress(have)
                        told = have
                    }
                }
            }
            progress(have)
        }
        if (have < size && goOn()) throw IOException(tr("The download stopped before the end"))
        return have
    }

    companion object {
        private const val STEP_BYTES = 256 * 1024L
    }
}
