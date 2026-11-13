package trade.nesty.app.evm

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import trade.nesty.app.evm.EvmTestKit.MADE_UP_WALLET
import trade.nesty.app.evm.EvmTestKit.OTHER_WALLET
import trade.nesty.app.keys.keccak256
import java.math.BigInteger
import java.util.Collections

/**
 * The trade engine end to end, on coins the TypeScript engine read on live chains (replay.json):
 * route, build, validate, simulate, sign and send, then the receipt and the approval for the
 * sale. The chain's answers to the trade itself (balance, base fee, gas estimate, nonce,
 * receipt) are made up here, and sends go to a list: nothing reaches a chain. Every signature
 * is by the MADE-UP vector key.
 */
class EngineTest {
    private val replay = EvmVectors.load("replay")
    private val nowMs = EvmTestKit.NOW_S * 1000
    private val maxUint = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
    private val own = listOf(MADE_UP_WALLET, OTHER_WALLET)
    private val signer = EvmTestKit.KeySigner()

    private fun case(label: String): JsonObject = replay.arr("cases").map { it.jsonObject }.first { it.s("label") == label }

    private fun pools(c: JsonObject): List<PoolRef> = c.arr("discovered").flatMap { d -> d.jsonObject.arr("pools").map { EvmTestKit.pool(it.jsonObject) } }

    /** The pool the record picked for amount `i` (without the reference amount). */
    private fun picked(c: JsonObject, side: Side, i: Int): Pair<PoolRef, BigInteger> {
        val best = c.arr(if (side == Side.BUY) "bestBuy" else "bestSell")[i].jsonObject
        return pools(c).first { it.id == best.s("id").lowercase() } to best.getValue("out").big()
    }

    private fun spender(chainId: Long, pool: PoolRef) = EvmTestKit.venue(pool.venueId).sellApproval(EvmTestKit.venueCtx(chainId), pool).spender

    /** The made-up rest of the chain, around the recorded reads. */
    private inner class FakeChain(val baseFee: BigInteger = big(1_000_000_000)) {
        val rpc = ReplayRpc(replay)
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sent: MutableList<Pair<EvmChain, ByteArray>> = Collections.synchronizedList(mutableListOf())

        @Volatile var balance: BigInteger = parseEther("10")

        @Volatile var estimate: () -> BigInteger = { big(200_000) }

        @Volatile var nativeUsd: Double = 600.0

        /** The coin a trade's receipt delivers. */
        @Volatile var token: String = ""
        val pending = 7L
        val bought: BigInteger = big(1_000_000)
        val gasUsed: BigInteger = big(150_000)
        val price: BigInteger = big(1_100_000_000)

        /** The wallet's native balance around block 0x10, where every receipt lands. */
        val before: BigInteger = parseEther("1")
        val after: BigInteger = parseEther("1.004")

        init {
            rpc.other = { _, method, params -> answer(method, params) }
        }

        private fun hex(n: BigInteger) = JsonPrimitive("0x" + n.toString(16))

        private fun answer(method: String, params: JsonArray): JsonElement {
            log += method
            return when (method) {
                "eth_getBalance" -> when (val block = params[1].jsonPrimitive.content) {
                    "latest" -> hex(balance)
                    "0xf" -> hex(before)
                    "0x10" -> hex(after)
                    else -> error("balance at $block")
                }
                "eth_getBlockByNumber" -> buildJsonObject { put("baseFeePerGas", "0x" + baseFee.toString(16)) }
                "eth_estimateGas" -> hex(estimate())
                "eth_getTransactionCount" -> hex(big(pending))
                "eth_getTransactionReceipt" -> receipt(params[0].jsonPrimitive.content)
                else -> error("unexpected $method")
            }
        }

        private fun receipt(hash: String): JsonElement {
            val raw = sent.firstOrNull { keccak256(it.second).toHex() == hash }?.second ?: return JsonNull
            val to = fields(raw).to
            return buildJsonObject {
                put("status", "0x1")
                put("gasUsed", "0x" + gasUsed.toString(16))
                put("effectiveGasPrice", "0x" + price.toString(16))
                put("blockNumber", "0x10")
                put(
                    "logs",
                    buildJsonArray {
                        // A trade moves the coin to the wallet; an approval (sent to the coin) moves nothing.
                        if (to != token) {
                            add(
                                buildJsonObject {
                                    put("address", token)
                                    put(
                                        "topics",
                                        buildJsonArray {
                                            add(JsonPrimitive(Receipt.TRANSFER))
                                            add(JsonPrimitive("0x" + "0".repeat(64)))
                                            add(JsonPrimitive("0x" + "0".repeat(24) + MADE_UP_WALLET.removePrefix("0x")))
                                        },
                                    )
                                    put("data", Abi.word(bought).toHex())
                                },
                            )
                        }
                    },
                )
            }
        }

        fun trader() = EvmTrader(
            rpc = rpc,
            sender = TxSender { chain, raw ->
                log += "send"
                sent += chain to raw
                keccak256(raw).toHex()
            },
            nativeUsd = { nativeUsd },
            now = { nowMs },
            pollMs = 5,
        )

        /** The wallet's own reads, answered before the record. */
        fun holds(token: String, amount: BigInteger) {
            rpc.overrides["${token.lowercase()}:${Erc20.balanceOf.encode(MADE_UP_WALLET).toHex()}"] = true to Abi.word(amount).toHex()
        }

        fun allows(token: String, spender: String, amount: BigInteger) {
            rpc.overrides["${token.lowercase()}:${Erc20.allowance.encode(MADE_UP_WALLET, spender).toHex()}"] = true to Abi.word(amount).toHex()
        }

        /** No Permit2 allowance for `spender` yet. */
        fun noPermit2(token: String, spender: String) {
            val data = Permit2.allowance.encode(MADE_UP_WALLET, token, spender).toHex()
            rpc.overrides["${Permit2.ADDRESS}:$data"] = true to (Abi.word(BigInteger.ZERO) + Abi.word(BigInteger.ZERO) + Abi.word(BigInteger.ZERO)).toHex()
        }

        val estimates: Int get() = log.count { it == "eth_estimateGas" }

        fun assertAllReadsKnown() = assertEquals("reads outside the record", emptyList<String>(), rpc.missing.distinct())
    }

