package trade.nesty.app.update

import android.content.Context

/** The release build asks nesty.trade only: no test server can be named. */
object UpdateHost {
    @Suppress("UNUSED_PARAMETER")
    fun testBase(context: Context): String? = null
}
