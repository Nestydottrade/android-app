package trade.nesty.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import trade.nesty.app.data.AppGraph

class NestyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { AppGraph.imageHttp })) }
                .build()
        }
    }
}
