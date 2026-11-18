package trade.nesty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import trade.nesty.app.data.KeyMove
import trade.nesty.app.data.KeysAnswer
import trade.nesty.app.data.NestyJson
import trade.nesty.app.data.Trade
import trade.nesty.app.ui.holders.KeyList
import trade.nesty.app.ui.holders.KeyShow

/** The token page's Key wallets list (website/api/README.md, Key wallets). Made-up addresses throughout. */
class KeyWalletsTest {
    private val dev = "0x00000000000000000000000000000000000000d1"
    private val insider = "0x00000000000000000000000000000000000000a2"
    private val top = "0x00000000000000000000000000000000000000b3"
    private val buyer = "0x00000000000000000000000000000000000000c4"

    /** The shape the server answers, cut down. */
    private val answer = NestyJson.decodeFromString<KeysAnswer>(
        """
        {"feed":"56","address":"0x7AbA","quote":{"symbol":"BNB","usd":600,"address":"0xbb"},
         "supply":1000000000,"price":0.000001,"reading":false,"partial":true,
         "wallets":[
           {"wallet":"$dev","role":"dev","rank":null,"tags":["creator"],"held":0},
           {"wallet":"$insider","role":"insider","rank":null,"tags":[],"held":null},
           {"wallet":"$top","role":"top","rank":3,"tags":[],"held":25000000},
           {"wallet":"$buyer","role":"big","rank":null,"tags":[],"held":15000000}],
         "moves":[
           {"id":"t5:1","tx":"t5","at":5000,"verb":"sold","wallet":"$dev","role":"dev","tokens":20000000,"quote":0.5,"other":null,"big":false,"mcapUsd":15000},
           {"id":"t4:3","tx":"t4","at":4000,"verb":"sent","wallet":"$dev","role":"dev","tokens":10000000,"quote":null,"other":"$insider","big":false,"mcapUsd":null},
           {"id":"t3:7","tx":"t3","at":3000,"verb":"bought","wallet":"$buyer","role":"big","tokens":15000000,"quote":0.25,"other":null,"big":true,"mcapUsd":10000},
           {"id":"t2:2","tx":"t2","at":2000,"verb":"bought","wallet":"$top","role":"top","tokens":25000000,"quote":0.3,"other":null,"big":true,"mcapUsd":7200},
           {"id":"t1:9","tx":"t1","at":1000,"verb":"burned","wallet":"$dev","role":"dev","tokens":1000,"quote":null,"other":null,"big":false,"mcapUsd":null},
           {"id":"t0:4","tx":"t0","at":500,"verb":"bought","wallet":"$dev","role":"dev","tokens":40000000,"quote":0.1,"other":null,"big":true,"mcapUsd":1500}]}
        """.trimIndent(),
    )
    private val ranks = mapOf(top to 3)
    private val roles = answer.wallets.associate { it.wallet to it.role }
    private val held = answer.wallets.mapNotNull { w -> w.held?.let { w.wallet to it } }.toMap()

    @Test
    fun eachChoiceShowsItsMoves() {
        assertEquals(6, KeyList.shown(answer.moves, KeyShow.ALL, ranks).size)
        // The creator's side: its own moves; the insider made none.
        assertEquals(listOf("t5", "t4", "t1", "t0"), KeyList.shown(answer.moves, KeyShow.SIDE, ranks).map { it.tx })
        assertEquals(listOf("t2"), KeyList.shown(answer.moves, KeyShow.TOP, ranks).map { it.tx })
        assertEquals(listOf("t3", "t2", "t0"), KeyList.shown(answer.moves, KeyShow.BIG, ranks).map { it.tx })
    }

    @Test
    fun partsAndMovesAreNamedAsOnTheSite() {
        assertEquals("Creator", KeyList.roleWord("dev"))
        assertEquals("Team", KeyList.roleWord("handoff"))
        assertEquals("Top 10 #3", KeyList.roleLabel("top", 3))
        assertEquals("Top 10", KeyList.roleLabel("top", null))
        assertEquals("Big buy", KeyList.roleLabel("big", null))
        assertEquals("Burned", KeyList.verbWord("burned"))
    }

