package app.aaps.pump.medtronic.comm.message

import app.aaps.pump.medtronic.MedtronicTestBase
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PumpMessageTest : MedtronicTestBase() {

    @Test
    fun `isValid returns false for an unrecognized commandType byte`() {
        // 0xa7 = PacketType.Carelink, 000000 = address, 0xFF = a command byte that doesn't map to any
        // MedtronicCommandType, so getByCode() resolves it to the real, non-null InvalidCommand -
        // already logged as an error in init(), but not previously rejected by isValid() itself.
        val rxData = byteArrayOf(0xa7.toByte(), 0, 0, 0, 0xFF.toByte())

        val message = PumpMessage(aapsLogger, rxData)

        assertThat(message.commandType).isEqualTo(MedtronicCommandType.InvalidCommand)
        assertThat(message.isValid()).isFalse()
    }

    @Test
    fun `isValid returns true for a recognized commandType`() {
        // 0x06 = CommandACK's code
        val rxData = byteArrayOf(0xa7.toByte(), 0, 0, 0, 0x06.toByte())

        val message = PumpMessage(aapsLogger, rxData)

        assertThat(message.commandType).isEqualTo(MedtronicCommandType.CommandACK)
        assertThat(message.isValid()).isTrue()
    }
}
