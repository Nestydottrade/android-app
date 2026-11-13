package trade.nesty.app

import trade.nesty.app.data.NestyJson

/** Real answers of https://nesty.trade/api/v1/, saved on 2026-11-13 (src/test/resources/fixtures). */
object Fixtures {
    fun text(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name.json")) { "No fixture $name" }.readText()

    inline fun <reified T> read(name: String): T = NestyJson.decodeFromString<T>(text(name))
}
