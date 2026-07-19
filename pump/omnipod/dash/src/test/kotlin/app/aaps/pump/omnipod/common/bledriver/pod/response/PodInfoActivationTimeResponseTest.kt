package app.aaps.pump.omnipod.common.bledriver.pod.response

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodInfoActivationTimeResponseTest {

    @Test fun testValidResponse() {
        val bytes = byteArrayOf(
            0x02, 0x11, 0x05,
            0x14, // faultEventCode = ALARM_OCCLUDED
            0x00, 0x7D, // faultTime = 125
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 8 reserved bytes
            0x07, // month = 7
            0x12, // day = 18
            0x1A, // year = 26
            0x09, // hour = 9
            0x20  // minute = 32
        )
        val response = PodInfoActivationTimeResponse(bytes)

        assertThat(response.responseType).isEqualTo(ResponseType.ADDITIONAL_STATUS_RESPONSE)
        assertThat(response.statusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_5)
        assertThat(response.messageLength).isEqualTo(17.toShort())
        assertThat(response.additionalStatusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_5.value)
        assertThat(response.faultEventCode).isEqualTo(AlarmType.ALARM_OCCLUDED)
        assertThat(response.faultTime).isEqualTo(125.toShort())
        assertThat(response.month).isEqualTo(7)
        assertThat(response.day).isEqualTo(18)
        assertThat(response.year).isEqualTo(26)
        assertThat(response.hour).isEqualTo(9)
        assertThat(response.minute).isEqualTo(32)
    }
}
