package trade.nesty.app

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import trade.nesty.app.data.NestyApi

/**
 * The client against a fake server: `answer` gives a status and a body for each URL asked, and
 * every URL asked is added to `asked`. Nothing leaves the machine.
 */
fun fakeApi(asked: MutableList<String>, answer: (String) -> Pair<Int, String>): NestyApi {
    val fake = Interceptor { chain ->
        val url = chain.request().url.toString()
        synchronized(asked) { asked += url }
        val (code, body) = answer(url)
        val type = if (url.contains("/stream/")) "text/event-stream" else "application/json"
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .header("retry-after", "5")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
    }
    val http = OkHttpClient.Builder().addInterceptor(fake).build()
    return NestyApi(http, http)
}
