package trade.nesty.app.evm

import java.math.BigDecimal
import java.math.BigInteger

/** What a gas preset sets: a tip in gwei and the max-fee multiplier over the base fee. */
data class GasPreset(val priorityFeeGwei: String, val maxFeeMultiplier: Double, val slippageBps: Int) {
    val priorityFeeWei: BigInteger get() = BigDecimal(priorityFeeGwei).movePointRight(9).toBigIntegerExact()
}

data class Stable(val address: String, val symbol: String, val decimals: Int?)

/** A hop token reached through a native v4 pool (Flaunch's flETH). */
data class V4Bridge(val fee: Int, val tickSpacing: Int, val hooks: String)

data class HopToken(val address: String, val symbol: String, val v4Bridge: V4Bridge? = null)

enum class FeeModel { EIP1559, ARBITRUM }

data class UniswapDeployment(
    val universalRouter: String,
    val v2Factory: String,
    val v3Factory: String,
    val v3QuoterV2: String,
    val v4PoolManager: String,
    val v4PositionManager: String,
    val v4StateView: String,
    val v4Quoter: String,
)

/**
 * An EVM chain as the engine trades on it, from the extension's shared/chains/definitions.ts.
 * Addresses lowercase.
 */
data class EvmChain(
    val id: Long,
    val name: String,
    val nativeSymbol: String,
    val wrappedNative: String,
    val stablecoins: List<Stable>,
    val hopTokens: List<HopToken>,
    val nestyRouter: String?,
    val rpcUrls: List<String>,
    val explorer: String,
    val feeModel: FeeModel,
    /** The private RPC a trade goes to when MEV protection is on; null where there is no mempool. */
    val mevRpc: String?,
    val quickBuyAmounts: List<String>,
    val p1: GasPreset,
    val p2: GasPreset,
    val p3: GasPreset,
    val uniswap: UniswapDeployment,
) {
    /** Stablecoins and hop tokens: the assets a route may pass through. */
    val routeAssets: List<Pair<String, String>> get() = stablecoins.map { it.address to it.symbol } + hopTokens.map { it.address to it.symbol }
}

object EvmChains {
    private const val NESTY_ROUTER = "0xfdf24705a48f08558a6eed9b72d24cf3bbce07e5"
    const val MULTICALL3 = "0xca11bde05977b3631167028862be2a173976ca11"

