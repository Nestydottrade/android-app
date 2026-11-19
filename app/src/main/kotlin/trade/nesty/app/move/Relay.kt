package trade.nesty.app.move

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import trade.nesty.app.data.await
import trade.nesty.app.evm.AbiFunction
import trade.nesty.app.evm.ContractRule
import trade.nesty.app.evm.TxDraft
import trade.nesty.app.evm.Validator
import trade.nesty.app.evm.ZERO_ADDRESS
import trade.nesty.app.evm.act
import trade.nesty.app.evm.hexBytes
import trade.nesty.app.evm.sameAddress
import trade.nesty.app.evm.sanitizeLabel
import trade.nesty.app.i18n.tr
import trade.nesty.app.sol.AccountMeta
import trade.nesty.app.sol.Instruction
import trade.nesty.app.sol.SolAddr
import trade.nesty.app.sol.bytesOf
import trade.nesty.app.sol.concat
import trade.nesty.app.sol.le
import trade.nesty.app.sol.meta
import trade.nesty.app.sol.u64
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * Bridging through Relay (relay.link), ported from the extension's trade/relay.ts. Relay quotes
 * one deposit into its depository on the origin chain; a solver then pays the recipient on the
 * destination chain. The phone asks Relay itself: Nesty's server never sees a quote.
 *
 * On the EVM chains the depository is the same address everywhere, and the only calls Nesty
 * makes to it are depositNative(depositor, id) and, from a chain whose coin Relay does not hold,
 * depositErc20 after an exact approval. On Solana it is Relay's program, and the only
 * instruction is deposit_native, which Nesty builds itself around the quoted id.
 *
 * Exact output (a buy's missing amount, to the wallet's own address) is the app's addition:
 * Relay then names what goes in, and the quote passes only below a cap the user confirmed.
 */
object Relay {
    const val API = "https://api.relay.link"
    const val DEPOSITORY = "0x4cd00e387622c35bddb9b4c962c136462338bc31"
    const val NATIVE = ZERO_ADDRESS

    /** Relay's address for SOL. */
    const val SOLANA_NATIVE = "11111111111111111111111111111111"

    /** Solana's chain id at Relay (and in the extension's tickets). */
    const val SOLANA_CHAIN_ID = 792_703_809L

    /**
     * Slippage asked of Relay, in basis points. Payouts land within seconds, so 0.5% leaves room
     * for the price to move meanwhile; Relay pays at least the minimum or refunds the deposit.
     */
    const val SLIPPAGE_BPS = 50

    /** How long a bridge is followed once its deposit landed, and how often. */
    const val FOLLOW_MS = 15 * 60_000L
    const val FOLLOW_POLL_MS = 3_000L
    val FINAL = setOf("success", "failure", "refund")

    val BYTES32 = Regex("^0x[0-9a-fA-F]{64}$")
    private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val SOL_SIGNATURE = Regex("^[1-9A-HJ-NP-Za-km-z]{64,88}$")
    private val STATUS_PATH = Regex("^/intents/status(?:/v\\d)?\\?requestId=(0x[0-9a-fA-F]{64})$")
    private val WEI = Regex("^[0-9]+$")

    val depositNative = AbiFunction("function depositNative(address depositor, bytes32 id) payable")
    val depositErc20 = AbiFunction("function depositErc20(address depositor, address token, uint256 amount, bytes32 id)")
    private val erc20Approve = Validator.erc20Approve

    fun isSolana(chainId: Long) = chainId == SOLANA_CHAIN_ID

    fun isStatusPath(path: String) = STATUS_PATH.matches(path)

    // ── the quote, as the extension's zod schema reads it ──────────────────────────────

    sealed interface TxData {
        data class Evm(val to: String, val data: String, val value: String, val chainId: Double) : TxData
        data class Sol(val instructions: List<QuoteIx>) : TxData
    }

    data class QuoteIx(val programId: String, val keys: List<AccountMeta>, val data: String)

    data class Item(val data: TxData, val endpoint: String?)

