package trade.nesty.app.evm

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import trade.nesty.app.evm.EvmTestKit.assertResult
import trade.nesty.app.evm.EvmTestKit.tx
import trade.nesty.app.evm.EvmTestKit.vctx

/**
 * The check before every signature, against the TypeScript validator's own verdicts: each
 * build as built and tampered (another recipient, another coin, no minimum, more value, a
 * stranger signing, a far or gone deadline, an unknown function), approvals, token calls,
 * hand-made Universal Router commands and Permit2 permits. Refusals must match as well as
 * passes, reason for reason.
 */
class VerdictsTest {
    private val v = EvmVectors.load("verdicts")

    @Test
    fun validatorAgreesWithTheExtension() {
        val verdicts = v.arr("verdicts")
        assertEquals(1040, verdicts.size)
        var refused = 0
        for (e in verdicts) {
            val o = e.jsonObject
            val result = o.obj("result")
            if (!result.getValue("ok").bool()) refused++
            assertResult(o.s("name"), result, Validator.validateTx(tx(o.obj("tx")), vctx(o.obj("ctx"))))
        }
        // Both kinds are in the record.
        assert(refused in 1 until verdicts.size)
    }

    @Test
    fun permitsAgreeWithTheExtension() {
        for (e in v.arr("permits")) {
            val o = e.jsonObject
            val permit = EvmTestKit.permitSingle(o.obj("permit"))
            assertResult(o.s("name"), o.obj("result"), Validator.validatePermit(permit, vctx(o.obj("ctx"))))
        }
    }
}