    @Test
    fun aMoveSaysWhoASendWentToAndWhatTheWalletHoldsNow() {
        val newest = KeyList.newestTrades(answer.moves)
        val line = { m: KeyMove, names: Map<String, String> ->
            KeyList.detail(m, answer, KeyList.key(m) in newest, roles, held) { names[it] }
        }
        val (sold, sent, big, topBuy, burned) = answer.moves
        val firstBuy = answer.moves[5]
        // The creator's newest move is its sell: it holds none now.
        assertEquals("at MC $15.0K · holds none now", line(sold, emptyMap()))
        assertEquals("To 0x00…00a2, insider", line(sent, emptyMap()))
        // A followed wallet shows by its name.
        assertEquals("To Pal, insider", line(sent, mapOf(insider to "Pal")))
        assertEquals("at MC $10.0K · holds 1.5% now", line(big, emptyMap()))
        assertEquals("at MC $7.2K · holds 2.5% now", line(topBuy, emptyMap()))
        assertEquals("To the burn address", line(burned, emptyMap()))
        // An older trade of a wallet tells nothing of what it holds now.
        assertEquals("at MC $1.5K", line(firstBuy, emptyMap()))
        val received = sent.copy(verb = "received", wallet = insider, role = "insider", other = dev)
        assertEquals("From 0x00…00d1, the creator", line(received, emptyMap()))
    }

    @Test
    fun aMoveIsWorthWhatItPaidOrWhatItsTokensAreWorthNow() {
        val (sold, sent) = answer.moves
        assertEquals(300.0, KeyList.worthUsd(sold, answer)!!, 1e-9)
        // 10M tokens at 0.000001 of the quote, at $600 the quote.
        assertEquals(6_000.0, KeyList.worthUsd(sent, answer)!!, 1e-9)
        assertNull(KeyList.worthUsd(sold, answer.copy(quote = null)))
        assertNull(KeyList.worthUsd(sent, answer.copy(price = null)))
    }

    @Test
    fun smallSharesOfTheSupplyStayReadable() {
        assertEquals("34%", KeyList.ofSupply(0.34))
        assertEquals("6.2%", KeyList.ofSupply(0.062))
        assertEquals("0.47%", KeyList.ofSupply(0.0047))
        assertEquals("0.2%", KeyList.ofSupply(0.00198))
        assertEquals("<0.01%", KeyList.ofSupply(0.00003))
        assertEquals("0%", KeyList.ofSupply(0.0))
        assertEquals("holds none now", KeyList.heldNote(50.0, 1e9))
        assertNull(KeyList.heldNote(null, 1e9))
        assertNull(KeyList.heldNote(5.0, null))
    }

    @Test
    fun anEmptyListSaysWhy() {
        assertEquals("None of these yet.", KeyList.emptyNote(answer, KeyShow.TOP))
        val none = KeysAnswer()
        assertEquals("Reading what the key wallets did…", KeyList.emptyNote(none.copy(reading = true), KeyShow.ALL))
        assertEquals("No creator, launch buyer or largest holder is known for this coin yet.", KeyList.emptyNote(none, KeyShow.ALL))
        assertTrue(KeyList.emptyNote(none.copy(wallets = answer.wallets), KeyShow.ALL).startsWith("No moves"))
    }

    @Test
    fun solanasListComesFromTheTradesTagsAndBigBuys() {
        val trades = listOf(
            Trade(id = "a", tx = "sa", at = 30, side = "sell", tokens = 5e6, quote = 1.0, mcapUsd = 9_000.0, wallet = "Dev1", tags = listOf("creator")),
            Trade(id = "b", tx = "sb", at = 20, side = "buy", tokens = 2e7, quote = 2.0, wallet = "Whale", tags = emptyList()),
            Trade(id = "c", tx = "sc", at = 10, side = "buy", tokens = 1e6, wallet = "Small", tags = emptyList()),
            Trade(id = "d", tx = "sd", at = 40, side = "buy", tokens = 3e6, wallet = "Snipe", tags = listOf("sniper", "cluster")),
            Trade(id = "e", tx = "se", at = 50, side = "buy", tokens = 9e7, wallet = null),
        )
        val moves = KeyList.fromTrades(trades, 1e9)
        assertEquals(listOf("d", "a", "b"), moves.map { it.id })
        assertEquals(listOf("sniper", "dev", "big"), moves.map { it.role })
        assertEquals(listOf("bought", "sold", "bought"), moves.map { it.verb })
        assertEquals(listOf(false, false, true), moves.map { it.big })
        // Without a supply no buy counts as big.
        assertEquals(listOf("d", "a"), KeyList.fromTrades(trades, null).map { it.id })
    }
}
