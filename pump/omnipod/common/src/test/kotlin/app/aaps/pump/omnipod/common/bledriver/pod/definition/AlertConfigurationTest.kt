package app.aaps.pump.omnipod.common.bledriver.pod.definition

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [AlertConfiguration]'s 6-byte bit-packed encoding, verified by hand against the field
 * layout in [AlertConfiguration.encoded] - see this file's two test cases for the full
 * worked byte-by-byte derivation (one per [AlertTrigger] variant).
 */
class AlertConfigurationTest {

    @Test
    fun `encodes a TimerTrigger alert with all flag bits set`() {
        // type=LOW_RESERVOIR(4): 4<<4=0x40; enabled: |0x08; TimerTrigger (not reservoir):
        // no |0x04; autoOff: |0x02; duration=300=0b1_00101100, high bit=1: |0x01 -> 0x4B.
        // durationInMinutes low byte: 300 & 0xFF = 0x2C. trigger.offsetInMinutes=500=0x01F4.
        // beepRepetition=XXX(0x01), beepType=FOUR_TIMES_BIP_BEEP(0x02).
        val config = AlertConfiguration(
            type = AlertType.LOW_RESERVOIR,
            enabled = true,
            durationInMinutes = 300,
            autoOff = true,
            trigger = AlertTrigger.TimerTrigger(offsetInMinutes = 500),
            beepType = BeepType.FOUR_TIMES_BIP_BEEP,
            beepRepetition = BeepRepetitionType.XXX
        )

        assertThat(config.encoded).isEqualTo(
            byteArrayOf(0x4B, 0x2C, 0x01, 0xF4.toByte(), 0x01, 0x02)
        )
    }

    @Test
    fun `encodes a ReservoirVolumeTrigger alert with all flag bits clear`() {
        // type=EXPIRATION(7): 7<<4=0x70; disabled: no |0x08; ReservoirVolumeTrigger:
        // |0x04 -> 0x74; autoOff=false: no |0x02; duration=0: no |0x01.
        // trigger.thresholdInMicroLiters=1000=0x03E8.
        // beepRepetition=EVERY_MINUTE_AND_EVERY_15_MIN(0x03), beepType=SILENT(0x00).
        val config = AlertConfiguration(
            type = AlertType.EXPIRATION,
            enabled = false,
            durationInMinutes = 0,
            autoOff = false,
            trigger = AlertTrigger.ReservoirVolumeTrigger(thresholdInMicroLiters = 1000),
            beepType = BeepType.SILENT,
            beepRepetition = BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
        )

        assertThat(config.encoded).isEqualTo(
            byteArrayOf(0x74, 0x00, 0x03, 0xE8.toByte(), 0x03, 0x00)
        )
    }

    @Test
    fun `the reservoir-trigger flag bit distinguishes trigger type independent of other flags`() {
        val timerBased = AlertConfiguration(
            type = AlertType.AUTO_OFF,
            enabled = false,
            durationInMinutes = 0,
            autoOff = false,
            trigger = AlertTrigger.TimerTrigger(offsetInMinutes = 0),
            beepType = BeepType.SILENT,
            beepRepetition = BeepRepetitionType.XXX
        )
        val reservoirBased = AlertConfiguration(
            type = AlertType.AUTO_OFF,
            enabled = false,
            durationInMinutes = 0,
            autoOff = false,
            trigger = AlertTrigger.ReservoirVolumeTrigger(thresholdInMicroLiters = 0),
            beepType = BeepType.SILENT,
            beepRepetition = BeepRepetitionType.XXX
        )

        assertThat(timerBased.encoded[0]).isEqualTo(0x00.toByte())
        assertThat(reservoirBased.encoded[0]).isEqualTo(0x04.toByte())
    }
}
