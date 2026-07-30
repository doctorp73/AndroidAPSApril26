package app.aaps.plugins.eversense.packets.e365

import app.aaps.plugins.eversense.enums.EversenseAlarm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PushAlarmWithDataPacketTest {

    private fun makePacket(vararg bytes: Int): PushAlarmWithDataPacket {
        val packet = PushAlarmWithDataPacket()
        packet.appendData(bytes.map { it.toUByte() }.toUByteArray())
        return packet
    }

    @Test
    fun `data shorter than 12 bytes returns null`() {
        val packet = makePacket(0x44, 0x03, 0x00, 14)
        assertNull(packet.parseResponse())
    }

    @Test
    fun `alarm code comes from byte index 3, not the reserved byte at index 2`() {
        // [0]=0x44 header, [1]=0x03 type, [2]=reserved (nonzero on purpose - must NOT be read as
        // the code, this is exactly the byte EversenseGattCallback's inline parsing used to read
        // instead of [3]), [3]=14 (LOW_GLUCOSE), [4..11]=datetime.
        val packet = makePacket(0x44, 0x03, 0x7F, 14, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(EversenseAlarm.LOW_GLUCOSE, packet.parseResponse()?.alarm?.code)
    }

    @Test
    fun `unrecognized alarm code falls back to UNKNOWN`() {
        val packet = makePacket(0x44, 0x03, 0x00, 254, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(EversenseAlarm.UNKNOWN, packet.parseResponse()?.alarm?.code)
    }
}
