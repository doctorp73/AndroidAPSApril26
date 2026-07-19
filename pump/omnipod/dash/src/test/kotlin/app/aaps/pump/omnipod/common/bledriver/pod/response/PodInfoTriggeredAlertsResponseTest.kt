package app.aaps.pump.omnipod.common.bledriver.pod.response

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PodInfoTriggeredAlertsResponseTest {

    @Test fun testValidResponse() {
        // 02 13 01 ABCD 0000 000A 0000 0000 0078 0000 0000 012C
        val bytes = byteArrayOf(
            0x02, 0x13, 0x01,
            0xAB.toByte(), 0xCD.toByte(), // unknownWord = 0xABCD
            0x00, 0x00, // AUTO_OFF = 0
            0x00, 0x0A, // MULTI_COMMAND = 10
            0x00, 0x00, // EXPIRATION_IMMINENT = 0
            0x00, 0x00, // USER_SET_EXPIRATION = 0
            0x00, 0x78, // LOW_RESERVOIR = 120
            0x00, 0x00, // SUSPEND_IN_PROGRESS = 0
            0x00, 0x00, // SUSPEND_ENDED = 0
            0x01, 0x2C  // EXPIRATION = 300
        )
        val response = PodInfoTriggeredAlertsResponse(bytes)

        assertThat(response.responseType).isEqualTo(ResponseType.ADDITIONAL_STATUS_RESPONSE)
        assertThat(response.statusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_1)
        assertThat(response.messageType).isEqualTo(ResponseType.ADDITIONAL_STATUS_RESPONSE.value)
        assertThat(response.messageLength).isEqualTo(19.toShort())
        assertThat(response.additionalStatusResponseType).isEqualTo(ResponseType.StatusResponseType.STATUS_RESPONSE_PAGE_1.value)
        assertThat(response.unknownWord).isEqualTo(0xABCD.toShort())
        assertThat(response.alertActivations[AlertType.AUTO_OFF]).isEqualTo(0.toShort())
        assertThat(response.alertActivations[AlertType.MULTI_COMMAND]).isEqualTo(10.toShort())
        assertThat(response.alertActivations[AlertType.EXPIRATION_IMMINENT]).isEqualTo(0.toShort())
        assertThat(response.alertActivations[AlertType.USER_SET_EXPIRATION]).isEqualTo(0.toShort())
        assertThat(response.alertActivations[AlertType.LOW_RESERVOIR]).isEqualTo(120.toShort())
        assertThat(response.alertActivations[AlertType.SUSPEND_IN_PROGRESS]).isEqualTo(0.toShort())
        assertThat(response.alertActivations[AlertType.SUSPEND_ENDED]).isEqualTo(0.toShort())
        assertThat(response.alertActivations[AlertType.EXPIRATION]).isEqualTo(300.toShort())
        assertThat(response.alertActivations).hasSize(8)
    }
}