    /** A signed type-2 transaction, read back. */
    private class Decoded(val chainId: Long, val nonce: Long, val tip: BigInteger, val maxFee: BigInteger, val gas: BigInteger, val to: String, val value: BigInteger, val data: ByteArray)

    private fun fields(raw: ByteArray): Decoded {
        check(raw[0].toInt() == 0x02) { "not a type-2 transaction" }
        val f = rlp(raw, 1).first as List<*>
        fun num(i: Int) = BigInteger(1, f[i] as ByteArray)
        return Decoded(num(0).toLong(), num(1).toLong(), num(2), num(3), num(4), (f[5] as ByteArray).toHex(), num(6), f[7] as ByteArray)
    }

    /** Read back, and signed by the made-up key: the same fields signed again give the same bytes. */
    private fun decode(raw: ByteArray): Decoded {
        val d = fields(raw)
        val again = Tx1559(d.chainId, d.nonce, d.tip, d.maxFee, d.gas, d.to, d.value, d.data).sign(EvmTestKit.madeUpKey)
        assertArrayEquals("signed by the made-up wallet", again, raw)
        return d
    }

    /** One RLP item at `at`: bytes or a list, and where the next one starts. */
    private fun rlp(b: ByteArray, at: Int): Pair<Any, Int> {
        val p = b[at].toInt() and 0xff
        fun len(n: Int, from: Int): Int {
            var l = 0
            for (i in 0 until n) l = (l shl 8) or (b[from + i].toInt() and 0xff)
            return l
        }
        return when {
            p < 0x80 -> byteArrayOf(b[at]) to at + 1
            p <= 0xb7 -> b.copyOfRange(at + 1, at + 1 + (p - 0x80)) to at + 1 + (p - 0x80)
            p <= 0xbf -> {
                val n = p - 0xb7
                val l = len(n, at + 1)
                b.copyOfRange(at + 1 + n, at + 1 + n + l) to at + 1 + n + l
            }
            else -> {
                val (start, l) = if (p <= 0xf7) (at + 1) to (p - 0xc0) else (at + 1 + (p - 0xf7)) to len(p - 0xf7, at + 1)
                val out = mutableListOf<Any>()
                var i = start
                while (i < start + l) {
                    val (item, next) = rlp(b, i)
                    out += item
                    i = next
                }
                out to start + l
            }
        }
    }

