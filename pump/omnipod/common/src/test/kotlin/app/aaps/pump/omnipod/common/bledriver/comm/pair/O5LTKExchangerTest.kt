package app.aaps.pump.omnipod.common.bledriver.comm.pair

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [O5LTKExchanger]'s two CRC-16 companion functions - the only public, independently
 * testable surface of this class without mocking [app.aaps.pump.omnipod.common.bledriver
 * .comm.message.MessageIO] to drive the full pairing handshake (`o5NegotiateLTK`), which
 * this pass doesn't attempt.
 */
class O5LTKExchangerTest {

    @Test
    fun `crc16XMODEM matches the standard CRC-16-XMODEM check value`() {
        // The published check value for CRC-16/XMODEM (poly 0x1021, init 0x0000) over the
        // ASCII string "123456789" is 0x31C3 - a standard cross-implementation test vector,
        // not something specific to this codebase.
        assertThat(O5LTKExchanger.crc16XMODEM("123456789".toByteArray())).isEqualTo(0x31C3)
    }

    @Test
    fun `crc16XMODEM of empty input is zero`() {
        assertThat(O5LTKExchanger.crc16XMODEM(ByteArray(0))).isEqualTo(0)
    }

    @Test
    fun `classicCrc16 is deterministic and sensitive to every input byte`() {
        val a = O5LTKExchanger.classicCrc16(byteArrayOf(0x01, 0x02, 0x03))
        val aAgain = O5LTKExchanger.classicCrc16(byteArrayOf(0x01, 0x02, 0x03))
        val b = O5LTKExchanger.classicCrc16(byteArrayOf(0x01, 0x02, 0x04))

        assertThat(a).isEqualTo(aAgain)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `classicCrc16 and crc16XMODEM are different algorithms, not accidentally identical`() {
        // O5LTKExchanger uses these two for different purposes (o5Sp2 vs o5Sps0) and its own
        // doc comment warns not to confuse them - this is a regression guard against that.
        val data = "regression guard".toByteArray()

        assertThat(O5LTKExchanger.classicCrc16(data)).isNotEqualTo(O5LTKExchanger.crc16XMODEM(data))
    }

    @Test
    fun `classicCrc16 of empty input is zero`() {
        assertThat(O5LTKExchanger.classicCrc16(ByteArray(0))).isEqualTo(0)
    }
}
