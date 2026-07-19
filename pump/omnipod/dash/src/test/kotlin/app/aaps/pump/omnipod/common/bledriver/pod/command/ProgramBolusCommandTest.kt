package app.aaps.pump.omnipod.common.bledriver.pod.command

import app.aaps.pump.omnipod.common.bledriver.pod.definition.ProgramReminder
import com.google.common.truth.Truth.assertThat
import org.apache.commons.codec.binary.Hex
import org.junit.jupiter.api.Test

class ProgramBolusCommandTest {

    @Test fun testProgramBolusCommand() {
        val encoded = ProgramBolusCommand.Builder()
            .setNumberOfUnits(5.0)
            .setProgramReminder(ProgramReminder(false, true, 0.toByte()))
            .setDelayBetweenPulsesInEighthSeconds(16.toByte())
            .setUniqueId(37879809)
            .setSequenceNumber(14.toShort())
            .setNonce(1229869870)
            .build()
            .encoded

        assertThat(encoded).asList().containsExactlyElementsIn(Hex.decodeHex("02420001381F1A0E494E532E02010F01064000640064170D4003E800030D4000000000000080F6").asList()).inOrder()
    }

    // -- O5 wire format (18-byte body incl. bolusSource/mealUnits/correctionUnits) ------------
    //
    // Verified against real captured O5 pairing/bolus data in OmnipodKit's own
    // BolusExtraCommand.swift doc comment ("17 12 7c 00c8 00030d40 0000 00000000 01 00c8 0000"
    // for a 1.0U bolus, "17 12 7c 0208 000186a0 0000 00000000 01 0000 0000" for a prime bolus)
    // - the piece this test suite was missing when the Dash-only 13-byte format was
    // discovered to be silently wrong for O5. Only the trailing "bolusCommand" sub-block is
    // compared (the last 22 bytes before the final CRC), since these fixtures are OmnipodKit's
    // own per-MessageBlock capture, not this codebase's fused header+interlock+bolus+CRC layout.

    private fun bolusCommandTail(encoded: ByteArray): ByteArray {
        // 20-byte O5 bolusCommand block, immediately before the trailing 2-byte CRC.
        return encoded.copyOfRange(encoded.size - 22, encoded.size - 2)
    }

    @Test fun testO5BolusWireFormatMatchesRealCapturedOneUnitBolus() {
        val encoded = ProgramBolusCommand.Builder()
            .setNumberOfUnits(1.0)
            .setProgramReminder(ProgramReminder(atStart = false, atEnd = true, atInterval = 60.toByte()))
            .setDelayBetweenPulsesInEighthSeconds(16.toByte())
            .setO5BolusInfo(mealUnits = 1.0, correctionUnits = 0.0)
            .setUniqueId(37879809)
            .setSequenceNumber(14.toShort())
            .setNonce(1229869870)
            .build()
            .encoded

        assertThat(bolusCommandTail(encoded)).asList()
            .containsExactlyElementsIn(Hex.decodeHex("17127c00c800030d40000000000000 0100c80000".replace(" ", "")).asList())
            .inOrder()
    }

    @Test fun testO5BolusWireFormatMatchesRealCapturedPrimeBolus() {
        val encoded = ProgramBolusCommand.Builder()
            .setNumberOfUnits(2.6)
            .setProgramReminder(ProgramReminder(atStart = false, atEnd = true, atInterval = 60.toByte()))
            .setDelayBetweenPulsesInEighthSeconds(8.toByte())
            .setO5BolusInfo(mealUnits = 0.0, correctionUnits = 0.0)
            .setUniqueId(37879809)
            .setSequenceNumber(14.toShort())
            .setNonce(1229869870)
            .build()
            .encoded

        assertThat(bolusCommandTail(encoded)).asList()
            .containsExactlyElementsIn(Hex.decodeHex("17127c0208000186a0000000000000 0100000000".replace(" ", "")).asList())
            .inOrder()
    }

    @Test fun testO5BolusInfoOmittedKeepsTheOriginalDashEros13ByteFormat() {
        // Regression guard: OmnipodDashManagerImpl must keep getting the original format -
        // not calling setO5BolusInfo() must produce byte-identical output to before this fix.
        val encoded = ProgramBolusCommand.Builder()
            .setNumberOfUnits(5.0)
            .setProgramReminder(ProgramReminder(false, true, 0.toByte()))
            .setDelayBetweenPulsesInEighthSeconds(16.toByte())
            .setUniqueId(37879809)
            .setSequenceNumber(14.toShort())
            .setNonce(1229869870)
            .build()
            .encoded

        assertThat(encoded).asList().containsExactlyElementsIn(Hex.decodeHex("02420001381F1A0E494E532E02010F01064000640064170D4003E800030D4000000000000080F6").asList()).inOrder()
    }
}