    private fun prefs(chain: EvmChain) = TradePrefs(1_500, chain.p2)

    private val terminal = setOf(StepStatus.CONFIRMED, StepStatus.FAILED, StepStatus.BLOCKED, StepStatus.SKIPPED)

    private suspend fun done(flow: StateFlow<Trade>, steps: Int): Trade = withTimeout(20_000) {
        flow.first { t -> t.steps.size == steps && t.steps.all { it.status in terminal } }
    }

    private val deadline = big(EvmTestKit.NOW_S + TradeMath.DEADLINE_S)

    // ── a curve on BNB Chain (Flap) ──────────────────────────────────────────────────

    private val flap = "Flap curve, near the end (BNB Chain)"

    @Test
    fun buysSimulatedThenApprovesTheSale() = runBlocking<Unit> {
        val c = case(flap)
        val chain = EvmTestKit.chain(56)
        val fake = FakeChain()
        val token = c.s("token").lowercase()
        fake.token = token
        val amount = c.arr("buyAmounts")[2].big()
        val (pool, out) = picked(c, Side.BUY, 1)
        val sp = spender(56, pool)
        fake.holds(token, BigInteger.ZERO)
        fake.allows(token, sp, BigInteger.ZERO)
        val trade = done(fake.trader().buy(BuyRequest(56, token, amount, prefs(chain), "TEST"), signer, own), 2)

        assertEquals(TradeStatus.DONE, trade.status)
        val (swap, approve) = trade.steps
        assertEquals(StepKind.SWAP, swap.kind)
        assertEquals(StepStatus.CONFIRMED, swap.status)
        assertEquals(fake.bought, swap.amountOut)
        assertEquals(fake.gasUsed * fake.price, swap.feeWei)
        assertEquals(StepKind.APPROVE, approve.kind)
        assertEquals(StepStatus.CONFIRMED, approve.status)

        // The swap was simulated before it was signed; the approval behind it was not.
        assertEquals(1, fake.estimates)
        assertTrue(fake.log.indexOf("eth_estimateGas") < fake.log.indexOf("send"))
        assertEquals(2, fake.sent.size)
        val buy = decode(fake.sent[0].second)
        val fees = TradeMath.computeFees(chain, fake.baseFee, chain.p2)
        assertEquals(56L, buy.chainId)
        assertEquals(7L, buy.nonce)
        assertEquals(big(260_000), buy.gas)
        assertEquals(fees.maxFeePerGas, buy.maxFee)
        assertEquals(fees.maxPriorityFeePerGas, buy.tip)
        val minOut = TradeMath.minOutAfter(out, 0, 1_500)
        val expected = Flap.buildBuy(EvmTestKit.venueCtx(56), pool, BuyParams(MADE_UP_WALLET, amount, minOut, deadline))
        assertEquals(expected.to.lowercase(), buy.to)
        assertEquals(amount, buy.value)
        assertEquals(expected.value, buy.value)
        assertEquals(expected.data.toHex(), buy.data.toHex())

        val ap = decode(fake.sent[1].second)
        assertEquals(8L, ap.nonce)
        assertEquals(token, ap.to)
        assertEquals(BigInteger.ZERO, ap.value)
        assertEquals(TradeMath.staticGas(chain, GasKind.APPROVE), ap.gas)
        assertEquals(Erc20.approve.encode(sp, maxUint).toHex(), ap.data.toHex())
        fake.assertAllReadsKnown()
    }

