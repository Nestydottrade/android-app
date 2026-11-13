package trade.nesty.app.data

import android.content.Context
import okhttp3.OkHttpClient
import trade.nesty.app.BuildConfigLite
import java.util.concurrent.TimeUnit

/** The app's few long-lived pieces. */
object AppGraph {
    lateinit var api: NestyApi
        private set
    lateinit var settings: Settings
        private set
    lateinit var imageHttp: OkHttpClient
        private set

    fun init(context: Context) {
        val agent = "Nesty-Android/${BuildConfigLite.VERSION}"
        val base = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
            }
            .build()
        // A stream sends a comment every 15 seconds; 45 seconds of silence means it is gone.
        val stream = base.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
        imageHttp = base.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
        api = NestyApi(base, stream)
        settings = Settings(context.applicationContext)
    }
}
