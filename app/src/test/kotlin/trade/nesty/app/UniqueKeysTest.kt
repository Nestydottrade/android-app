package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.ui.keyAt
import trade.nesty.app.ui.uniqueKeys
import java.io.File

/**
 * Lazy list keys. A key used twice crashed the app on the Signals page as soon as it was scrolled
 * (the same coin's signal at two sizes); the same can happen to any list whose data repeats a row.
 */
class UniqueKeysTest {
    @Test
    fun repeatsGetACountAndTheFirstKeepsItsKey() {
        val keys = uniqueKeys(listOf("a", "b", "a", "a", "c")) { it }
        assertEquals(listOf("a", "b", "a#2", "a#3", "c"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun keysFromAnyTypeBecomeText() {
        assertEquals(listOf("1", "2", "1#2"), uniqueKeys(listOf(1L, 2L, 1L)) { it })
        val byIndex = keyAt(listOf("x", "x")) { it }
        assertEquals("x", byIndex(0))
        assertEquals("x#2", byIndex(1))
    }

    @Test
    fun everyKeyedListGoesThroughTheHelper() {
        val ui = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val found = ui.walkTopDown().filter { it.extension == "kt" && it.name != "UniqueKeys.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (Regex("""(?<![\w.])items(Indexed)?\(""").containsMatchIn(line) && line.contains("key = {")) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue("Lazy list keys not made unique (use uniqueItems or keyAt): $found", found.isEmpty())
    }
}