    data class Step(val id: String, val kind: String, val requestId: String?, val items: List<Item>)

    /** A fee entry: present (an object, maybe without a dollar figure) or absent. */
    data class Fee(val amountUsd: String?)

    data class Quote(
        val steps: List<Step>,
        /** Null when the quote has no fees at all; an entry is null when Relay sent null for it. */
        val fees: Map<String, Fee?>?,
        val recipient: String,
        val timeEstimate: Double?,
        val outChainId: Double,
        val outAddress: String,
        val amountOut: BigInteger,
        val minimumOut: BigInteger,
        /** What goes in, as Relay says: read for exact-output quotes only. */
        val amountIn: BigInteger?,
    )

    class RelayError(message: String) : Exception(message)

    private fun format(): Nothing = throw RelayError(tr("Unexpected quote format"))

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: format()

    private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: format()

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: format()

    private fun JsonElement?.num(): Double = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: format()

    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: format()

    /** An optional field: absent passes, present must be the type (null too is refused, as zod does). */
    private inline fun <T> JsonObject.opt(key: String, read: (JsonElement) -> T): T? = if (key in this) read(this[key]!!) else null

    private fun wei(e: JsonElement?): BigInteger = e.str().takeIf { WEI.matches(it) }?.let { BigInteger(it) } ?: format()

    private fun txData(e: JsonElement?): TxData {
        val o = e.obj()
        // As zod's union: the EVM shape first, then Solana's.
        evmData(o)?.let { return it }
        val ixs = o["instructions"].arr().map { ix ->
            val io = ix.obj()
            QuoteIx(
                programId = io["programId"].str(),
                keys = io["keys"].arr().map { k ->
                    val ko = k.obj()
                    AccountMeta(ko["pubkey"].str(), isSigner = ko["isSigner"].bool(), isWritable = ko["isWritable"].bool())
                },
                data = io["data"].str(),
            )
        }
        return TxData.Sol(ixs)
    }

    private fun evmData(o: JsonObject): TxData.Evm? = try {
        TxData.Evm(o["to"].str(), o["data"].str(), o["value"].str(), o["chainId"].num())
    } catch (e: RelayError) {
        null
    }

    /** Relay's answer read as the extension reads it: anything else is "Unexpected quote format". */
    fun parse(e: JsonElement): Quote {
        val o = e.obj()
        val steps = o["steps"].arr().map { s ->
            val so = s.obj()
            Step(
                id = so["id"].str(),
                kind = so["kind"].str(),
                requestId = so.opt("requestId") { it.str() },
                items = so["items"].arr().map { i ->
                    val io = i.obj()
                    Item(txData(io["data"]), io.opt("check") { c -> c.obj()["endpoint"].str() })
                },
            )
        }
        val fees = o.opt("fees") { f ->
            f.obj().mapValues { (_, v) ->
                if (v is JsonNull) null else Fee(v.obj().opt("amountUsd") { it.str() })
            }
        }
        val details = o["details"].obj()
        val out = details["currencyOut"].obj()
        val currency = out["currency"].obj()
        val amountIn = (details["currencyIn"] as? JsonObject)?.get("amount")?.let { a ->
            (a as? JsonPrimitive)?.takeIf { it.isString && WEI.matches(it.content) }?.let { BigInteger(it.content) }
        }
        return Quote(
            steps = steps,
            fees = fees,
            recipient = details["recipient"].str(),
            timeEstimate = details.opt("timeEstimate") { it.num() },
            outChainId = currency["chainId"].num(),
            outAddress = currency["address"].str(),
            amountOut = wei(out["amount"]),
            minimumOut = wei(out["minimumAmount"]),
            amountIn = amountIn,
        )
    }

    // ── what each direction checks ─────────────────────────────────────────────────────

