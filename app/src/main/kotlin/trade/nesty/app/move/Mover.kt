package trade.nesty.app.move

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import trade.nesty.app.data.BalanceModel
import trade.nesty.app.data.Balances
import trade.nesty.app.data.Chain
import trade.nesty.app.data.CoinInfo
import trade.nesty.app.data.FillMove
import trade.nesty.app.data.FillTx
import trade.nesty.app.data.Preset
import trade.nesty.app.data.Settings
import trade.nesty.app.data.Trading
import trade.nesty.app.data.WalletSession
import trade.nesty.app.evm.ChainReader
import trade.nesty.app.evm.Erc20
import trade.nesty.app.evm.EvmChain
import trade.nesty.app.evm.EvmTrader
import trade.nesty.app.evm.GasKind
import trade.nesty.app.evm.HttpRpc
import trade.nesty.app.evm.TradeMath
import trade.nesty.app.evm.TradeStatus
import trade.nesty.app.evm.TxDraft
import trade.nesty.app.evm.ValidationContext
import trade.nesty.app.evm.orNull
import trade.nesty.app.evm.shortErr
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.HttpSolCall
import trade.nesty.app.sol.Layouts
import trade.nesty.app.sol.RelayDepositContext
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.SolAsset
import trade.nesty.app.sol.SolBroadcast
import trade.nesty.app.sol.SolRpc
import trade.nesty.app.sol.SolRpcs
import trade.nesty.app.sol.SolSend
import trade.nesty.app.sol.SolTokenMeta
import trade.nesty.app.sol.SolTrader
import trade.nesty.app.sol.SolTransferContext
import trade.nesty.app.sol.SolValidator
import trade.nesty.app.sol.Spl
import trade.nesty.app.ui.Fmt
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import trade.nesty.app.evm.Trade as EngineTrade

/** Why a move did not start. Nothing was signed. */
class MoveRefused(message: String) : Exception(message)

/** What a coin send reads on its chain right before signing; null where a read failed. */
private class CoinReads(val held: BigInteger?, val decimals: Int?, val native: BigInteger?, val baseFee: BigInteger?)

/** What a move that ended says at the bottom of the screen. */
data class MoveNote(val moveId: String, val title: String, val detail: String, val ok: Boolean)

/** A bridge's offer for one leg, read for the screen before anything is confirmed. */
data class LegQuote(
    val from: Chain,
    val to: Chain,
    val amountIn: BigInteger,
    val amountOut: BigInteger,
    val usdIn: Double?,
    val usdOut: Double?,
    val feeUsd: Double?,
    val timeS: Double?,
)

/**
 * Moves funds out of a chain's own balance: plain sends, and bridges to another chain (Relay,
 * asked from the phone, never through Nesty's server). A withdrawal sends to an address the user
 * typed; a buy on a chain that is short moves the missing amount to the wallet itself, then buys.
 *
 * Nothing moves by itself: every move starts from the user's one confirmation. Each move is kept
 * on the phone (MoveStore) as it goes, so one the app was closed in the middle of is followed
 * again on the next start, and a buy whose funds landed while the app was closed is asked again.
 *
 * Reads that name a typed address (its code, a send's simulation, its receipt) go to the chain's
 * own RPCs: Nesty's server never sees whom the user pays.
 */
