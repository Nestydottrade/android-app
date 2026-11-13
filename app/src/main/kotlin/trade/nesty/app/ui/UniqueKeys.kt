package trade.nesty.app.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * Keys for a lazy list that stay unique when the data repeats one, such as one transaction with two
 * buys of a coin. Two rows with one key crash the whole app the moment both are composed, which is
 * often only on a scroll. The first row keeps the plain key, so it keeps its place as before; a later
 * repeat gets a count. Data keys never contain '#'.
 */
fun <T> uniqueKeys(list: List<T>, key: (T) -> Any): List<String> {
    val seen = HashMap<String, Int>()
    return list.map { item ->
        val k = key(item).toString()
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) k else "$k#$n"
    }
}

/** A key by index for `items(count, key = ...)`, unique as [uniqueKeys] makes it. */
fun <T> keyAt(list: List<T>, key: (T) -> Any): (Int) -> Any {
    val keys = uniqueKeys(list, key)
    return { keys[it] }
}

/** `items(list, key = ...)` with keys made unique by [uniqueKeys]. Every keyed list in the app goes through this. */
inline fun <T> LazyListScope.uniqueItems(
    list: List<T>,
    noinline key: (T) -> Any,
    crossinline content: @Composable LazyItemScope.(T) -> Unit,
) {
    val keys = uniqueKeys(list, key)
    items(list.size, key = { keys[it] }) { content(list[it]) }
}