    /** What a quote says in either direction: amounts, fees and the request to follow. */
    data class Terms(
        val amountIn: BigInteger,
        val amountOut: BigInteger,
        /** The least Relay commits to pay out. */
        val minOut: BigInteger,
        val feeUsd: Double?,
        val timeEstimateS: Double?,
        /** Relay's id for the request (not the deposit's id). */
        val requestId: String,
        /** Relay's status path for this request: /intents/status?requestId=0x… */
        val statusPath: String,
    )

    /** From an EVM chain: the deposit transaction. */
    data class EvmQuote(val terms: Terms, val tx: TxDraft)

    /** From an EVM chain in one of its tokens: the deposit of exactly the amount, after an approval. */
    data class TokenQuote(val terms: Terms, val token: String, val tx: TxDraft)

    /** From Solana: the deposit's id; the instruction is built around it (RelaySol.depositNative). */
    class SolQuote(val terms: Terms, val depositId: ByteArray)

    /** Who and what a quote is asked for. `recipient` is base58 on Solana, 0x elsewhere. */
    data class Ask(
        val user: String,
        val recipient: String,
        val fromChainId: Long,
        val toChainId: Long,
        /** EXACT_INPUT: what goes in. Exact output: what must come out. */
        val amount: BigInteger,
        /** Exact output only: the most that may go in, as the user confirmed it. */
        val cap: BigInteger? = null,
        /** The token deposited, from a chain whose coin Relay does not hold. */
        val token: String? = null,
    ) {
        val exactOut: Boolean get() = cap != null
    }

    private class Checked(val item: Item, val terms: Terms, val approve: Item?)

    /**
     * What every direction checks, in the extension's order: one transaction step (a token deposit
     * may follow Relay's approval step), the recipient asked for, the destination's own coin paid
     * out, and no more slippage than asked for. Exact output also pays at least what was asked.
     */
    private fun checkTerms(q: Quote, p: Ask, approval: Boolean = false): Checked {
        val lead = if (approval && q.steps.size == 2) q.steps[0] else null
        val step = q.steps.lastOrNull()
        if (step == null || q.steps.size != (if (lead != null) 2 else 1) || q.steps.any { it.items.size != 1 }) {
            throw RelayError(tr("Quote has unexpected steps"))
        }
        if (lead != null && (lead.id != "approve" || lead.kind != "transaction")) throw RelayError(tr("Quote has unexpected steps"))
        val item = step.items[0]
        if (step.kind != "transaction") throw RelayError(tr("Quote step is not a transaction"))

        val toSolana = isSolana(p.toChainId)
        val r = q.recipient
        // Base58 is case-sensitive: a Solana recipient must match exactly.
        val sameRecipient = if (toSolana) r == p.recipient else EVM_ADDRESS.matches(r) && EVM_ADDRESS.matches(p.recipient) && sameAddress(r, p.recipient)
        if (!sameRecipient) throw RelayError(tr("Quote recipient differs from your wallet"))
        if (q.outChainId != p.toChainId.toDouble()) throw RelayError(tr("Quote pays out on a different chain"))
        if (q.outAddress.lowercase() != (if (toSolana) SOLANA_NATIVE.lowercase() else NATIVE)) throw RelayError(tr("Quote pays out a token, not the native coin"))

        val amountOut = q.amountOut
        val minOut = q.minimumOut
        if (p.exactOut && amountOut < p.amount) throw RelayError(tr("Quote pays out less than asked"))
        // One unit of slack for rounding: Relay floors the minimum.
        val reference = if (p.exactOut) p.amount else amountOut
        val floor = reference * BigInteger.valueOf((10_000 - SLIPPAGE_BPS).toLong()) / BigInteger.valueOf(10_000)
        if (minOut.signum() == 0 || minOut + BigInteger.ONE < floor) throw RelayError(tr("Quote allows more slippage than requested"))

        // The request is followed by Relay's id for it, which is not the deposit's id.
        val endpoint = item.endpoint
        val fromPath = endpoint?.let { STATUS_PATH.find(it)?.groupValues?.get(1) }
        if (endpoint != null && fromPath == null) throw RelayError(tr("Unexpected status path"))
        val requestId = fromPath ?: step.requestId
        if (requestId == null || !BYTES32.matches(requestId)) throw RelayError(tr("Quote has no request id"))
        for (s in listOfNotNull(lead, step)) {
            if (s.requestId != null && !s.requestId.equals(requestId, ignoreCase = true)) throw RelayError(tr("Quote names two requests"))
        }

        // Relay lists sub-fees next to their totals: add the two top-level ones, origin gas + relayer.
        val fees = q.fees
        val feeUsd = if (fees != null && (fees["gas"] != null || fees["relayer"] != null)) {
            jsNumber(fees["gas"]?.amountUsd) + jsNumber(fees["relayer"]?.amountUsd)
        } else null
        return Checked(
            item = item,
            approve = lead?.items?.get(0),
            terms = Terms(
                amountIn = p.amount,
                amountOut = amountOut,
                minOut = minOut,
                feeUsd = feeUsd?.takeIf { it.isFinite() },
                timeEstimateS = q.timeEstimate,
                requestId = requestId,
                statusPath = endpoint ?: "/intents/status?requestId=$requestId",
            ),
        )
    }

