package app.aaps.pump.omnipod.common.bledriver.pod.command

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.response.ResponseType
import app.aaps.pump.omnipod.common.bledriver.pod.util.AlertUtil
import app.aaps.pump.omnipod.common.bledriver.pod.util.MessageUtil
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.EnumSet

/**
 * Byte-level encoding checks for a handful of read-only / non-dosing commands, each
 * verified against the real, production [MessageUtil.createCrc] rather than a
 * re-implementation of it. These commands ([GetVersionCommand], [GetStatusCommand],
 * [SilenceAlertsCommand]) are built on [app.aaps.pump.omnipod.common.bledriver.pod.command
 * .base.HeaderEnabledCommand] / [app.aaps.pump.omnipod.common.bledriver.pod.command.base
 * .NonceEnabledCommand], which have no pod-type-specific assumptions - these tests exist
 * to pin that down with concrete byte checks, not just an architectural argument.
 *
 * Deliberately excludes anything with dosing-affecting logic (bolus, basal, temp basal) -
 * that's out of scope for this test's purpose.
 */
class CommandEncodingTest : TestBase() {

    // -- GetVersionCommand ---------------------------------------------------------------------

    @Test
    fun `GetVersionCommand encodes to the documented 14-byte layout`() {
        val uniqueId = 0x12345678
        val sequenceNumber: Short = 5
        val cmd = GetVersionCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .build()

        val encoded = cmd.encoded

        // HEADER_LENGTH(6) + LENGTH(6: type+bodyLen+uniqueId) + CRC(2) = 14 bytes
        assertThat(encoded.size).isEqualTo(14)

        val headerUniqueId = readInt(encoded, 0)
        assertThat(headerUniqueId).isEqualTo(uniqueId)

        val bodyUniqueId = readInt(encoded, 8)
        assertThat(bodyUniqueId).isEqualTo(uniqueId)

        assertCrcMatches(encoded, crcOffset = 12)
    }

    @Test
    fun `GetVersionCommand with different sequence numbers produces different encodings`() {
        val uniqueId = 0x12345678
        val cmd1 = GetVersionCommand.Builder().setUniqueId(uniqueId).setSequenceNumber(1).build()
        val cmd2 = GetVersionCommand.Builder().setUniqueId(uniqueId).setSequenceNumber(2).build()

        assertThat(cmd1.encoded).isNotEqualTo(cmd2.encoded)
    }

    // -- GetStatusCommand -----------------------------------------------------------------------

    @Test
    fun `GetStatusCommand encodes to the documented 11-byte layout`() {
        val uniqueId = 0x0A0B0C0D
        val sequenceNumber: Short = 2

        val cmd = GetStatusCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
            .build()

        val encoded = cmd.encoded

        // HEADER_LENGTH(6) + LENGTH(3: type+bodyLen+statusResponseType) + CRC(2) = 11 bytes
        assertThat(encoded.size).isEqualTo(11)
        assertThat(encoded[8]).isEqualTo(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE.value)

        assertCrcMatches(encoded, crcOffset = 9)
    }

    @Test
    fun `GetStatusCommand reflects a different requested status page in its encoding`() {
        val uniqueId = 0x0A0B0C0D
        val sequenceNumber: Short = 2

        val defaultPage = GetStatusCommand.Builder()
            .setUniqueId(uniqueId).setSequenceNumber(sequenceNumber)
            .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
            .build()
        val alarmPage = GetStatusCommand.Builder()
            .setUniqueId(uniqueId).setSequenceNumber(sequenceNumber)
            .setStatusResponseType(ResponseType.StatusResponseType.ALARM_STATUS)
            .build()

        assertThat(defaultPage.encoded).isNotEqualTo(alarmPage.encoded)
        assertThat(alarmPage.encoded[8]).isEqualTo(ResponseType.StatusResponseType.ALARM_STATUS.value)
    }

    // -- SilenceAlertsCommand -------------------------------------------------------------------

    @Test
    fun `SilenceAlertsCommand encodes to the documented 15-byte layout`() {
        val uniqueId = 0x2233AABB.toInt()
        val sequenceNumber: Short = 9
        val nonce = 0x0A0B0C0D
        val alertTypes = EnumSet.of(AlertType.LOW_RESERVOIR, AlertType.SUSPEND_ENDED)

        val cmd = SilenceAlertsCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .setNonce(nonce)
            .setAlertTypes(alertTypes)
            .build()

        val encoded = cmd.encoded

        // HEADER_LENGTH(6) + LENGTH(7: type+bodyLen+nonce(4)+alertByte(1)) + CRC(2) = 15 bytes
        assertThat(encoded.size).isEqualTo(15)

        val encodedNonce = readInt(encoded, 8)
        assertThat(encodedNonce).isEqualTo(nonce)

        assertCrcMatches(encoded, crcOffset = 13)
    }

    @Test
    fun `SilenceAlertsCommand alert byte round-trips through AlertUtil`() {
        val alertTypes = EnumSet.of(AlertType.LOW_RESERVOIR, AlertType.SUSPEND_ENDED)
        val cmd = SilenceAlertsCommand.Builder()
            .setUniqueId(0x2233AABB.toInt())
            .setSequenceNumber(9)
            .setNonce(0x0A0B0C0D)
            .setAlertTypes(alertTypes)
            .build()

        val decoded = AlertUtil.decodeAlertSet(cmd.encoded[12])

        assertThat(decoded).isEqualTo(alertTypes)
    }

    @Test
    fun `SilenceAlertsCommand with an empty alert set encodes to zero`() {
        val cmd = SilenceAlertsCommand.Builder()
            .setUniqueId(0x2233AABB.toInt())
            .setSequenceNumber(9)
            .setNonce(0x0A0B0C0D)
            .setAlertTypes(EnumSet.noneOf(AlertType::class.java))
            .build()

        assertThat(cmd.encoded[12]).isEqualTo(0.toByte())
        assertThat(AlertUtil.decodeAlertSet(cmd.encoded[12])).isEmpty()
    }

    // -- shared helpers --------------------------------------------------------------------------

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Recomputes the CRC over everything before [crcOffset] using the real, production
     *  [MessageUtil.createCrc] and checks it matches the two CRC bytes at [crcOffset]. */
    private fun assertCrcMatches(encoded: ByteArray, crcOffset: Int) {
        val withoutCrc = encoded.copyOfRange(0, crcOffset)
        val expectedCrc = MessageUtil.createCrc(withoutCrc)
        val embeddedCrc = (((encoded[crcOffset].toInt() and 0xFF) shl 8) or
            (encoded[crcOffset + 1].toInt() and 0xFF)).toShort()
        assertThat(embeddedCrc).isEqualTo(expectedCrc)
    }
}
