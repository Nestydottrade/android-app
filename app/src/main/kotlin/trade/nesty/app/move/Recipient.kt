package trade.nesty.app.move

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import trade.nesty.app.data.Chain
import trade.nesty.app.evm.ChainReader
import trade.nesty.app.evm.Erc20
import trade.nesty.app.evm.sanitizeLabel
import trade.nesty.app.keys.Evm
import trade.nesty.app.sol.SolAddr

/** An address typed or pasted for a withdrawal, as its chain writes it. */
sealed interface Typed {
    val address: String

    /** For the EVM chains: in EIP-55's mixed case. */
    data class EvmAddress(override val address: String) : Typed

    data class SolAddress(override val address: String) : Typed

    /** Neither, and why. */
    data class Bad(val reason: String) : Typed {
        override val address: String get() = ""
    }

    /** Whether funds can arrive on `chain` at this address: an address only works on its own kind of chain. */
    fun fits(chain: Chain): Boolean = when (this) {
        is EvmAddress -> chain.isEvm
        is SolAddress -> chain == Chain.SOLANA
        is Bad -> false
    }
}

object Recipient {
    private val EVM = Regex("^0x[0-9a-fA-F]{40}$")

    const val NOT_AN_ADDRESS = "This is not an EVM or Solana address"
    const val BAD_CHECKSUM = "This EVM address has a typo in it: check every character"

    /**
     * What was typed, read as the extension reads a typed recipient: a mixed-case EVM address must
     * carry a valid checksum (a typo in it is caught here); a Solana one is 32 bytes of Base58.
     * Null for nothing typed.
     */
    fun parse(input: String): Typed? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (EVM.matches(s)) {
            val sum = Evm.checksum(s)
            if (s != s.lowercase() && s != sum) return Typed.Bad(BAD_CHECKSUM)
            return Typed.EvmAddress(sum)
        }
        if (SolAddr.isAddress(s)) return Typed.SolAddress(s)
        return Typed.Bad(NOT_AN_ADDRESS)
    }

    /** What an EVM address holds on its chain: no code (a wallet), a contract, or a token contract. */
    data class EvmKind(val contract: Boolean, val tokenSymbol: String?)

    /**
     * The extension's contractAt: an EIP-7702 delegation (0xef0100 and an address) is still a
     * key-held account. A token contract answers symbol(), and sending to it almost always loses
     * the funds. Read on the chain's own RPCs (`reader`), never through Nesty's server.
     */
    suspend fun evmKind(reader: ChainReader, address: String): EvmKind {
        val code = runCatching {
            (reader.request("eth_getCode", buildJsonArray { add(address); add("latest") }) as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        val contract = code != null && code != "0x" && !code.lowercase().startsWith("0xef0100")
        val symbol = if (contract) {
            runCatching { reader.read1(address, Erc20.symbol) as? String }.getOrNull()?.let { sanitizeLabel(it, 16).ifEmpty { "token" } }
        } else null
        return EvmKind(contract, symbol)
    }

    fun tokenContract(symbol: String) = "This is the $symbol token contract: funds sent there are lost."
}