    /**
     * From an EVM chain in its own coin. Passes only one deposit transaction on the origin chain,
     * into the depository, credited to our own wallet: for exactly the amount, or with exact output
     * for what Relay names up to the cap.
     */
    fun checkQuote(q: Quote, p: Ask): EvmQuote {
        val c = checkTerms(q, p)
        val d = c.item.data
        if (d !is TxData.Evm || d.chainId != p.fromChainId.toDouble()) throw RelayError(tr("Quote is for a different chain"))
        if (!EVM_ADDRESS.matches(d.to) || !sameAddress(d.to, DEPOSITORY)) throw RelayError(tr("Quote targets an unknown contract"))
        val value = d.value.toBigIntegerOrNull() ?: throw RelayError(tr("Quote value differs from the amount"))
        val amountIn = if (p.exactOut) {
            if (value.signum() <= 0 || (q.amountIn != null && q.amountIn != value)) throw RelayError(tr("Quote value differs from the amount"))
            if (value > p.cap!!) throw RelayError(tr("Quote asks for more than you confirmed"))
            value
        } else {
            if (value != p.amount) throw RelayError(tr("Quote value differs from the amount"))
            p.amount
        }
        val data = runCatching { d.data.hexBytes() }.getOrNull()
        val args = data?.let { runCatching { depositNative.decodeCall(it) }.getOrNull() } ?: throw RelayError(tr("Quote calldata is not a native deposit"))
        if (!sameAddress(args[0] as String, p.user)) throw RelayError(tr("Deposit is not credited to your wallet"))
        return EvmQuote(c.terms.copy(amountIn = amountIn), TxDraft(DEPOSITORY, data, value))
    }