    @Test
    fun sellsAfterItsApproval() = runBlocking<Unit> {
        val c = case(flap)
        val chain = EvmTestKit.chain(56)
        val fake = FakeChain()
        val token = c.s("token").lowercase()
        fake.token = token
        val amount = c.arr("sellAmounts")[1].big()
        val (pool, out) = picked(c, Side.SELL, 0)
        val sp = spender(56, pool)
        fake.holds(token, amount)
        fake.allows(token, sp, BigInteger.ZERO)
        val trade = done(fake.trader().sell(SellRequest(56, token, 10_000, prefs(chain), "TEST"), signer, own), 2)

        assertEquals(TradeStatus.DONE, trade.status)
        val swap = trade.swap
        assertEquals(StepStatus.CONFIRMED, swap.status)
        // The curve pays native without an unwrap: the balance change, the fee added back.
        assertEquals(fake.after - fake.before + fake.gasUsed * fake.price, swap.amountOut)
        // The approval was simulated; the sale behind it could not be (it needs that approval).
        assertEquals(1, fake.estimates)
        val ap = decode(fake.sent[0].second)
        assertEquals(token, ap.to)
        assertEquals(7L, ap.nonce)
        assertEquals(big(260_000), ap.gas)
        assertEquals(Erc20.approve.encode(sp, maxUint).toHex(), ap.data.toHex())
        val sell = decode(fake.sent[1].second)
        assertEquals(8L, sell.nonce)
        assertEquals(TradeMath.staticGas(chain, GasKind.SWAP), sell.gas)
        val minOut = TradeMath.minOutAfter(out, 0, 1_500)
        val expected = Flap.buildSell(EvmTestKit.venueCtx(56), pool, SellParams(MADE_UP_WALLET, amount, minOut, deadline))
        assertEquals(expected.to.lowercase(), sell.to)
        assertEquals(BigInteger.ZERO, sell.value)
        assertEquals(expected.data.toHex(), sell.data.toHex())
        fake.assertAllReadsKnown()
    }

