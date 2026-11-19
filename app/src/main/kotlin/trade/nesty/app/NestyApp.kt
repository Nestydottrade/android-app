package trade.nesty.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import trade.nesty.app.data.AppGraph
import trade.nesty.app.i18n.I18n

class NestyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        AppGraph.init(this)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { AppGraph.imageHttp })) }
                .build()
        }
    }
}