    /**
     * From an EVM chain in a token. Passes only Relay's approval step, if any, of exactly the amount
     * to the depository; then one deposit of exactly that amount of `token`, credited to our own
     * wallet. The deposit is built again around the quoted id and must match the quote's to the byte.
     */
    fun checkTokenQuote(q: Quote, p: Ask): TokenQuote {
        val token = p.token ?: throw RelayError(tr("Quote deposits another token"))
        val c = checkTerms(q, p, approval = true)
        c.approve?.let { item ->
            val a = item.data
            if (a !is TxData.Evm || a.chainId != p.fromChainId.toDouble()) throw RelayError(tr("Quote is for a different chain"))
            if (!EVM_ADDRESS.matches(a.to) || !sameAddress(a.to, token)) throw RelayError(tr("Quote approves another token"))
            val args = runCatching { erc20Approve.decodeCall(a.data.hexBytes()) }.getOrNull() ?: throw RelayError(tr("Quote approval is not an approve"))
            if (!sameAddress(args[0] as String, DEPOSITORY) || args[1] as BigInteger != p.amount || a.value.toBigIntegerOrNull() != BigInteger.ZERO) {
                throw RelayError(tr("Quote approval is not for the deposit"))
            }
        }
        val d = c.item.data
        if (d !is TxData.Evm || d.chainId != p.fromChainId.toDouble()) throw RelayError(tr("Quote is for a different chain"))
        if (!EVM_ADDRESS.matches(d.to) || !sameAddress(d.to, DEPOSITORY)) throw RelayError(tr("Quote targets an unknown contract"))
        if (d.value.toBigIntegerOrNull() != BigInteger.ZERO) throw RelayError(tr("Quote sends native value with a token deposit"))
        val quoted = runCatching { d.data.hexBytes() }.getOrNull()
        val args = quoted?.let { runCatching { depositErc20.decodeCall(it) }.getOrNull() } ?: throw RelayError(tr("Quote calldata is not a token deposit"))
        if (!sameAddress(args[0] as String, p.user)) throw RelayError(tr("Deposit is not credited to your wallet"))
        if (!sameAddress(args[1] as String, token)) throw RelayError(tr("Quote deposits another token"))
        if (args[2] as BigInteger != p.amount) throw RelayError(tr("Quote value differs from the amount"))
        val data = depositErc20.encode(p.user, token, p.amount, args[3])
        if (!data.contentEquals(quoted)) throw RelayError(tr("Quote calldata is not a token deposit"))
        return TokenQuote(c.terms, token, TxDraft(DEPOSITORY, data, BigInteger.ZERO))
    }