    val ethereum = EvmChain(
        id = 1,
        name = "Ethereum",
        nativeSymbol = "ETH",
        wrappedNative = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
        stablecoins = listOf(
            Stable("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", 6),
            Stable("0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6),
        ),
        hopTokens = emptyList(),
        nestyRouter = NESTY_ROUTER,
        rpcUrls = listOf("https://ethereum-rpc.publicnode.com"),
        explorer = "https://etherscan.io",
        feeModel = FeeModel.EIP1559,
        mevRpc = "https://rpc.flashbots.net/fast",
        quickBuyAmounts = listOf("0.001", "0.005", "0.01", "0.05"),
        p1 = GasPreset("0.5", 1.5, 500),
        p2 = GasPreset("2", 2.0, 1000),
        p3 = GasPreset("5", 3.0, 2000),
        uniswap = UniswapDeployment(
            universalRouter = "0x23617e59a5925b2a4bf75d73ff6711cd0b29de85",
            v2Factory = "0x5c69bee701ef814a2b6a3edd4b1652cb9cc5aa6f",
            v3Factory = "0x1f98431c8ad98523631ae4a59f267346ea31f984",
            v3QuoterV2 = "0x61ffe014ba17989e743c5f6cb21bf9697530b21e",
            v4PoolManager = "0x000000000004444c5dc75cb358380d2e3de08a90",
            v4PositionManager = "0xbd216513d74c8cf14cf4747e6aaa6420ff64ee9e",
            v4StateView = "0x7ffe42c4a5deea5b0fec41c94c136cf115597227",
            v4Quoter = "0x99bd42b2b22e531f283b7f8a86dff631eb16a9d1",
        ),
    )

    val base = EvmChain(
        id = 8453,
        name = "Base",
        nativeSymbol = "ETH",
        wrappedNative = "0x4200000000000000000000000000000000000006",
        stablecoins = listOf(Stable("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", 6)),
        hopTokens = listOf(
            HopToken("0x0b3e328455c4059eeb9e3f84b5543f74e24e7e1b", "VIRTUAL"),
            HopToken("0x1111111111166b7fe7bd91427724b487980afc69", "ZORA"),
            HopToken("0x000000000d564d5be76f7f0d28fe52605afc7cf8", "flETH", V4Bridge(0, 60, "0x9e433f32bb5481a9ca7dff5b3af74a7ed041a888")),
        ),
        nestyRouter = NESTY_ROUTER,
        rpcUrls = listOf("https://mainnet.base.org", "https://base-rpc.publicnode.com"),
        explorer = "https://basescan.org",
        feeModel = FeeModel.EIP1559,
        mevRpc = null,
        quickBuyAmounts = listOf("0.001", "0.005", "0.01", "0.05"),
        p1 = GasPreset("0.001", 1.5, 500),
        p2 = GasPreset("0.01", 2.0, 1000),
        p3 = GasPreset("0.05", 3.0, 2000),
        uniswap = UniswapDeployment(
            universalRouter = "0xd6145b2d3f379919e8cdeda7b97e37c4b2ca9c40",
            v2Factory = "0x8909dc15e40173ff4699343b6eb8132c65e18ec6",
            v3Factory = "0x33128a8fc17869897dce68ed026d694621f6fdfd",
            v3QuoterV2 = "0x3d4e44eb1374240ce5f1b871ab261cd16335b76a",
            v4PoolManager = "0x498581ff718922c3f8e6a244956af099b2652b2b",
            v4PositionManager = "0x7c5f5a4bbd8fd63184577525326123b519429bdc",
            v4StateView = "0xa3c0c9b65bad0b08107aa264b0f3db444b867a71",
            v4Quoter = "0xe1a6c5803e3c44368b171bd680c7d32dfc92ca47",
        ),
    )

    val bsc = EvmChain(
        id = 56,
        name = "BNB Chain",
        nativeSymbol = "BNB",
        wrappedNative = "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c",
        stablecoins = listOf(
            Stable("0x55d398326f99059ff775485246999027b3197955", "USDT", 18),
            Stable("0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", "USDC", 18),
            Stable("0x8d0d000ee44948fc98c9b98a4fa4921476f08b0d", "USD1", 18),
        ),
        hopTokens = emptyList(),
        nestyRouter = NESTY_ROUTER,
        rpcUrls = listOf("https://bsc-dataseed.bnbchain.org", "https://bsc-rpc.publicnode.com"),
        explorer = "https://bscscan.com",
        feeModel = FeeModel.EIP1559,
        mevRpc = "https://bscrpc.pancakeswap.finance",
        quickBuyAmounts = listOf("0.005", "0.02", "0.05", "0.1"),
        p1 = GasPreset("0.05", 1.5, 500),
        p2 = GasPreset("0.1", 2.0, 1000),
        p3 = GasPreset("1", 3.0, 2000),
        uniswap = UniswapDeployment(
            universalRouter = "0xdc264714f68d84cf29bc605589405e78bdbe7c9f",
            v2Factory = "0x8909dc15e40173ff4699343b6eb8132c65e18ec6",
            v3Factory = "0xdb1d10011ad0ff90774d0c6bb92e5c5c8b4461f7",
            v3QuoterV2 = "0x78d78e420da98ad378d7799be8f4af69033eb077",
            v4PoolManager = "0x28e2ea090877bf75740558f6bfb36a5ffee9e9df",
            v4PositionManager = "0x7a4a5c919ae2541aed11041a1aeee68f1287f95b",
            v4StateView = "0xd13dd3d6e93f276fafc9db9e6bb47c1180aee0c4",
            v4Quoter = "0x95c086d7fe28add842ee220fef225a777474d781",
        ),
    )

    val robinhood = EvmChain(
        id = 4663,
        name = "Robinhood Chain",
        nativeSymbol = "ETH",
        wrappedNative = "0x0bd7d308f8e1639fab988df18a8011f41eacad73",
        stablecoins = listOf(
            Stable("0x11d04a2491ab485a54da3817c10c2a9178c7991e", "USDC", 18),
            Stable("0x5fc5360d0400a0fd4f2af552add042d716f1d168", "USDG", 6),
        ),
        hopTokens = emptyList(),
        nestyRouter = NESTY_ROUTER,
        rpcUrls = listOf("https://rpc.mainnet.chain.robinhood.com"),
        explorer = "https://robin.etherscan.io",
        feeModel = FeeModel.ARBITRUM,
        mevRpc = null,
        quickBuyAmounts = listOf("0.001", "0.005", "0.01", "0.05"),
        p1 = GasPreset("0", 1.5, 500),
        p2 = GasPreset("0", 2.0, 1000),
        p3 = GasPreset("0", 3.0, 2000),
        uniswap = UniswapDeployment(
            universalRouter = "0x204faca1764b154221e35c0d20abb3c525710498",
            v2Factory = "0x8bceaa40b9acdfaedf85adf4ff01f5ad6517937f",
            v3Factory = "0x1f7d7550b1b028f7571e69a784071f0205fd2efa",
            v3QuoterV2 = "0x33e885ed0ec9bf04ecfb19341582aadcb4c8a9e7",
            v4PoolManager = "0x8366a39cc670b4001a1121b8f6a443a643e40951",
            v4PositionManager = "0x58daec3116aae6d93017baaea7749052e8a04fa7",
            v4StateView = "0xf3334192d15450cdd385c8b70e03f9a6bd9e673b",
            v4Quoter = "0x8dc178efb8111bb0973dd9d722ebeff267c98f94",
        ),
    )

    val elysium = EvmChain(
        id = 99901,
        name = "Elysium",
        nativeSymbol = "HYPE",
        wrappedNative = "0xba876237b0f021a6c6d852d6c26e107997cc5e81",
        stablecoins = listOf(Stable("0xb47c4c8f73f009cc0c0d3f92b388d13e293f0b65", "USDC", 6)),
        hopTokens = emptyList(),
        nestyRouter = null,
        rpcUrls = listOf("https://rpc.elysium.kinetiq.xyz"),
        explorer = "https://elysium.kinetiq.xyz",
        feeModel = FeeModel.EIP1559,
        mevRpc = null,
        quickBuyAmounts = listOf("0.05", "0.2", "0.5", "1"),
        p1 = GasPreset("0.01", 1.5, 500),
        p2 = GasPreset("0.05", 2.0, 1000),
        p3 = GasPreset("0.5", 3.0, 2000),
        uniswap = UniswapDeployment(
            universalRouter = "0xa13f7611115752deb2b13813bdde16a5073e4b45",
            v2Factory = "0xbbbcc62853a5fa27b93d6bab3e6f7ce841e25df2",
            v3Factory = "0x1707327f626496a7b5b3872e7e4d2879df7d7a9f",
            v3QuoterV2 = "0x204faca1764b154221e35c0d20abb3c525710498",
            v4PoolManager = "0xe2e105d3f7209a9da11f83ccf3e7398a753823f1",
            v4PositionManager = "0x1d1c51b9d07fd27d36412a2b66cc23d19eb3e518",
            v4StateView = "0x27ed73ae0652fdcf71d6793412a7525f32be9dfe",
            v4Quoter = "0x379f556c5a748bd7cc9baf0f7a344094ac1814f5",
        ),
    )

    val all: List<EvmChain> = listOf(ethereum, base, bsc, robinhood, elysium)

    fun byId(id: Long): EvmChain? = all.firstOrNull { it.id == id }
}

/** Same address, whatever the case. */
fun sameAddress(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)

const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

fun big(n: Long): BigInteger = BigInteger.valueOf(n)
fun big(n: Int): BigInteger = BigInteger.valueOf(n.toLong())

/** Whole native units ("0.005") as wei. */
fun parseEther(s: String): BigInteger = BigDecimal(s).movePointRight(18).toBigIntegerExact()