class Mover(
    private val session: WalletSession,
    private val trading: Trading,
    private val balances: BalanceModel,
    private val settings: Settings,
    private val store: MoveStore,
    private val relay: RelayClient,
    http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val counter = AtomicLong()
    private val direct = HttpRpc(http, direct = true)
    private val directReaders = ConcurrentHashMap<Long, ChainReader>()

    /** Solana on its public RPC only: sends to a typed address, and the reads around them. */
    private val solDirectRpc = SolRpc(HttpSolCall(http, urls = SolRpcs.PUBLIC), SolBroadcast(http))
    private val solDirect = SolTrader(solDirectRpc, knownSolUsd = { trading.priceOf(Chain.SOLANA) })

    @Volatile
    private var rent: Pair<Long, BigInteger>? = null

    /** The rent of a new token account, by its size, and when it was read. */
    private val accountRents = ConcurrentHashMap<Int, Pair<Long, BigInteger>>()
    private val notesFlow = MutableSharedFlow<MoveNote>(extraBufferCapacity = 16)

    /** A note for each move that ended, or that needs the user. */
    val notes: SharedFlow<MoveNote> = notesFlow.asSharedFlow()

    /** The buy each move started, by move id, while the app runs: a sheet that is open follows it. */
    val buys = mutableStateMapOf<String, StateFlow<EngineTrade>>()

    /** Moves a screen is showing as they go: their notes are left out at the bottom. */
    val quiet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Moves being run in this process, so a resume does not run one twice. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    val moves: List<Move> get() = store.moves

    fun get(id: String): Move? = store.get(id)

    /** The moves of the wallet that trades, newest first. */
    fun ofActive(): List<Move> = store.of(session.active?.id)

    // ── what the screens read ────────────────────────────────────────────────────────

    fun directReader(chain: EvmChain): ChainReader = directReaders.getOrPut(chain.id) { ChainReader(chain, direct) }

    /** Solana's rent exemption for a wallet: read from the chain, kept ten minutes. */
    suspend fun rentMin(): BigInteger {
        rent?.let { (at, lamports) -> if (now() - at < RENT_TTL_MS) return lamports }
        val lamports = orNull { solDirectRpc.minimumBalance(0) } ?: return rent?.second ?: DEFAULT_RENT
        rent = now() to lamports
        return lamports
    }

    private fun rentKnown(): BigInteger = rent?.second ?: DEFAULT_RENT

    /** A Solana transaction's fee at a preset's speed. */
    fun solFee(preset: Preset): BigInteger = SolSend.networkFee(Trading.priorityLamports(preset.speed))

    /** What stays on each chain when funds leave it. */
    fun reserves(preset: Preset): Map<Chain, BigInteger> = Chain.entries.associateWith { Plans.reserve(it, rentKnown(), solFee(preset)) }

    /** One Balance of the wallet that trades, chain by chain, as last read; chains without a price are left out. */
    fun funds(): List<Funds> {
        val wallet = session.active ?: return emptyList()
        val b = balances.active ?: return emptyList()
        return Balances.chainsOf(wallet).mapNotNull { c ->
            val raw = b.raw[c] ?: return@mapNotNull null
            val price = b.prices[c] ?: trading.priceOf(c) ?: return@mapNotNull null
            Funds(c, raw, price)
        }
    }

    /** The wallet's own address on `chain`. */
    private fun own(chain: Chain): String? = trading.addressOn(chain)

    /** A plain send's network fee on `chain` now, in dollars. */
    suspend fun sendFeeUsd(chain: Chain, preset: Preset): Double? {
        val price = trading.priceOf(chain) ?: return null
        if (chain == Chain.SOLANA) return trading.usdOf(solFee(preset), chain.decimals, price)
        val evm = trading.evmOf(chain) ?: return null
        val wei = trading.engine.networkFeeWei(evm.id, listOf(GasKind.TRANSFER), Trading.gasOf(evm, preset.speed)) ?: return null
        return trading.usdOf(wei, 18, price)
    }

    /**
     * The bridge's offer for moving `amount` out of `from` (exact output: `amount` arriving on `to`,
     * taking in at most `cap`), paid to `recipient`. Its checks are the extension's: a quote that
     * does not pass them is refused with the reason.
     */
    suspend fun quote(from: Chain, to: Chain, recipient: String, amount: BigInteger, cap: BigInteger? = null): LegQuote {
        if (!Plans.canLeave(from)) throw MoveRefused(ELYSIUM_OUT)
        val user = own(from) ?: throw MoveRefused(if (from == Chain.SOLANA) Trading.NO_SOL else Trading.NO_EVM)
        val ask = Relay.Ask(user, recipient, Plans.bridgeId(from), Plans.bridgeId(to), amount, cap)
        val terms = try {
            if (from == Chain.SOLANA) relay.quoteFromSolana(ask).terms else relay.quote(ask).terms
        } catch (e: CancellationException) {
            throw e
        } catch (e: Relay.RelayError) {
            throw MoveRefused(bridgeSays(e.message))
        } catch (e: Exception) {
            throw MoveRefused(tr("The bridge did not answer: {1}", shortErr(e)))
        }
        val pIn = trading.priceOf(from)
        val pOut = trading.priceOf(to)
        return LegQuote(
            from, to, terms.amountIn, terms.amountOut,
            pIn?.let { trading.usdOf(terms.amountIn, from.decimals, it) },
            pOut?.let { trading.usdOf(terms.amountOut, to.decimals, it) },
            terms.feeUsd, terms.timeEstimateS,
        )
    }

    /**
     * Why funds may not go to `recipient` on `chain`, or null: a token contract on an EVM chain, an
     * address funds are lost at on Solana. `contract` says when it is some other contract.
     */
    data class Check(val block: String?, val contract: Boolean, val newSolana: Boolean)

    suspend fun check(chain: Chain, recipient: String): Check {
        if (chain == Chain.SOLANA) {
            val acc = solDirectRpc.getAccount(recipient)
            return Check(SolSend.recipientBlock(recipient, acc), contract = false, newSolana = acc == null)
        }
        val evm = trading.evmOf(chain) ?: return Check(null, false, false)
        val kind = Recipient.evmKind(directReader(evm), recipient)
        return Check(kind.tokenSymbol?.let { Recipient.tokenContract(it) }, kind.contract, newSolana = false)
    }

    /**
     * A coin send to `recipient`, as the screen shows it before it is confirmed: why it may not go
     * (`block`), and on Solana the `rent` of the receiver's account for the coin when they have
     * none yet, and `keep`, what a wallet must keep to exist. Read on the chain's own RPCs.
     */
    data class CoinCheck(val block: String?, val rent: BigInteger = BigInteger.ZERO, val keep: BigInteger = BigInteger.ZERO)

    suspend fun checkCoin(chain: Chain, token: String, recipient: String): CoinCheck {
        // Case counts in a Solana address.
        if (if (chain.isEvm) sameAddress(recipient, own(chain)) else recipient == own(chain)) return CoinCheck(SAME_WALLET)
        if (chain == Chain.SOLANA) {
            val meta = solDirect.tokenMeta(token) ?: return CoinCheck(COIN_UNREAD)
            SolSend.coinBlock(meta)?.let { return CoinCheck(it) }
            val accs = solDirectRpc.getMultipleAccounts(listOf(recipient, SolAddr.associatedTokenAddress(recipient, token, meta.tokenProgram)))
            SolSend.recipientBlock(recipient, accs.getOrNull(0))?.let { return CoinCheck(it) }
            return CoinCheck(null, if (accs.getOrNull(1) == null) tokenAccountRent(meta) else BigInteger.ZERO, rentMin())
        }
        val evm = trading.evmOf(chain) ?: return CoinCheck(tr("This version of Nesty cannot send on {1}.", chain.title))
        return CoinCheck(Recipient.evmKind(directReader(evm), recipient).tokenSymbol?.let { Recipient.tokenContract(it) })
    }

    /** A coin send's network fee on `chain` now, in the smallest unit of the chain's own coin. */
    suspend fun coinFee(chain: Chain, preset: Preset): BigInteger? {
        if (chain == Chain.SOLANA) return solFee(preset)
        val evm = trading.evmOf(chain) ?: return null
        return trading.engine.networkFeeWei(evm.id, listOf(GasKind.TOKEN_TRANSFER), Trading.gasOf(evm, preset.speed))
    }

    /** The rent of a new account for a Solana coin: read from the chain, kept ten minutes. */
    private suspend fun tokenAccountRent(meta: SolTokenMeta): BigInteger {
        val size = SolSend.tokenAccountSize(meta.tokenProgram, meta.extensions)
        accountRents[size]?.let { (at, lamports) -> if (now() - at < RENT_TTL_MS) return lamports }
        val lamports = orNull { solDirectRpc.minimumBalance(size) } ?: return accountRents[size]?.second ?: SolSend.rentOf(size)
        accountRents[size] = now() to lamports
        return lamports
    }

    // ── starting a move ──────────────────────────────────────────────────────────────

    private fun newId() = "m${now()}-${counter.incrementAndGet()}"

    /**
     * A withdrawal of `usd` to `recipient` on `to`, from `parts` (Plans.withdrawParts): the arriving
     * chain's share is a plain send, each other chain's a bridge paying `recipient` on `to`. Each
     * bridge is quoted again right before it is signed. Returns at once; the legs run on.
     */
    fun withdraw(to: Chain, recipient: String, usd: Double, parts: List<Part>, shown: Map<Chain, LegQuote>, preset: Preset): Move {
        val wallet = session.active ?: throw MoveRefused(tr("Add a wallet first."))
        if (parts.isEmpty()) throw MoveRefused(tr("Enter an amount."))
        for (p in parts) {
            if (p.chain.isEvm && session.evmSigner() == null) throw MoveRefused(tr("Unlock your wallets first."))
            if (!p.chain.isEvm && session.solSigner() == null) throw MoveRefused(tr("Unlock your wallets first."))
            if (p.chain != to && !Plans.canLeave(p.chain)) throw MoveRefused(ELYSIUM_OUT)
        }
        val legs = parts.map { p ->
            val q = shown[p.chain]
            MoveLeg(
                kind = if (p.chain == to) LegKind.SEND else LegKind.BRIDGE,
                from = p.chain.feed,
                to = to.feed,
                amountIn = p.raw.toString(),
                amountOut = if (p.chain == to) p.raw.toString() else q?.amountOut?.toString(),
                usdIn = p.usd,
                usdOut = if (p.chain == to) p.usd else q?.usdOut,
                quoteFeeUsd = q?.feeUsd,
                timeS = q?.timeS,
            )
        }
        val move = Move(newId(), MoveKind.WITHDRAW, wallet.id, now(), to.feed, recipient, usd, legs, preset = settings.presets.indexOf(preset).coerceAtLeast(0))
        store.put(move)
        running += move.id
        scope.launch {
            try {
                coroutineScope { legs.indices.forEach { i -> launch { runLeg(move.id, i, preset) } } }
            } finally {
                settle(move.id)
                running -= move.id
            }
        }
        return move
    }

    /**
     * A buy on `chain` whose balance is short: `shortfall` (its smallest units) is moved there from
     * `from` first, to the wallet's own address, taking in at most `cap`; once it lands the buy goes
     * out as usual. If it lands too late to trust the price the sheet showed, the user is asked again.
     */
    fun buyWithMove(chain: Chain, token: String, usd: Double, presetIndex: Int, info: CoinInfo, from: Chain, shortfall: BigInteger, cap: BigInteger, shown: LegQuote): Move {
        val wallet = session.active ?: throw MoveRefused(tr("Add a wallet first."))
        val recipient = own(chain) ?: throw MoveRefused(if (chain == Chain.SOLANA) Trading.NO_SOL else Trading.NO_EVM)
        if (own(from) == null) throw MoveRefused(if (from == Chain.SOLANA) Trading.NO_SOL else Trading.NO_EVM)
        if (!Plans.canLeave(from)) throw MoveRefused(ELYSIUM_OUT)
        if ((if (from.isEvm) session.evmSigner() else session.solSigner()) == null) throw MoveRefused(tr("Unlock your wallets first."))
        val leg = MoveLeg(
            kind = LegKind.BRIDGE,
            from = from.feed,
            to = chain.feed,
            amountIn = shown.amountIn.toString(),
            amountOut = shortfall.toString(),
            usdIn = shown.usdIn,
            usdOut = shown.usdOut,
            quoteFeeUsd = shown.feeUsd,
            timeS = shown.timeS,
        )
        val move = Move(
            newId(), MoveKind.BUY, wallet.id, now(), chain.feed, recipient, usd, listOf(leg),
            token = token, symbol = info.symbol, decimals = info.decimals, supply = info.supply, hints = info.hints,
            tax = info.tax, onCurve = info.onCurve, launchpad = info.launchpad,
            preset = presetIndex.coerceIn(0, 2),
        )
        store.put(move)
        running += move.id
        val confirmedAt = now()
        scope.launch {
            try {
                runLeg(move.id, 0, settings.presets[move.preset], exactOut = shortfall, cap = cap)
                afterMove(move.id, confirmedAt, resumed = false)
            } finally {
                running -= move.id
            }
        }
        return move
    }

    /**
     * A send of `amount` (its smallest units) of a coin the wallet holds to `recipient` on the
     * coin's own chain. `usd` is what it is worth now, null when not priced. Everything is read
     * again and checked right before signing. Returns at once; the send runs on.
     */
    fun sendCoin(chain: Chain, token: String, symbol: String?, decimals: Int, recipient: String, amount: BigInteger, usd: Double?, preset: Preset): Move {
        val wallet = session.active ?: throw MoveRefused(tr("Add a wallet first."))
        if (own(chain) == null) throw MoveRefused(if (chain == Chain.SOLANA) Trading.NO_SOL else Trading.NO_EVM)
        if (chain.isEvm && trading.evmOf(chain) == null) throw MoveRefused(tr("This version of Nesty cannot send on {1}.", chain.title))
        if ((if (chain.isEvm) session.evmSigner() else session.solSigner()) == null) throw MoveRefused(tr("Unlock your wallets first."))
        if (amount.signum() <= 0) throw MoveRefused(tr("Enter an amount."))
        val leg = MoveLeg(
            kind = LegKind.SEND,
            from = chain.feed,
            to = chain.feed,
            amountIn = amount.toString(),
            amountOut = amount.toString(),
            usdIn = usd,
            usdOut = usd,
        )
        val move = Move(
            newId(), MoveKind.SEND_COIN, wallet.id, now(), chain.feed, recipient, usd ?: 0.0, listOf(leg),
            token = token, symbol = symbol, decimals = decimals,
            preset = settings.presets.indexOf(preset).coerceAtLeast(0),
        )
        store.put(move)
        running += move.id
        scope.launch {
            try {
                runLeg(move.id, 0, preset)
            } finally {
                settle(move.id)
                running -= move.id
            }
        }
        return move
    }

    /** The user's answer to a buy whose moved funds landed: keep them on the chain instead. */
    fun keep(id: String) {
        val m = store.update(id) { if (it.state == MoveState.ASK) it.copy(state = MoveState.KEPT) else it } ?: return
        if (m.state == MoveState.KEPT) balancesLater()
    }

    /**
     * The user's answer to a buy whose moved funds landed: buy now, at what the sheet shows now
     * (`usd` and `preset` as picked there). The move's cost joins the buy's cost basis.
     */
    suspend fun buyAsked(id: String, usd: Double, presetIndex: Int, info: CoinInfo): StateFlow<EngineTrade> {
        val m = store.get(id) ?: throw MoveRefused(tr("This move is no longer here."))
        if (m.state != MoveState.ASK) throw MoveRefused(tr("This move was already used."))
        val chain = m.toChain ?: throw MoveRefused(tr("Unknown chain."))
        val token = m.token ?: throw MoveRefused(tr("Unknown coin."))
        store.update(id) { it.copy(state = MoveState.BUYING, usd = usd, preset = presetIndex.coerceIn(0, 2), buyError = null) }
        return startBuy(id, chain, token, usd, presetIndex.coerceIn(0, 2), info, m.legs[0])
            ?: throw MoveRefused(store.get(id)?.buyError ?: tr("Could not start."))
    }

    /** A move for a buy of this coin that is waiting on the user, if any. */
    fun asking(chain: Chain, token: String): Move? = ofActive().firstOrNull {
        it.kind == MoveKind.BUY && it.state == MoveState.ASK && it.to == chain.feed && it.token != null && Trading.sameToken(chain, it.token, token)
    }

    // ── running the legs ─────────────────────────────────────────────────────────────

    private fun fail(id: String, i: Int, reason: String, status: LegStatus = LegStatus.FAILED) {
        store.leg(id, i) { it.copy(status = status, error = reason, endedAt = now()) }
    }

    /** One leg, from its checks to its end: sent, landed, and for a bridge paid out. */
    private suspend fun runLeg(id: String, i: Int, preset: Preset, exactOut: BigInteger? = null, cap: BigInteger? = null) {
        val leg = store.get(id)?.legs?.getOrNull(i) ?: return
        val from = leg.fromChain ?: return fail(id, i, tr("Unknown chain."))
        val to = leg.toChain ?: return fail(id, i, tr("Unknown chain."))
        val m = store.get(id) ?: return
        val recipient = m.recipient
        store.leg(id, i) { it.copy(status = LegStatus.SENDING, error = null) }
        try {
            when {
                m.kind == MoveKind.SEND_COIN -> {
                    val token = m.token ?: throw MoveRefused(tr("Unknown coin."))
                    val decimals = m.decimals ?: throw MoveRefused(tr("Unknown coin."))
                    if (from == Chain.SOLANA) sendCoinSol(id, i, token, m.symbol, decimals, recipient, BigInteger(leg.amountIn), preset)
                    else sendCoinEvm(id, i, from, token, decimals, recipient, BigInteger(leg.amountIn), preset)
                }
                leg.kind == LegKind.SEND && from == Chain.SOLANA -> sendSol(id, i, recipient, BigInteger(leg.amountIn), preset)
                leg.kind == LegKind.SEND -> sendEvm(id, i, from, recipient, BigInteger(leg.amountIn), preset)
                from == Chain.SOLANA -> bridgeFromSol(id, i, to, recipient, BigInteger(leg.amountIn), preset, exactOut, cap)
                else -> bridgeFromEvm(id, i, from, to, recipient, BigInteger(leg.amountIn), preset, exactOut, cap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MoveRefused) {
            fail(id, i, e.message ?: tr("Could not start."))
        } catch (e: Exception) {
            val l = store.get(id)?.legs?.getOrNull(i)
            // After a signature the transaction may be out: never say it failed when it is not known.
            if (l?.hash != null) fail(id, i, tr("Not confirmed. Check the explorer."), LegStatus.UNKNOWN) else fail(id, i, shortErr(e))
        }
        balancesLater()
    }

    private fun balancesLater() {
        scope.launch { runCatching { balances.refresh() } }
    }

    private fun signedAt(id: String, i: Int, hash: String) {
        store.leg(id, i) { it.copy(status = LegStatus.SENT, hash = hash, sentAt = now()) }
    }

    private fun gasUsd(chain: Chain, units: BigInteger?): Double? = units?.let { u -> trading.priceOf(chain)?.let { trading.usdOf(u, chain.decimals, it) } }

    /** A plain send of `value` wei to `recipient`, read and simulated on the chain's own RPCs. */
    private suspend fun sendEvm(id: String, i: Int, chain: Chain, recipient: String, value: BigInteger, preset: Preset) {
        val evm = trading.evmOf(chain) ?: throw MoveRefused(tr("This version of Nesty cannot send on {1}.", chain.title))
        val signer = session.evmSigner() ?: throw MoveRefused(tr("Unlock your wallets first."))
        val reader = directReader(evm)
        // The receiver again, right before signing.
        Recipient.evmKind(reader, recipient).tokenSymbol?.let { throw MoveRefused(Recipient.tokenContract(it)) }
        val tx = TxDraft(recipient, ByteArray(0), value)
        val vctx = ValidationContext(chain = evm, from = signer.address, ownWallets = session.ownEvm(), maxValue = value, payee = recipient, nowS = now() / 1000)
        val r = trading.engine.sendPlain(evm.id, signer, tx, vctx, GasKind.TRANSFER, Trading.prefsOf(evm, preset), simulate = true, reader = reader) { h -> signedAt(id, i, h) }
        when (r) {
            is EvmTrader.Plain.Refused -> fail(id, i, r.reason)
            is EvmTrader.Plain.Out -> {
                signedAt(id, i, r.hash)
                val receipt = trading.engine.receiptOf(evm.id, r.hash, signer.address, reader)
                when {
                    receipt == null -> fail(id, i, tr("Not confirmed. Check the explorer."), LegStatus.UNKNOWN)
                    !receipt.success -> fail(id, i, tr("The send failed on chain."))
                    else -> store.leg(id, i) { it.copy(status = LegStatus.DONE, gasUsd = gasUsd(chain, receipt.feeWei), endedAt = now()) }
                }
            }
        }
    }

    /** A plain send of `value` lamports to `recipient`, on Solana's public RPC. */
    private suspend fun sendSol(id: String, i: Int, recipient: String, value: BigInteger, preset: Preset) {
        val signer = session.solSigner() ?: throw MoveRefused(tr("Unlock your wallets first."))
        if (recipient == signer.address) throw MoveRefused(tr("This is the wallet you are sending from."))
        val acc = solDirectRpc.getAccount(recipient)
        val min = rentMin()
        SolSend.recipientBlock(recipient, acc)?.let { throw MoveRefused(it) }
        SolSend.newAddressBlock(acc, value, min)?.let { throw MoveRefused(it) }
        val prefs = Trading.solPrefsOf(preset)
        val ctx = SolTransferContext(wallet = signer.address, to = recipient, asset = null, amount = value, maxPriorityLamports = prefs.priorityLamports)
        val shortfall: suspend (BigInteger) -> String? = { priority -> SolSend.sendBlock(solDirectRpc.getBalance(signer.address), value, SolSend.networkFee(priority), min) }
        val r = solDirect.sendPlain(signer, listOf(Spl.transfer(signer.address, recipient, value)), SolSend.SOL_UNITS, prefs, { SolValidator.validateTransfer(it, ctx) }, shortfall) { sig -> signedAt(id, i, sig) }
        when (r) {
            is SolTrader.Plain.Refused -> fail(id, i, r.reason)
            is SolTrader.Plain.Out -> {
                signedAt(id, i, r.sig)
                when (val end = solDirect.landed(r)) {
                    is SolTrader.PlainEnd.Landed -> store.leg(id, i) { it.copy(status = LegStatus.DONE, gasUsd = gasUsd(Chain.SOLANA, end.feeLamports), endedAt = now()) }
                    is SolTrader.PlainEnd.Failed -> fail(id, i, end.reason, if (end.unknown) LegStatus.UNKNOWN else LegStatus.FAILED)
                }
            }
        }
    }

    /**
     * A send of `amount` of an ERC-20 the wallet holds to `recipient`, as the extension sends one:
     * its balance and decimals read again, the fee covered, the transfer validated (no value, to
     * this payee only) and simulated, all on the chain's own RPCs.
     */
    private suspend fun sendCoinEvm(id: String, i: Int, chain: Chain, token: String, decimals: Int, recipient: String, amount: BigInteger, preset: Preset) {
        val evm = trading.evmOf(chain) ?: throw MoveRefused(tr("This version of Nesty cannot send on {1}.", chain.title))
        val signer = session.evmSigner() ?: throw MoveRefused(tr("Unlock your wallets first."))
        if (sameAddress(recipient, signer.address)) throw MoveRefused(SAME_WALLET)
        val reader = directReader(evm)
        // The receiver again, right before signing.
        Recipient.evmKind(reader, recipient).tokenSymbol?.let { throw MoveRefused(Recipient.tokenContract(it)) }
        // Read together: one round trip.
        val reads = coroutineScope {
            val h = async { orNull { reader.read1(token, Erc20.balanceOf, signer.address) as? BigInteger } }
            val d = async { orNull { (reader.read1(token, Erc20.decimals) as? BigInteger)?.toInt() } }
            val n = async { orNull { reader.balance(signer.address) } }
            val b = async { orNull { reader.baseFee() } }
            CoinReads(h.await(), d.await(), n.await(), b.await())
        }
        val held = reads.held
        val nativeHeld = reads.native
        val base = reads.baseFee
        if (held == null || nativeHeld == null || base == null) throw MoveRefused(tr("Could not read your balances just now. Try again."))
        // The amount was typed in whole coins at these decimals: a coin that says otherwise is not sent.
        if (reads.decimals != null && reads.decimals != decimals) throw MoveRefused(COIN_CHANGED)
        if (held < amount) throw MoveRefused(tr("Not enough tokens."))
        val fees = TradeMath.computeFees(evm, base, Trading.gasOf(evm, preset.speed))
        val short = tr("Not enough {1} for the fee.", evm.nativeSymbol)
        if (nativeHeld < TradeMath.staticGas(evm, GasKind.TOKEN_TRANSFER) * fees.maxFeePerGas) throw MoveRefused(short)
        val tx = TxDraft(token, Erc20.transfer.encode(recipient, amount), BigInteger.ZERO)
        val vctx = ValidationContext(chain = evm, from = signer.address, ownWallets = session.ownEvm(), token = token, maxValue = BigInteger.ZERO, payee = recipient, nowS = now() / 1000)
        // The node refuses a transaction whose gas limit at its top fee is above the balance.
        val gate: (BigInteger) -> String? = { gas -> if (nativeHeld < gas * fees.maxFeePerGas) short else null }
        // Simulated: catches coins that cannot be moved (paused, blocked wallets) before signing.
        val r = trading.engine.sendPlain(evm.id, signer, tx, vctx, GasKind.TOKEN_TRANSFER, Trading.prefsOf(evm, preset), simulate = true, gate = gate, reader = reader) { h -> signedAt(id, i, h) }
        when (r) {
            is EvmTrader.Plain.Refused -> fail(id, i, r.reason)
            is EvmTrader.Plain.Out -> {
                signedAt(id, i, r.hash)
                val receipt = trading.engine.receiptOf(evm.id, r.hash, signer.address, reader)
                when {
                    receipt == null -> fail(id, i, tr("Not confirmed. Check the explorer."), LegStatus.UNKNOWN)
                    !receipt.success -> fail(id, i, tr("The send failed on chain."))
                    else -> store.leg(id, i) { it.copy(status = LegStatus.DONE, gasUsd = gasUsd(chain, receipt.feeWei), endedAt = now()) }
                }
            }
        }
    }

    /**
     * A send of `amount` of a Solana coin the wallet holds to `recipient`, as the extension sends
     * one: the receiver's account for the coin opened when missing (paid by the wallet), then one
     * checked transfer, on Solana's public RPC.
     */
    private suspend fun sendCoinSol(id: String, i: Int, mint: String, symbol: String?, decimals: Int, recipient: String, amount: BigInteger, preset: Preset) {
        val signer = session.solSigner() ?: throw MoveRefused(tr("Unlock your wallets first."))
        if (recipient == signer.address) throw MoveRefused(SAME_WALLET)
        val meta = orNull { solDirect.tokenMeta(mint) } ?: throw MoveRefused(COIN_UNREAD)
        SolSend.coinBlock(meta)?.let { throw MoveRefused(it) }
        if (meta.decimals != decimals) throw MoveRefused(COIN_CHANGED)
        val program = meta.tokenProgram
        val accs = solDirectRpc.getMultipleAccounts(
            listOf(recipient, SolAddr.associatedTokenAddress(signer.address, mint, program), SolAddr.associatedTokenAddress(recipient, mint, program)),
        )
        SolSend.recipientBlock(recipient, accs.getOrNull(0))?.let { throw MoveRefused(it) }
        val held = accs.getOrNull(1)?.let { Layouts.decodeTokenAccount(it.data)?.amount } ?: BigInteger.ZERO
        if (held < amount) throw MoveRefused(tr("Not enough {1}.", meta.symbol.ifBlank { tr("tokens") }))
        val rent = if (accs.getOrNull(2) == null) tokenAccountRent(meta) else BigInteger.ZERO
        val min = rentMin()
        val prefs = Trading.solPrefsOf(preset)
        val asset = SolAsset(mint, program, meta.decimals, symbol ?: meta.symbol)
        val ctx = SolTransferContext(wallet = signer.address, to = recipient, asset = asset, amount = amount, maxPriorityLamports = prefs.priorityLamports)
        // Opened if missing (idempotent): the receiver may close it before this lands.
        val ixs = listOf(
            Spl.createAtaIdempotent(recipient, mint, program, payer = signer.address),
            Spl.transferChecked(signer.address, recipient, mint, program, amount, meta.decimals),
        )
        val shortfall: suspend (BigInteger) -> String? = { priority -> SolSend.coinSendBlock(solDirectRpc.getBalance(signer.address), rent, SolSend.networkFee(priority), min) }
        val r = solDirect.sendPlain(signer, ixs, SolSend.COIN_UNITS, prefs, { SolValidator.validateTransfer(it, ctx) }, shortfall) { sig -> signedAt(id, i, sig) }
        when (r) {
            is SolTrader.Plain.Refused -> fail(id, i, r.reason)
            is SolTrader.Plain.Out -> {
                signedAt(id, i, r.sig)
                when (val end = solDirect.landed(r)) {
                    is SolTrader.PlainEnd.Landed -> store.leg(id, i) { it.copy(status = LegStatus.DONE, gasUsd = gasUsd(Chain.SOLANA, end.feeLamports), endedAt = now()) }
                    is SolTrader.PlainEnd.Failed -> fail(id, i, end.reason, if (end.unknown) LegStatus.UNKNOWN else LegStatus.FAILED)
                }
            }
        }
    }

    /** Keeps a fresh quote's figures on the leg before its deposit is signed. */
    private fun quoted(id: String, i: Int, from: Chain, to: Chain, terms: Relay.Terms) {
        val pIn = trading.priceOf(from)
        val pOut = trading.priceOf(to)
        store.leg(id, i) {
            it.copy(
                amountIn = terms.amountIn.toString(),
                amountOut = terms.amountOut.toString(),
                usdIn = pIn?.let { p -> trading.usdOf(terms.amountIn, from.decimals, p) } ?: it.usdIn,
                usdOut = pOut?.let { p -> trading.usdOf(terms.amountOut, to.decimals, p) } ?: it.usdOut,
                quoteFeeUsd = terms.feeUsd,
                timeS = terms.timeEstimateS,
                requestId = terms.requestId,
                statusPath = terms.statusPath,
            )
        }
    }

    /** The receiver checks of a bridge's payout on `to`: a token contract, an address funds are lost at, a new Solana wallet paid too little. */
    private suspend fun payoutChecks(to: Chain, recipient: String, minOut: BigInteger) {
        if (to == Chain.SOLANA) {
            val acc = solDirectRpc.getAccount(recipient)
            SolSend.recipientBlock(recipient, acc)?.let { throw MoveRefused(it) }
            SolSend.payoutBlock(acc, minOut, rentMin())?.let { throw MoveRefused(it) }
        } else if (!sameAddress(recipient, own(to))) {
            trading.evmOf(to)?.let { evm -> Recipient.evmKind(directReader(evm), recipient).tokenSymbol?.let { throw MoveRefused(Recipient.tokenContract(it)) } }
        }
    }

    private fun sameAddress(a: String, b: String?) = b != null && a.equals(b, ignoreCase = true)

    /** A bridge out of an EVM chain: a fresh quote, its deposit checked and signed, then followed until it pays out. */
    private suspend fun bridgeFromEvm(id: String, i: Int, from: Chain, to: Chain, recipient: String, amount: BigInteger, preset: Preset, exactOut: BigInteger?, cap: BigInteger?) {
        val evm = trading.evmOf(from) ?: throw MoveRefused(tr("This version of Nesty cannot move funds from {1}.", from.title))
        if (!Plans.canLeave(from)) throw MoveRefused(ELYSIUM_OUT)
        val signer = session.evmSigner() ?: throw MoveRefused(tr("Unlock your wallets first."))
        val ask = Relay.Ask(signer.address, recipient, evm.id, Plans.bridgeId(to), exactOut ?: amount, if (exactOut != null) cap else null)
        // Always a fresh quote right before signing; its checks pin what it may do.
        val q = try {
            relay.quote(ask)
        } catch (e: Relay.RelayError) {
            throw MoveRefused(bridgeSays(e.message))
        }
        payoutChecks(to, recipient, q.terms.minOut)
        quoted(id, i, from, to, q.terms)
        val vctx = ValidationContext(
            chain = evm,
            from = signer.address,
            ownWallets = session.ownEvm(),
            extraContracts = mapOf(Relay.DEPOSITORY to Relay.rule),
            maxValue = q.tx.value,
            nowS = now() / 1000,
        )
        // A contract call quoted by an outside service: always measured, never a static guess.
        val r = trading.engine.sendPlain(evm.id, signer, q.tx, vctx, GasKind.DEPOSIT, Trading.prefsOf(evm, preset), simulate = true) { h -> signedAt(id, i, h) }
        when (r) {
            is EvmTrader.Plain.Refused -> fail(id, i, r.reason)
            is EvmTrader.Plain.Out -> {
                signedAt(id, i, r.hash)
                val receipt = trading.engine.receiptOf(evm.id, r.hash, signer.address)
                when {
                    receipt == null -> fail(id, i, tr("Not confirmed. Check the explorer."), LegStatus.UNKNOWN)
                    !receipt.success -> fail(id, i, tr("The deposit failed on chain. Nothing was moved."))
                    else -> {
                        store.leg(id, i) { it.copy(status = LegStatus.MOVING, gasUsd = gasUsd(from, receipt.feeWei)) }
                        follow(id, i)
                    }
                }
            }
        }
    }

    /** A bridge out of Solana to an EVM chain: a fresh quote, its deposit built around the quoted id, then followed. */
    private suspend fun bridgeFromSol(id: String, i: Int, to: Chain, recipient: String, amount: BigInteger, preset: Preset, exactOut: BigInteger?, cap: BigInteger?) {
        val signer = session.solSigner() ?: throw MoveRefused(tr("Unlock your wallets first."))
        val ask = Relay.Ask(signer.address, recipient, Relay.SOLANA_CHAIN_ID, Plans.bridgeId(to), exactOut ?: amount, if (exactOut != null) cap else null)
        val q = try {
            relay.quoteFromSolana(ask)
        } catch (e: Relay.RelayError) {
            throw MoveRefused(bridgeSays(e.message))
        }
        payoutChecks(to, recipient, q.terms.minOut)
        quoted(id, i, Chain.SOLANA, to, q.terms)
        val amountIn = q.terms.amountIn
        val min = rentMin()
        val prefs = Trading.solPrefsOf(preset)
        val ctx = RelayDepositContext(signer.address, amountIn, q.depositId, maxSpend = amountIn, maxPriorityLamports = prefs.priorityLamports)
        // The deposit moves SOL out like a transfer: what the wallet keeps must be zero or enough to exist.
        val shortfall: suspend (BigInteger) -> String? = { priority -> SolSend.sendBlock(solDirectRpc.getBalance(signer.address), amountIn, SolSend.networkFee(priority), min) }
        val r = trading.sol.sendPlain(
            signer,
            listOf(RelaySol.depositNative(signer.address, amountIn, q.depositId)),
            RelaySol.DEPOSIT_UNITS,
            prefs,
            { SolValidator.validateRelayDeposit(it, ctx) },
            shortfall,
        ) { sig -> signedAt(id, i, sig) }
        when (r) {
            is SolTrader.Plain.Refused -> fail(id, i, r.reason)
            is SolTrader.Plain.Out -> {
                signedAt(id, i, r.sig)
                when (val end = trading.sol.landed(r)) {
                    is SolTrader.PlainEnd.Landed -> {
                        store.leg(id, i) { it.copy(status = LegStatus.MOVING, gasUsd = gasUsd(Chain.SOLANA, end.feeLamports)) }
                        follow(id, i)
                    }
                    is SolTrader.PlainEnd.Failed -> fail(id, i, end.reason, if (end.unknown) LegStatus.UNKNOWN else LegStatus.FAILED)
                }
            }
        }
    }

    /**
     * Follows the bridge until it pays out, refunds or fails: every 3 s for the extension's
     * 15 minutes, then every 30 s for up to two hours. Longer than that it is marked as not known.
     */
    private suspend fun follow(id: String, i: Int) {
        val leg = store.get(id)?.legs?.getOrNull(i) ?: return
        val path = leg.statusPath ?: return fail(id, i, tr("Not confirmed. Check the explorer."), LegStatus.UNKNOWN)
        val to = leg.toChain ?: return
        val from = leg.fromChain ?: return
        val started = now()
        var last = leg.bridgeStatus
        var outHash = leg.outHash
        while (now() - started < FOLLOW_LIMIT_MS) {
            delay(if (now() - started < Relay.FOLLOW_MS) Relay.FOLLOW_POLL_MS else SLOW_POLL_MS)
            val r = orNull { relay.status(path, Plans.bridgeId(to)) } ?: continue
            if (r.status != last || (r.outHash != null && r.outHash != outHash)) {
                last = r.status
                outHash = r.outHash ?: outHash
                store.leg(id, i) { it.copy(bridgeStatus = r.status, outHash = outHash) }
            }
            when (r.status) {
                "success" -> {
                    store.leg(id, i) { it.copy(status = LegStatus.DONE, endedAt = now()) }
                    return
                }
                "refund" -> return fail(id, i, tr("The bridge gave it back on {1}.", from.title), LegStatus.REFUNDED)
                "failure" -> return fail(id, i, tr("The bridge did not pay it out. Anything it gives back arrives on {1}.", from.title))
            }
        }
        fail(id, i, tr("The bridge has not said it paid out yet. Check your balances later."), LegStatus.UNKNOWN)
    }

    /** A withdrawal or a coin send whose legs all ended: its state and a note. */
    private fun settle(id: String) {
        val m = store.get(id) ?: return
        if (m.kind == MoveKind.BUY) return
        val s = m.settled()
        if (s == MoveState.WORKING) return
        store.update(id) { it.copy(state = s) }
        if (m.kind == MoveKind.SEND_COIN) return settleCoin(m, s)
        val to = m.toChain?.title ?: tr("the chain")
        val who = Fmt.address(m.recipient)
        notesFlow.tryEmit(
            when (s) {
                MoveState.DONE -> MoveNote(id, tr("Sent {1}", Fmt.usdFull(m.usd)), tr("to {1} on {2}", who, to), ok = true)
                MoveState.PARTIAL -> MoveNote(id, tr("Part of your withdrawal arrived"), tr("About {1} to {2}. See Activity.", Fmt.usdFull(m.arrivedUsd), who), ok = false)
                else -> MoveNote(id, tr("Withdrawal not sent"), m.legs.firstNotNullOfOrNull { it.error } ?: tr("Stopped"), ok = false)
            },
        )
    }

    /**
     * A coin send that ended. One that landed is kept with the coin's fills at what it was worth
     * then, so the position's result stays where it stood; then its note.
     */
    private fun settleCoin(m: Move, s: MoveState) {
        val leg = m.legs.firstOrNull() ?: return
        val chain = m.toChain ?: return
        val coin = m.symbol ?: tr("coins")
        val token = m.token
        val decimals = m.decimals
        val hash = leg.hash
        if (s == MoveState.DONE && token != null && decimals != null && hash != null) {
            val wallet = session.wallets.firstOrNull { it.id == m.walletId }
            val address = wallet?.let { if (chain.isEvm) it.evm else it.sol }
            if (address != null) {
                val price = trading.prices[Balances.tokenKey(chain, token)]
                val usd = price?.let { trading.usdOf(m.coinRaw, decimals, it) } ?: leg.usdIn
                trading.recordSend(address, chain, token, m.symbol, decimals, m.coinRaw, hash, usd, leg.gasUsd)
            }
        }
        val who = Fmt.address(m.recipient)
        notesFlow.tryEmit(
            when (s) {
                MoveState.DONE -> MoveNote(m.id, tr("Sent {1} {2}", Fmt.amount(m.coinTokens), coin), tr("to {1} on {2}", who, chain.title), ok = true)
                MoveState.PARTIAL -> MoveNote(m.id, tr("Send not confirmed"), leg.error ?: tr("Check the explorer."), ok = false)
                else -> MoveNote(m.id, tr("{1} not sent", coin), leg.error ?: tr("Stopped"), ok = false)
            },
        )
    }

    /** The moved funds of a buy landed (or not): buy now if its price is still fresh, else ask. */
    private suspend fun afterMove(id: String, confirmedAt: Long, resumed: Boolean) {
        val m = store.get(id) ?: return
        val leg = m.legs.firstOrNull() ?: return
        val chain = m.toChain ?: return
        if (leg.status != LegStatus.DONE) {
            store.update(id) { it.copy(state = MoveState.FAILED) }
            val sent = leg.hash != null
            notesFlow.tryEmit(MoveNote(id, if (sent) tr("Move failed") else tr("Move not sent"), leg.error ?: tr("Stopped"), ok = false))
            return
        }
        val token = m.token ?: return
        if (resumed || now() - confirmedAt > FRESH_MS) {
            store.update(id) { it.copy(state = MoveState.ASK) }
            notesFlow.tryEmit(MoveNote(id, tr("Moved to {1}", chain.title), tr("Buy {1} at today's price, or keep the funds there. See Portfolio.", m.symbol ?: tr("the coin")), ok = true))
            return
        }
        store.update(id) { it.copy(state = MoveState.BUYING) }
        val info = CoinInfo(m.symbol, m.decimals, m.supply, m.hints, tax = m.tax, onCurve = m.onCurve, launchpad = m.launchpad)
        startBuy(id, chain, token, m.usd, m.preset, info, leg)
    }

    /**
     * Waits for the moved funds to show in the chain's balance, then buys; the move ends with the
     * buy. What the move cost joins the buy's cost basis, and the move its page.
     */
    private suspend fun startBuy(id: String, chain: Chain, token: String, usd: Double, presetIndex: Int, info: CoinInfo, leg: MoveLeg): StateFlow<EngineTrade>? {
        val preset = settings.presets[presetIndex.coerceIn(0, 2)]
        val moved = Trading.Moved(
            FillMove(
                from = leg.from,
                sentUsd = leg.usdIn,
                arrivedUsd = leg.usdOut,
                feeUsd = Plans.moveCostUsd(leg),
                ms = if (leg.sentAt != null && leg.endedAt != null) (leg.endedAt - leg.sentAt).coerceAtLeast(0) else null,
            ),
            listOfNotNull(leg.hash?.let { FillTx("move", leg.from, it) }, leg.outHash?.let { FillTx("arrive", leg.to, it) }),
        )
        val price = trading.priceOf(chain)
        val wei = price?.let { trading.weiOf(usd, chain, it) }
        for (attempt in 0 until ARRIVAL_TRIES) {
            runCatching { balances.refresh() }
            val have = balances.active?.raw?.get(chain)
            if (wei == null || (have != null && have >= wei)) break
            delay(ARRIVAL_WAIT_MS)
        }
        val flow = try {
            trading.buy(chain, token, usd, preset, info, presetIndex = presetIndex, moved = moved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing was signed: the funds are on the chain, and the user decides again.
            val why = e.message ?: tr("Could not start.")
            store.update(id) { it.copy(state = MoveState.ASK, buyError = why) }
            notesFlow.tryEmit(MoveNote(id, tr("Moved, but the buy was not sent"), tr("{1} The funds are in your {2} balance.", why, chain.title), ok = false))
            return null
        }
        buys[id] = flow
        scope.launch {
            // The swap's hash as soon as it is out: a buy the app was closed during is then never asked again.
            flow.first { it.swap.hash != null || it.status != TradeStatus.WORKING }.swap.hash?.let { h -> store.update(id) { it.copy(buyTx = h) } }
            val t = flow.first { it.status != TradeStatus.WORKING }
            store.update(id) {
                if (t.status == TradeStatus.DONE) it.copy(state = MoveState.DONE, buyTx = t.swap.hash)
                else it.copy(state = MoveState.FAILED, buyTx = t.swap.hash, buyError = t.swap.error ?: tr("Stopped"))
            }
        }
        return flow
    }

    // ── after the app was closed ────────────────────────────────────────────────────

    /**
     * Follows again every move the app was closed in the middle of. A transaction that was being
     * signed may or may not have gone out: it is marked as not known, never as failed. A buy whose
     * funds landed meanwhile is asked again, never bought without the user.
     */
    fun resume() {
        for (m in store.moves.filter { it.state == MoveState.WORKING || it.state == MoveState.BUYING }) {
            if (!running.add(m.id)) continue
            scope.launch {
                try {
                    coroutineScope { m.legs.indices.forEach { i -> launch { resumeLeg(m.id, i) } } }
                    when (m.kind) {
                        MoveKind.WITHDRAW, MoveKind.SEND_COIN -> settle(m.id)
                        MoveKind.BUY -> {
                            val now = store.get(m.id) ?: return@launch
                            when {
                                // The buy went out before the app closed: how it ended is in the wallet's balance and activity.
                                now.state == MoveState.BUYING && now.buyTx != null -> store.update(m.id) { it.copy(state = MoveState.DONE) }
                                now.state == MoveState.BUYING -> store.update(m.id) { it.copy(state = MoveState.ASK) }
                                else -> afterMove(m.id, 0, resumed = true)
                            }
                        }
                    }
                } finally {
                    running -= m.id
                }
            }
        }
    }

    private suspend fun resumeLeg(id: String, i: Int) {
        val leg = store.get(id)?.legs?.getOrNull(i) ?: return
        val from = leg.fromChain ?: return
        when (leg.status) {
            LegStatus.WAITING -> fail(id, i, tr("The app closed before it was sent."))
            LegStatus.SENDING -> if (leg.hash == null) {
                fail(id, i, tr("The app closed while it was being sent. Your balances show whether it went out."), LegStatus.UNKNOWN)
            } else watchSent(id, i, leg, from)
            LegStatus.SENT -> watchSent(id, i, leg, from)
            LegStatus.MOVING -> follow(id, i)
            else -> Unit
        }
    }

    /** A leg sent before the app closed: its receipt (or signature), then the bridge. */
    private suspend fun watchSent(id: String, i: Int, leg: MoveLeg, from: Chain) {
        val hash = leg.hash ?: return
        val landed: Boolean? = if (from == Chain.SOLANA) {
            var meta: trade.nesty.app.sol.TxMeta? = null
            for (attempt in 0 until SOL_RESUME_TRIES) {
                meta = orNull { solDirectRpc.getTransactionMeta(hash) }
                if (meta != null) break
                delay(Relay.FOLLOW_POLL_MS)
            }
            meta?.let { it.err == null }.also { ok -> if (ok == true) store.leg(id, i) { it.copy(gasUsd = gasUsd(Chain.SOLANA, meta?.fee)) } }
        } else {
            val evm = trading.evmOf(from) ?: return
            val signer = own(from) ?: return
            val reader = if (leg.kind == LegKind.SEND) directReader(evm) else null
            trading.engine.receiptOf(evm.id, hash, signer, reader)?.also { r -> store.leg(id, i) { it.copy(gasUsd = gasUsd(from, r.feeWei)) } }?.success
        }
        when (landed) {
            null -> fail(id, i, tr("Not confirmed. Check the explorer."), LegStatus.UNKNOWN)
            false -> fail(id, i, if (leg.kind == LegKind.SEND) tr("The send failed on chain.") else tr("The deposit failed on chain. Nothing was moved."))
            true -> if (leg.kind == LegKind.SEND) {
                store.leg(id, i) { it.copy(status = LegStatus.DONE, endedAt = now()) }
            } else {
                store.leg(id, i) { it.copy(status = LegStatus.MOVING) }
                follow(id, i)
            }
        }
    }

    companion object {
        /** The bridge's own words, without its name: the screens name no provider. */
        fun bridgeSays(message: String?): String = (message ?: tr("The bridge did not answer.")).replace(Regex("(?i)\\brelay\\b"), "bridge")

        val ELYSIUM_OUT: String get() = tr("Funds cannot leave Elysium for another chain in this version of Nesty. They can still be sent on Elysium.")

        val SAME_WALLET: String get() = tr("This is the wallet you are sending from.")
        val COIN_UNREAD: String get() = tr("Could not read this coin just now. Try again.")
        val COIN_CHANGED: String get() = tr("This coin's details changed. Open it again and check the amount.")

        /** A wallet's rent exemption until the chain is read (it was 890,880 lamports for years). */
        val DEFAULT_RENT: BigInteger = BigInteger.valueOf(890_880)
        private const val RENT_TTL_MS = 10 * 60_000L

        /** How long after the user confirmed a moved buy still goes out at the price the sheet showed. */
        const val FRESH_MS = 3 * 60_000L
        private const val FOLLOW_LIMIT_MS = 2 * 60 * 60_000L
        private const val SLOW_POLL_MS = 30_000L
        private const val ARRIVAL_TRIES = 15
        private const val ARRIVAL_WAIT_MS = 2_000L
        private const val SOL_RESUME_TRIES = 40
    }
}
