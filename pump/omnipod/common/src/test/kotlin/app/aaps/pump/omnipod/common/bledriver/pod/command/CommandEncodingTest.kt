package app.aaps.pump.omnipod.common.bledriver.pod.command

import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepType
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

    // -- DeactivateCommand ----------------------------------------------------------------------

    @Test
    fun `DeactivateCommand encodes to the documented 14-byte layout`() {
        val uniqueId = 0x33445566.toInt()
        val sequenceNumber: Short = 3
        val nonce = 0x0F0E0D0C

        val cmd = DeactivateCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .setNonce(nonce)
            .build()

        val encoded = cmd.encoded

        // HEADER_LENGTH(6) + LENGTH(6: type+bodyLen+nonce(4)) + CRC(2) = 14 bytes
        assertThat(encoded.size).isEqualTo(14)
        assertThat(encoded[6]).isEqualTo(0x1c.toByte()) // CommandType.DEACTIVATE

        val encodedNonce = readInt(encoded, 8)
        assertThat(encodedNonce).isEqualTo(nonce)

        assertCrcMatches(encoded, crcOffset = 12)
    }

    @Test
    fun `DeactivateCommand with different nonces produces different encodings`() {
        val uniqueId = 0x33445566.toInt()
        val cmd1 = DeactivateCommand.Builder().setUniqueId(uniqueId).setSequenceNumber(1).setNonce(100).build()
        val cmd2 = DeactivateCommand.Builder().setUniqueId(uniqueId).setSequenceNumber(1).setNonce(101).build()

        assertThat(cmd1.encoded).isNotEqualTo(cmd2.encoded)
    }

    // -- StopDeliveryCommand --------------------------------------------------------------------

    @Test
    fun `StopDeliveryCommand encodes ALL plus LONG_SINGLE_BEEP to the documented 15-byte layout`() {
        val uniqueId = 0x11223344.toInt()
        val sequenceNumber: Short = 4
        val nonce = 0x0A0B0C0D

        val cmd = StopDeliveryCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .setNonce(nonce)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.ALL)
            .setBeepType(BeepType.LONG_SINGLE_BEEP)
            .build()

        val encoded = cmd.encoded

        // HEADER_LENGTH(6) + LENGTH(7: type+bodyLen+nonce(4)+deliveryTypeByte(1)) + CRC(2) = 15 bytes
        assertThat(encoded.size).isEqualTo(15)
        assertThat(encoded[6]).isEqualTo(0x1f.toByte()) // CommandType.STOP_DELIVERY

        val encodedNonce = readInt(encoded, 8)
        assertThat(encodedNonce).isEqualTo(nonce)

        // DeliveryType.ALL -> bits {basal,tempBasal,bolus} all set -> 0x07;
        // BeepType.LONG_SINGLE_BEEP = 0x06 in the high nibble -> combined 0x67
        assertThat(encoded[12]).isEqualTo(0x67.toByte())

        assertCrcMatches(encoded, crcOffset = 13)
    }

    @Test
    fun `StopDeliveryCommand DeliveryType BASAL produces a different, correctly-masked encoding`() {
        val uniqueId = 0x11223344.toInt()
        val nonce = 0x0A0B0C0D

        val allCmd = StopDeliveryCommand.Builder()
            .setUniqueId(uniqueId).setSequenceNumber(4).setNonce(nonce)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.ALL).setBeepType(BeepType.LONG_SINGLE_BEEP)
            .build()
        val basalOnlyCmd = StopDeliveryCommand.Builder()
            .setUniqueId(uniqueId).setSequenceNumber(4).setNonce(nonce)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.BASAL).setBeepType(BeepType.SILENT)
            .build()

        // BASAL only -> bit0 set -> 0x01; BeepType.SILENT = 0x00 in high nibble -> combined 0x01
        assertThat(basalOnlyCmd.encoded[12]).isEqualTo(0x01.toByte())
        assertThat(allCmd.encoded).isNotEqualTo(basalOnlyCmd.encoded)
    }

    // -- ProgramAlertsCommand -------------------------------------------------------------------
    //
    // Configures WHEN the pod's own alert system should fire (a timer offset or reservoir
    // volume threshold, plus beep pattern) - no insulin delivery amount is computed here.

    @Test
    fun `ProgramAlertsCommand encodes a single AlertConfiguration to the documented 20-byte layout`() {
        val uniqueId = 0x22334455.toInt()
        val sequenceNumber: Short = 6
        val nonce = 0x01020304

        val alertConfig = AlertConfiguration(
            type = AlertType.SUSPEND_ENDED,
            enabled = true,
            durationInMinutes = 0,
            autoOff = false,
            trigger = AlertTrigger.TimerTrigger(20),
            beepType = BeepType.FOUR_TIMES_BIP_BEEP,
            beepRepetition = BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
        )

        val cmd = ProgramAlertsCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .setNonce(nonce)
            .setAlertConfigurations(listOf(alertConfig))
            .build()

        val encoded = cmd.encoded

        // HEADER(6) + [type(1)+bodyLen(1)+nonce(4)+alertConfig(6)] + CRC(2) = 20 bytes
        assertThat(encoded.size).isEqualTo(20)
        assertThat(encoded[6]).isEqualTo(0x19.toByte()) // CommandType.PROGRAM_ALERTS
        assertThat(encoded[7]).isEqualTo(10.toByte())   // bodyLength = 1*6+4

        assertThat(readInt(encoded, 8)).isEqualTo(nonce)

        // AlertConfiguration bytes at offset 12: firstByte = (type.index<<4=0x60) | enabled(bit3) = 0x68
        assertThat(encoded[12]).isEqualTo(0x68.toByte())
        assertThat(encoded[13]).isEqualTo(0.toByte()) // durationInMinutes
        assertThat(encoded[14]).isEqualTo(0.toByte()) // offsetInMinutes high byte
        assertThat(encoded[15]).isEqualTo(20.toByte()) // offsetInMinutes low byte
        assertThat(encoded[16]).isEqualTo(BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN.value)
        assertThat(encoded[17]).isEqualTo(BeepType.FOUR_TIMES_BIP_BEEP.value)

        assertCrcMatches(encoded, crcOffset = 18)
    }

    // -- SuspendDeliveryCommand -----------------------------------------------------------------
    //
    // Composes StopDelivery(ALL) with an embedded, fixed ProgramAlerts(SUSPEND_ENDED) command -
    // still no delivery-rate computation anywhere; it's a fixed "stop everything and arm one
    // specific alert" sequence.

    @Test
    fun `SuspendDeliveryCommand composes StopDelivery ALL plus embedded ProgramAlerts correctly`() {
        val uniqueId = 0x22334455.toInt()
        val sequenceNumber: Short = 6
        val nonce = 0x01020304

        val cmd = SuspendDeliveryCommand.Builder()
            .setUniqueId(uniqueId)
            .setSequenceNumber(sequenceNumber)
            .setNonce(nonce)
            .setBeepType(BeepType.LONG_SINGLE_BEEP)
            .build()

        val encoded = cmd.encoded

        // HEADER(6) + [type+bodyLen+nonce(4)+deliveryTypeByte(1)](7) + embeddedProgramAlerts(12) + CRC(2) = 27 bytes
        assertThat(encoded.size).isEqualTo(27)
        assertThat(encoded[6]).isEqualTo(0x1f.toByte()) // CommandType.STOP_DELIVERY

        assertThat(readInt(encoded, 8)).isEqualTo(nonce)

        // byte 12: (beepType.value<<4) | DeliveryType.ALL bits(0x07) = (0x06<<4)|0x07 = 0x67
        assertThat(encoded[12]).isEqualTo(0x67.toByte())

        // Embedded ProgramAlertsCommand starts at offset 13: type(0x19), bodyLen(10), nonce(4), alertConfig(6)
        assertThat(encoded[13]).isEqualTo(0x19.toByte())
        assertThat(encoded[14]).isEqualTo(10.toByte())
        assertThat(readInt(encoded, 15)).isEqualTo(nonce) // embedded nonce matches outer nonce
        assertThat(encoded[19]).isEqualTo(0x68.toByte())  // embedded alertConfig firstByte (SUSPEND_ENDED, enabled)

        assertCrcMatches(encoded, crcOffset = 25)
    }

    // -- SetUniqueIdCommand ---------------------------------------------------------------------
    //
    // Assigns the pod's address during activation. No dosing math - purely identity/timestamp
    // assignment. Notably, the message HEADER uses a placeholder address (the pod has no real
    // address to address it BY yet), while the BODY carries the real address being assigned.

    @Test
    fun `SetUniqueIdCommand encodes to the documented 29-byte layout`() {
        val targetUniqueId = 0x12345678
        val sequenceNumber: Short = 7
        val lotNumber = 123456
        val podSequenceNumber = 654321

        val cal = java.util.Calendar.getInstance()
        cal.set(2024, java.util.Calendar.MARCH, 15, 10, 30, 0)

        val cmd = SetUniqueIdCommand.Builder()
            .setUniqueId(targetUniqueId)
            .setSequenceNumber(sequenceNumber)
            .setLotNumber(lotNumber)
            .setPodSequenceNumber(podSequenceNumber)
            .setInitializationTime(cal.time)
            .build()

        val encoded = cmd.encoded

        // HEADER(6) + [type+bodyLen+uniqueId(4)+0x14+0x04+initTime(5)+lotNumber(4)+podSeqNum(4)](21) + CRC(2) = 29
        assertThat(encoded.size).isEqualTo(29)

        // Header address (bytes 0-3) is the placeholder 0xFFFFFFFF, not the target uniqueId.
        assertThat(readInt(encoded, 0)).isEqualTo(-1)

        assertThat(encoded[6]).isEqualTo(0x03.toByte()) // CommandType.SET_UNIQUE_ID

        // Body (bytes 8-11) carries the REAL target uniqueId being assigned.
        assertThat(readInt(encoded, 8)).isEqualTo(targetUniqueId)

        assertThat(encoded[12]).isEqualTo(0x14.toByte())
        assertThat(encoded[13]).isEqualTo(0x04.toByte())

        // Initialization time: month, day, year%100, hour, minute
        assertThat(encoded[14]).isEqualTo(3.toByte())  // March
        assertThat(encoded[15]).isEqualTo(15.toByte())
        assertThat(encoded[16]).isEqualTo(24.toByte()) // 2024 % 100
        assertThat(encoded[17]).isEqualTo(10.toByte())
        assertThat(encoded[18]).isEqualTo(30.toByte())

        assertThat(readInt(encoded, 19)).isEqualTo(lotNumber)
        assertThat(readInt(encoded, 23)).isEqualTo(podSequenceNumber)

        assertCrcMatches(encoded, crcOffset = 27)
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