    /** Bytes from hex, with or without 0x; null when it is not hex. */
    private fun hexOrNull(s: String): ByteArray? {
        val h = s.removePrefix("0x")
        if (h.length % 2 != 0 || h.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    /**
     * From Solana. Passes one transaction holding only Relay's deposit_native (compute budget aside,
     * which the app sets itself): from and credited to our wallet, into Relay's vault, for exactly the
     * amount, or with exact output for what Relay names up to the cap. The deposit is then built
     * around the quoted id, never taken from the quote.
     */
    fun checkSolanaQuote(q: Quote, p: Ask): SolQuote {
        val c = checkTerms(q, p)
        val d = c.item.data as? TxData.Sol ?: throw RelayError(tr("Quote is for a different chain"))
        val deposits = d.instructions.filter { it.programId != SolAddr.COMPUTE_BUDGET_PROGRAM }
        val ix = deposits.firstOrNull()
        if (deposits.size != 1 || ix == null) throw RelayError(tr("Quote is not a single deposit"))
        if (ix.programId != RelaySol.PROGRAM) throw RelayError(tr("Quote targets an unknown program"))
        val data = hexOrNull(ix.data)
        if (data == null || data.size != 48 || !RelaySol.DEPOSIT_NATIVE.indices.all { data[it] == RelaySol.DEPOSIT_NATIVE[it] }) {
            throw RelayError(tr("Quote instruction is not a native deposit"))
        }
        val depositId = data.copyOfRange(16, 48)
        val amount = if (p.exactOut) {
            val asked = le(data, 8, 8)
            if (asked.signum() <= 0 || (q.amountIn != null && q.amountIn != asked)) throw RelayError(tr("Quote value differs from the amount"))
            if (asked > p.cap!!) throw RelayError(tr("Quote asks for more than you confirmed"))
            asked
        } else p.amount
        val want = RelaySol.depositNative(p.user, amount, depositId)
        if (!data.contentEquals(want.data)) throw RelayError(tr("Quote value differs from the amount"))
        if (ix.keys.getOrNull(2)?.pubkey != p.user) throw RelayError(tr("Deposit is not credited to your wallet"))
        if (ix.keys != want.keys) throw RelayError(tr("Quote deposit has unexpected accounts"))
        return SolQuote(c.terms.copy(amountIn = amount), depositId)
    }

    // ── the validator's rules for the depository ───────────────────────────────────────

    /** Only depositNative crediting the sending wallet. */
    val rule = ContractRule("Bridge") { tx, ctx ->
        val args = runCatching { depositNative.decodeCall(tx.data) }.getOrNull()
            ?: return@ContractRule listOf(act(tr("Bridge call"), tr("Only native deposits are allowed")))
        val depositor = args[0] as String
        val label = tr("Bridge deposit for {1}…", depositor.take(6))
        when {
            !Validator.isOwn(ctx, depositor) || !sameAddress(depositor, ctx.from) -> listOf(act(label, tr("Deposit is not credited to the sending wallet")))
            tx.value.signum() == 0 -> listOf(act(label, tr("Empty deposit")))
            else -> listOf(act(label))
        }
    }

    /**
     * For a bridge that leaves in `token`: the depository may be approved for the token (approvals
     * stay bounded by the amount), and its only call is depositErc20 of that token, credited to the
     * sending wallet, with no native value.
     */
    fun tokenRule(token: String) = ContractRule("Bridge", approvalSpender = true) { tx, ctx ->
        val args = runCatching { depositErc20.decodeCall(tx.data) }.getOrNull()
            ?: return@ContractRule listOf(act(tr("Bridge call"), tr("Only token deposits are allowed")))
        val depositor = args[0] as String
        val label = tr("Bridge deposit for {1}…", depositor.take(6))
        when {
            !Validator.isOwn(ctx, depositor) || !sameAddress(depositor, ctx.from) -> listOf(act(label, tr("Deposit is not credited to the sending wallet")))
            !sameAddress(args[1] as String, token) -> listOf(act(label, tr("Deposit is in another token")))
            (args[2] as BigInteger).signum() == 0 -> listOf(act(label, tr("Empty deposit")))
            tx.value.signum() != 0 -> listOf(act(label, tr("A token deposit must not carry native value")))
            else -> listOf(act(label))
        }
    }

    // ── following a request ────────────────────────────────────────────────────────────

    data class Status(val status: String, val outHash: String?)

    /** Where a request stands, and its payout on `toChainId` once there is one. */
    fun readStatus(raw: JsonElement?, toChainId: Long): Status {
        val o = raw as? JsonObject
        val format = if (isSolana(toChainId)) SOL_SIGNATURE else BYTES32
        val hashes = o?.get("txHashes") as? JsonArray
        val out = hashes?.firstNotNullOfOrNull { h -> (h as? JsonPrimitive)?.takeIf { it.isString && format.matches(it.content) }?.content }
        val status = when (val s = o?.get("status")) {
            null, is JsonNull -> "unknown"
            is JsonPrimitive -> s.content
            else -> s.toString()
        }
        return Status(status, out)
    }

    /** The service's own wording is outside text: cleaned, shortened and labelled as the bridge's. */
    fun serviceError(code: Int, raw: JsonElement?): RelayError {
        val said = ((raw as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { sanitizeLabel(it, 120) }.orEmpty()
        return RelayError(if (said.isNotEmpty()) tr("Bridge service: {1}", said) else tr("Bridge service error {1}", code))
    }

    /** The body of a quote request, in the extension's order. */
    fun quoteBody(p: Ask): String = buildJsonObject {
        val fromSolana = isSolana(p.fromChainId)
        put("user", p.user)
        put("recipient", p.recipient)
        put("originChainId", p.fromChainId)
        put("destinationChainId", p.toChainId)
        put("originCurrency", p.token ?: if (fromSolana) SOLANA_NATIVE else NATIVE)
        put("destinationCurrency", if (isSolana(p.toChainId)) SOLANA_NATIVE else NATIVE)
        put("amount", p.amount.toString())
        put("tradeType", if (p.exactOut) "EXACT_OUTPUT" else "EXACT_INPUT")
        put("slippageTolerance", SLIPPAGE_BPS.toString())
    }.toString()

    /** JavaScript's Number() of an optional string, as the fee sum reads it: undefined is 0. */
    fun jsNumber(s: String?): Double {
        if (s == null) return 0.0
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        if (Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(t)) return t.toDouble()
        if (Regex("^0[xX][0-9a-fA-F]+$").matches(t)) return BigInteger(t.substring(2), 16).toDouble()
        return when (t) {
            "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> Double.NaN
        }
    }
}

/** Relay's depository program on Solana (solana/relay.ts): its state, vault and the one deposit. */
object RelaySol {
    const val PROGRAM = "99vQwtBwYtrqqD9YSXbdum3KBdxPAVxYTaQ3cfnJSrN2"
    val STATE: String by lazy { SolAddr.findProgramAddress(listOf(SolAddr.seed("relay_depository")), PROGRAM) }
    val VAULT: String by lazy { SolAddr.findProgramAddress(listOf(SolAddr.seed("vault")), PROGRAM) }

    /** Anchor discriminator of deposit_native: sha256("global:deposit_native"), first 8 bytes. */
    val DEPOSIT_NATIVE: ByteArray = bytesOf(0x0d, 0x9e, 0x0d, 0xdf, 0x5f, 0xd5, 0x1c, 0x06)

    /** Compute a deposit asks for before simulating (13,551 used on mainnet in October 2026). */
    const val DEPOSIT_UNITS = 20_000

    /** deposit_native(amount, id): `amount` lamports from `wallet` into the vault, credited to it. */
    fun depositNative(wallet: String, amount: BigInteger, id: ByteArray): Instruction {
        require(id.size == 32) { "bad deposit id" }
        return Instruction(
            PROGRAM,
            listOf(
                meta(STATE),
                meta(wallet, isWritable = true, isSigner = true),
                // The depositor: whom Relay credits the deposit to.
                meta(wallet),
                meta(VAULT, isWritable = true),
                meta(SolAddr.SYSTEM_PROGRAM),
            ),
            concat(DEPOSIT_NATIVE, u64(amount), id),
        )
    }
}

/** Relay's API, asked from the phone: quotes and the status of a request. */
interface RelayClient {
    /** From an EVM chain in its own coin, to another or to Solana. */
    suspend fun quote(p: Relay.Ask): Relay.EvmQuote

    /** From an EVM chain in `p.token`, its bridge asset. */
    suspend fun quoteToken(p: Relay.Ask): Relay.TokenQuote

    /** From Solana, to an EVM chain. */
    suspend fun quoteFromSolana(p: Relay.Ask): Relay.SolQuote

    suspend fun status(statusPath: String, toChainId: Long): Relay.Status
}

/** The client over HTTP: no cookies, no referrer, ten seconds a call, as the extension asks. */
class HttpRelay(http: OkHttpClient, private val base: String = Relay.API) : RelayClient {
    private val client = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private suspend fun call(path: String, body: String?): JsonElement? {
        val req = Request.Builder().url(base + path).apply { if (body != null) post(body.toRequestBody(json)) else get() }.build()
        val response = client.newCall(req).await()
        val (code, ok, text) = response.use { r -> Triple(r.code, r.isSuccessful, withContext(Dispatchers.IO) { r.body.string() }) }
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (!ok) throw Relay.serviceError(code, parsed)
        return parsed
    }

    private suspend fun quoteOf(p: Relay.Ask): Relay.Quote {
        val raw = try {
            call("/quote", Relay.quoteBody(p))
        } catch (e: IOException) {
            throw Relay.RelayError(tr("Bridge service unreachable"))
        } ?: throw Relay.RelayError(tr("Unexpected quote format"))
        return Relay.parse(raw)
    }

    override suspend fun quote(p: Relay.Ask) = Relay.checkQuote(quoteOf(p), p)

    override suspend fun quoteToken(p: Relay.Ask) = Relay.checkTokenQuote(quoteOf(p), p)

    override suspend fun quoteFromSolana(p: Relay.Ask) = Relay.checkSolanaQuote(quoteOf(p), p)

    override suspend fun status(statusPath: String, toChainId: Long): Relay.Status {
        if (!Relay.isStatusPath(statusPath)) throw Relay.RelayError(tr("Unexpected status path"))
        return Relay.readStatus(call(statusPath, null), toChainId)
    }
}
