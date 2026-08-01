package app.aaps.pump.omnipod.common.bledriver.comm.session

import app.aaps.pump.omnipod.common.bledriver.comm.Id
import app.aaps.pump.omnipod.common.bledriver.comm.Ids
import app.aaps.pump.omnipod.common.bledriver.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.common.bledriver.comm.endecrypt.Nonce
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.CouldNotParseResponseException
import app.aaps.pump.omnipod.common.bledriver.comm.message.CrcMismatchException
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageIO
import app.aaps.pump.omnipod.common.bledriver.pod.util.MessageUtil
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.nio.ByteBuffer

/**
 * [Session.validateCrc]/[Session.validateSequenceNumber] - the O5-only response-envelope
 * checks (see [Session.parseResponse]'s doc comment for why they're O5-only, not Dash).
 * Exercised directly against hand-crafted bytes rather than through a full encrypted round
 * trip - these two functions are pure w.r.t. their explicit parameters (no session/crypto
 * state involved), so a real [Session] instance here is just a vehicle to call them on.
 */
class SessionResponseValidationTest {

    private fun session(): Session {
        val ids = Ids.forController(Id.fromInt(1), Id.fromInt(2))
        val nonce = Nonce(ByteArray(8), 0)
        val sessionKeys = SessionKeys(ck = ByteArray(16), nonce = nonce, msgSequenceNumber = 1)
        val enDecrypt = EnDecrypt(AAPSLoggerTest(), nonce, sessionKeys.ck)
        return Session(AAPSLoggerTest(), mock<MessageIO>(), ids, sessionKeys, enDecrypt)
    }

    // -- validateCrc -------------------------------------------------------------------------

    @Test
    fun `validateCrc accepts a correctly-computed trailing CRC`() {
        val body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val crc = MessageUtil.createCrc(body)
        val data = ByteBuffer.allocate(body.size + 2).put(body).putShort(crc).array()

        session().validateCrc(data) // must not throw
    }

    @Test
    fun `validateCrc rejects a wrong trailing CRC`() {
        val body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val wrongCrc = (MessageUtil.createCrc(body) + 1).toShort()
        val data = ByteBuffer.allocate(body.size + 2).put(body).putShort(wrongCrc).array()

        assertThrows(CrcMismatchException::class.java) { session().validateCrc(data) }
    }

    // -- validateSequenceNumber ---------------------------------------------------------------
    // Bit layout cross-checked against HeaderEnabledCommand.encodeHeader() - the same,
    // already real-hardware-proven encoder used for every outgoing command - rather than
    // just the reverse-engineered Swift description, so a real symmetry bug would show up
    // as a failing round trip below, not just a plausible-looking assertion.

    private fun packedLengthAndSequenceNumber(sequenceNumber: Short, length: Short, multiCommandFlag: Boolean): ByteArray {
        // Mirrors HeaderEnabledCommand.encodeHeader()'s second Short exactly, minus the
        // leading 4-byte uniqueId (not relevant to sequence-number extraction).
        val packed = (sequenceNumber.toInt() and 0x0f shl 10 or length.toInt() or ((if (multiCommandFlag) 1 else 0) shl 15)).toShort()
        return ByteBuffer.allocate(2).putShort(packed).array()
    }

    @Test
    fun `validateSequenceNumber accepts a response echoing the expected sequence number`() {
        val bytes = packedLengthAndSequenceNumber(sequenceNumber = 7, length = 42, multiCommandFlag = false)

        session().validateSequenceNumber(bytes, expected = 7)
    }

    @Test
    fun `validateSequenceNumber ignores the length and multiCommandFlag bits it shares the byte with`() {
        val bytes = packedLengthAndSequenceNumber(sequenceNumber = 3, length = 1000, multiCommandFlag = true)

        session().validateSequenceNumber(bytes, expected = 3) // must not throw
    }

    @Test
    fun `validateSequenceNumber rejects a mismatched sequence number`() {
        val bytes = packedLengthAndSequenceNumber(sequenceNumber = 5, length = 0, multiCommandFlag = false)

        assertThrows(CouldNotParseResponseException::class.java) { session().validateSequenceNumber(bytes, expected = 6) }
    }

    @Test
    fun `validateSequenceNumber only compares the low 4 bits of expected - matching the 4-bit wire field`() {
        val bytes = packedLengthAndSequenceNumber(sequenceNumber = 2, length = 0, multiCommandFlag = false)

        // expected=18 (0x12) has the same low 4 bits as sequenceNumber=2 (0x02) - must still pass,
        // since podStateManager.msgSequenceNumber is already guaranteed 4-bit-wrapped in practice
        // (see O5PodStateManager.increaseMessageSequenceNumber's doc comment) and this function
        // must mask defensively, not assume that invariant holds.
        session().validateSequenceNumber(bytes, expected = 18)
        assertThat(0x12 and 0x0f).isEqualTo(2)
    }
}
