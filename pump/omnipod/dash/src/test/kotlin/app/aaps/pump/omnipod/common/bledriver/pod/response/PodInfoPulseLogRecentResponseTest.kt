package app.aaps.pump.omnipod.common.bledriver.pod.response

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodInfoPulseLogRecentResponseTest {

    @Test fun testValidResponse() {
        val bytes = byteArrayOf(
            0x02, 0x07, 0x50,
            0x03, 0xE8.toByte(), // indexLastEntry = 1000
            0x12, 0x34, 0x56, 0x78 // entry 0 = 0x12345678
        )
        val response = PodInfoPulseLogRecentResponse(bytes)

        assertThat(response.responseType).isEqualTo(ResponseType.ADDITIONAL_STATUS_RESPONSE)
        assertThat(response.statusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_80)
        assertThat(response.messageLength).isEqualTo(7.toShort())
        assertThat(response.indexLastEntry).isEqualTo(1000)
        assertThat(response.pulseLog).containsExactly(0x12345678)
    }
}
