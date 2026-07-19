package app.aaps.pump.omnipod.common.bledriver.pod.response

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodInfoPulseLogPlusResponseTest {

    @Test fun testValidResponse() {
        val bytes = byteArrayOf(
            0x02, 0x10, 0x03,
            0x00, // faultEventCode = NONE
            0x00, 0x00, // faultTime = 0
            0x01, 0xF4.toByte(), // activationTime = 500
            0x04, // entrySize = 4
            0x3C, // maxEntries = 60
            0x00, 0x00, 0x00, 0x01, // entry 0 = 1
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte() // entry 1 = -1
        )
        val response = PodInfoPulseLogPlusResponse(bytes)

        assertThat(response.responseType).isEqualTo(ResponseType.ADDITIONAL_STATUS_RESPONSE)
        assertThat(response.statusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_3)
        assertThat(response.messageLength).isEqualTo(16.toShort())
        assertThat(response.faultEventCode).isEqualTo(AlarmType.NONE)
        assertThat(response.faultTime).isEqualTo(0.toShort())
        assertThat(response.activationTime).isEqualTo(500.toShort())
        assertThat(response.entrySize).isEqualTo(4)
        assertThat(response.maxEntries).isEqualTo(60)
        assertThat(response.pulseLog).containsExactly(1, -1).inOrder()
    }
}
