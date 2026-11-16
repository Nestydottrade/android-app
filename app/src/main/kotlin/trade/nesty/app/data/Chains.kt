package trade.nesty.app.data

/**
 * The chains the server serves, in the order the screens list them. Arbitrum is a chain of the
 * extension only and is left out: the server does not serve it.
 */
enum class Chain(
    /** The server's name for it in a path: an EVM chain id, or `solana`. */
    val feed: String,
    /** As a list names it. */
    val label: String,
    /** As a sentence names it. */
    val title: String,
    val native: String,
    val explorer: String,
    /** The chain's dot: one colour, or two for a gradient. */
    val dot: Long,
    val dot2: Long? = null,
    /** Whether Nesty's book reads it: holders, tags, signals, the Leaderboard, stats. */
    val book: Boolean = true,
) {
    BSC("56", "BNB", "BNB Chain", "BNB", "https://bscscan.com", 0xFFF0B90B),
    BASE("8453", "Base", "Base", "ETH", "https://basescan.org", 0xFF3D7BFF),
    ROBINHOOD("4663", "Robinhood", "Robinhood", "ETH", "https://robin.etherscan.io", 0xFFC6F432),
    ETHEREUM("1", "Ethereum", "Ethereum", "ETH", "https://etherscan.io", 0xFF8A9BFF),
    ELYSIUM("99901", "Elysium", "Elysium", "HYPE", "https://elysium.kinetiq.xyz", 0xFF97FCE4),
    SOLANA("solana", "Solana", "Solana", "SOL", "https://solscan.io", 0xFF9945FF, 0xFF14F195, book = false);

    val isEvm: Boolean get() = this != SOLANA

    /** Decimals of the chain's own coin: wei on the EVM chains, lamports on Solana. */
    val decimals: Int get() = if (this == SOLANA) 9 else 18

    /** The explorer's page of an address or token. */
    fun addressUrl(address: String): String =
        if (this == SOLANA) "$explorer/account/$address" else "$explorer/address/$address"

    fun tokenUrl(address: String): String =
        if (this == SOLANA) "$explorer/token/$address" else "$explorer/token/$address"

    /** The explorer's page of a transaction: /tx/ on every chain's explorer, Solscan too. */
    fun txUrl(hash: String): String = "$explorer/tx/$hash"

    companion object {
        fun of(feed: String?): Chain? = entries.firstOrNull { it.feed == feed }

        /** The slugs the updates list names chains by. */
        fun ofSlug(slug: String): Chain? = when (slug.lowercase()) {
            "bsc", "bnb" -> BSC
            "base" -> BASE
            "robinhood" -> ROBINHOOD
            "ethereum", "eth" -> ETHEREUM
            "elysium" -> ELYSIUM
            "solana", "sol" -> SOLANA
            else -> null
        }

        val booked: List<Chain> get() = entries.filter { it.book }
    }
}