    @Test
    fun aFailedSimulationSignsNothing() = runBlocking<Unit> {
        val c = case(flap)
        val fake = FakeChain()
        fake.estimate = { throw RpcError(3, "execution reverted: Slippage 0x11064Ffa76d1c861e9c19b3D36F66572f16f9576") }
        val trade = done(fake.trader().buy(BuyRequest(56, c.s("token"), c.arr("buyAmounts")[2].big(), prefs(EvmTestKit.chain(56))), signer, own), 1)
        assertEquals(TradeStatus.FAILED, trade.status)
        assertEquals(StepStatus.FAILED, trade.swap.status)
        // No address is echoed back.
        assertEquals("Simulation failed: execution reverted: Slippage 0x…", trade.swap.error)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun aRefusedCheckSignsNothing() = runBlocking<Unit> {
        val c = case(flap)
        val fake = FakeChain()
        // The signing wallet is not among the user's wallets: the validator refuses.
        val trade = done(fake.trader().buy(BuyRequest(56, c.s("token"), c.arr("buyAmounts")[2].big(), prefs(EvmTestKit.chain(56))), signer, listOf(OTHER_WALLET)), 1)
        assertEquals(TradeStatus.BLOCKED, trade.status)
        assertEquals(StepStatus.BLOCKED, trade.swap.status)
        assertEquals("Sender is not one of your wallets", trade.swap.error)
        assertNotNull(trade.blocked)
        assertEquals(0, fake.estimates)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun aShortBalanceOrAHighFeeSkipsTheBuy() = runBlocking<Unit> {
        val c = case(flap)
        val token = c.s("token")
        val amount = c.arr("buyAmounts")[2].big()
        val chain = EvmTestKit.chain(56)

        val poor = FakeChain().apply { balance = BigInteger.ZERO }
        val t1 = done(poor.trader().buy(BuyRequest(56, token, amount, prefs(chain)), signer, own), 1)
        assertEquals(StepStatus.SKIPPED, t1.swap.status)
        val need = amount + big(260_000) * TradeMath.computeFees(chain, poor.baseFee, chain.p2).maxFeePerGas
        assertEquals(
            "Insufficient balance: needs ${TradeMath.formatToken(need, 18)} BNB with gas, has ${TradeMath.formatToken(BigInteger.ZERO, 18)}",
            t1.swap.error,
        )
        assertTrue(poor.sent.isEmpty())

        // Gas worth more than the default limit of $10 at this price.
        val dear = FakeChain().apply { nativeUsd = 1e9 }
        val t2 = done(dear.trader().buy(BuyRequest(56, token, amount, prefs(chain)), signer, own), 1)
        assertEquals(StepStatus.SKIPPED, t2.swap.status)
        val error = t2.swap.error.orEmpty()
        assertTrue(error, error.startsWith("Gas fee about \$") && error.endsWith("BNB) is above your gas limit of \$10. Lower the priority fee or raise the limit in Settings."))
        assertTrue(dear.sent.isEmpty())

        try {
            FakeChain().trader().buy(BuyRequest(56, token, BigInteger.ZERO, prefs(chain)), signer, own)
            fail("a zero buy started")
        } catch (e: TradeException) {
            assertEquals("Amount too small.", e.message)
        }
    }

    @Test
    fun noncesFollowEachOther() = runBlocking<Unit> {
        val c = case(flap)
        val fake = FakeChain()
        val token = c.s("token").lowercase()
        fake.token = token
        fake.holds(token, BigInteger.ZERO)
        fake.allows(token, spender(56, picked(c, Side.BUY, 0).first), BigInteger.ZERO)
        val trader = fake.trader()
        val chain = EvmTestKit.chain(56)
        val a = async { trader.buy(BuyRequest(56, token, c.arr("buyAmounts")[1].big(), prefs(chain)), signer, own) }
        val b = async { trader.buy(BuyRequest(56, token, c.arr("buyAmounts")[2].big(), prefs(chain)), signer, own) }
        done(a.await(), 2)
        done(b.await(), 2)
        assertEquals(listOf(7L, 8L, 9L, 10L), fake.sent.map { decode(it.second).nonce }.sorted())
        assertEquals(2, trader.trades.value.size)
    }

    // ── Robinhood: fees as on Arbitrum ─────────────────────────────────────────────

    @Test
    fun robinhoodCapsAnUnsimulatedLimitAtTheBalance() = runBlocking<Unit> {
        val c = case("Pons curve")
        val chain = EvmTestKit.chain(4663)
        val fake = FakeChain(baseFee = big(10_000_000_000))
        val token = c.s("token").lowercase()
        fake.token = token
        val amount = c.arr("buyAmounts")[1].big()
        val (pool, _) = picked(c, Side.BUY, 0)
        assertEquals("pons", pool.venueId)
        val fees = TradeMath.computeFees(chain, fake.baseFee, chain.p2)
        // Enough for the buy, short of the approval's static limit (1M gas) at the max fee.
        fake.balance = amount + big(300_000) * fees.maxFeePerGas
        fake.holds(token, BigInteger.ZERO)
        fake.allows(token, spender(4663, pool), BigInteger.ZERO)
        val trade = done(fake.trader().buy(BuyRequest(4663, token, amount, prefs(chain)), signer, own), 2)
        assertEquals(TradeStatus.DONE, trade.status)
        val buy = decode(fake.sent[0].second)
        // No tip on an Arbitrum-style chain; the simulated limit as anywhere.
        assertEquals(BigInteger.ZERO, buy.tip)
        assertEquals(big(260_000), buy.gas)
        val ap = decode(fake.sent[1].second)
        assertEquals(fake.balance / fees.maxFeePerGas, ap.gas)
        assertTrue(ap.gas < TradeMath.staticGas(chain, GasKind.APPROVE))
        fake.assertAllReadsKnown()
    }

    // ── Uniswap v4 through the Universal Router: Permit2 ───────────────────────────

    @Test
    fun sellsThroughPermit2() = runBlocking<Unit> {
        val c = case("Clanker v4 (Base)")
        val chain = EvmTestKit.chain(8453)
        val router = chain.uniswap.universalRouter.lowercase()
        val fake = FakeChain()
        val token = c.s("token").lowercase()
        fake.token = token
        val amount = c.arr("sellAmounts")[1].big()
        val (pool, out) = picked(c, Side.SELL, 0)
        fake.holds(token, amount)
        fake.allows(token, Permit2.ADDRESS, BigInteger.ZERO)
        fake.noPermit2(token, router)
        val trade = done(fake.trader().sell(SellRequest(8453, token, 10_000, prefs(chain), "TEST"), signer, own), 2)
        assertEquals(TradeStatus.DONE, trade.status)

        val ap = decode(fake.sent[0].second)
        assertEquals(token, ap.to)
        assertEquals(Erc20.approve.encode(Permit2.ADDRESS, maxUint).toHex(), ap.data.toHex())
        val sell = decode(fake.sent[1].second)
        assertEquals(router, sell.to)
        // The permit covers exactly this sale, signed by the wallet; the swap carries it.
        val permit = Permit2.build(token, router, amount, 0, EvmTestKit.NOW_S)
        val sig = Permit2.single(8453, permit).sign(EvmTestKit.madeUpKey)
        val minOut = TradeMath.minOutAfter(out, 0, 1_500)
        val expected = UniswapVenue.buildSell(EvmTestKit.venueCtx(8453), pool, SellParams(MADE_UP_WALLET, amount, minOut, deadline, SignedPermit(permit, sig)))
        assertEquals(expected.data.toHex(), sell.data.toHex())
        assertEquals(expected.value, sell.value)
        assertEquals(1, fake.estimates)
        fake.assertAllReadsKnown()
    }

    // ── a curve quoted in an ERC-20: the Nesty Router ──────────────────────────────

    @Test
    fun tradesAFundedCurveInOneTransaction() = runBlocking<Unit> {
        val c = case("Virtuals curve (newest live)")
        val chain = EvmTestKit.chain(8453)
        val token = c.s("token").lowercase()
        val router = chain.nestyRouter!!.lowercase()

        // Buy: native → VIRTUAL → curve, one transaction to the router, simulated.
        val fake = FakeChain()
        fake.token = token
        fake.holds(token, BigInteger.ZERO)
        fake.allows(token, Permit2.ADDRESS, BigInteger.ZERO)
        val amount = c.arr("buyAmounts")[2].big()
        val bought = done(fake.trader().buy(BuyRequest(8453, token, amount, prefs(chain), "TEST"), signer, own), 2)
        assertEquals(TradeStatus.DONE, bought.status)
        assertEquals(1, fake.estimates)
        val buy = decode(fake.sent[0].second)
        assertEquals(router, buy.to)
        assertEquals(amount, buy.value)
        val call = checkNotNull(NestyRouter.execute.decodeCall(buy.data))
        assertEquals(token, (call[2] as String).lowercase())
        assertEquals(TradeMath.minOutAfter(picked(c, Side.BUY, 1).second, 0, 1_500), call[3])
        // Its sales go through the router too: the approval is for Permit2.
        val ap = decode(fake.sent[1].second)
        assertEquals(Erc20.approve.encode(Permit2.ADDRESS, maxUint).toHex(), ap.data.toHex())
        fake.assertAllReadsKnown()

        // Sell: a one-time Permit2 transfer of exactly the amount to the router, signed here.
        val seller = FakeChain()
        seller.token = token
        val sellAmount = c.arr("sellAmounts")[1].big()
        seller.holds(token, sellAmount)
        seller.allows(token, Permit2.ADDRESS, BigInteger.ONE.shiftLeft(255))
        val sold = done(seller.trader().sell(SellRequest(8453, token, 10_000, prefs(chain), "TEST"), signer, own), 1)
        assertEquals(TradeStatus.DONE, sold.status)
        assertEquals(1, seller.estimates)
        val sell = decode(seller.sent.single().second)
        assertEquals(router, sell.to)
        assertEquals(BigInteger.ZERO, sell.value)
        val args = checkNotNull(NestyRouter.execute.decodeCall(sell.data))
        val input = args[1] as List<*>
        val permitTuple = input[0] as List<*>
        val permitted = permitTuple[0] as List<*>
        assertEquals(token, (permitted[0] as String).lowercase())
        assertEquals(sellAmount, permitted[1])
        val transfer = TransferPermit(token, sellAmount, permitTuple[1] as BigInteger, permitTuple[2] as BigInteger)
        assertEquals(big(EvmTestKit.NOW_S + Permit2.TRANSFER_TTL_S), transfer.deadline)
        val signature = input[1] as ByteArray
        val digest = Permit2.transfer(8453, transfer, router).digest()
        val sig = EcSignature(BigInteger(1, signature.copyOfRange(0, 32)), BigInteger(1, signature.copyOfRange(32, 64)), signature[64] - 27)
        assertEquals(MADE_UP_WALLET, Secp256k1.recoverAddress(digest, sig)?.lowercase())
        assertEquals(NestyRouter.NATIVE.lowercase(), (args[2] as String).lowercase())
        assertEquals(TradeMath.minOutAfter(picked(c, Side.SELL, 0).second, 0, 1_500), args[3])
        seller.assertAllReadsKnown()
    }
}
