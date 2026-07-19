package app.aaps.pump.omnipod.common.bledriver.pod.response

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodInfoPulseLogPreviousResponseTest {

    @Test fun testValidResponse() {
        val bytes = byteArrayOf(
            0x02, 0x0B, 0x51,
            0x00, 0x02, // nEntries = 2
            0x00, 0x00, 0x00, 0x00, // entry 0 = 0
            0x00, 0x00, 0x00, 0x64 // entry 1 = 100
        )
        val response = PodInfoPulseLogPreviousResponse(bytes)

        assertThat(response.responseType).isEqualTo(ResponseType.ADDITIONAL_STATUS_RESPONSE)
        assertThat(response.statusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_81)
        assertThat(response.messageLength).isEqualTo(11.toShort())
        assertThat(response.nEntries).isEqualTo(2)
        assertThat(response.pulseLog).containsExactly(0, 100).inOrder()
    }
}
