package app.aaps.pump.omnipod.common.bledriver.pod.command.aid

import app.aaps.pump.omnipod.common.bledriver.comm.session.Session
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.charset.StandardCharsets

/**
 * Verifies the exact byte-for-byte wire format of the 8 O5 AID setup commands (see
 * [O5AidSetupCommands]'s class doc) against the values OmnipodKit's own O5AidCommands.swift
 * sends - cross-checked there against a real captured O5 pairing's comm log, not just
 * protocol documentation. [Session] is mocked directly (a plain, non-open Kotlin class,
 * mockable via this project's inline mock maker - same approach already used for
 * [app.aaps.pump.omnipod.common.bledriver.comm.message.MessageIO] elsewhere) so this stays
 * a pure wire-format test with no AES-CCM/nonce simulation involved.
 */
class O5AidSetupCommandsTest {

    @Test
    fun `sends all 8 AID setup commands in order with the exact OmnipodKit wire format`() {
        val session = mock<Session>()
        whenever(session.sendAidSetupCommand(any(), any())).thenReturn(ByteArray(0))
        val before = System.currentTimeMillis() / 1000

        O5AidSetupCommands.send(session)

        val payloads = argumentCaptor<ByteArray>()
        val prefixes = argumentCaptor<String>()
        verify(session, times(8)).sendAidSetupCommand(payloads.capture(), prefixes.capture())
        val after = System.currentTimeMillis() / 1000

        // 1: UTC time (feature 255.2, Extended SET) - the only command with a real (not
        // fixed-placeholder) value, so only its numeric suffix is range-checked.
        val utc = payloads.allValues[0].toString(StandardCharsets.US_ASCII)
        assertThat(utc).startsWith("SE255.2=")
        val utcSeconds = utc.removePrefix("SE255.2=").toLong()
        assertThat(utcSeconds).isAtLeast(before)
        assertThat(utcSeconds).isAtMost(after)
        assertThat(prefixes.allValues[0]).isEqualTo("ES255.2=")

        // 2: TDI (feature 3.2, SET+GET) - fixed 5-byte body.
        assertThat(payloads.allValues[1]).isEqualTo(
            "S3.2=".toByteArray(StandardCharsets.US_ASCII) +
                byteArrayOf(0x00, 0x03, 0x00, 0x0E, 0x00) +
                ",G3.2".toByteArray(StandardCharsets.US_ASCII)
        )
        assertThat(prefixes.allValues[1]).isEqualTo("3.2=")

        // 3: target BG profile (feature 3.1, SET+GET) - 2-byte length (192) + 48 half-hour
        // slots, each a 4-byte big-endian 110 mg/dL. 204 bytes total, matching the
        // reference's confirmed real-device capture.
        val expectedTargets = ByteArray(2 + 48 * 4)
        expectedTargets[0] = 0x00
        expectedTargets[1] = 0xC0.toByte()
        for (slot in 0 until 48) {
            val offset = 2 + slot * 4
            expectedTargets[offset] = 0x00
            expectedTargets[offset + 1] = 0x00
            expectedTargets[offset + 2] = 0x00
            expectedTargets[offset + 3] = 0x6E // 110 decimal
        }
        assertThat(payloads.allValues[2]).isEqualTo(
            "S3.1=".toByteArray(StandardCharsets.US_ASCII) + expectedTargets + ",G3.1".toByteArray(StandardCharsets.US_ASCII)
        )
        assertThat(payloads.allValues[2].size).isEqualTo(204)
        assertThat(prefixes.allValues[2]).isEqualTo("3.1=")

        // 4: DIA (feature 3.9, SET+GET) - fixed ASCII "8".
        assertThat(payloads.allValues[3]).isEqualTo("S3.9=8,G3.9".toByteArray(StandardCharsets.US_ASCII))
        assertThat(prefixes.allValues[3]).isEqualTo("3.9=")

        // 5: EGV/CGM config (feature 3.7, SET+GET) - fixed ASCII composite value.
        assertThat(payloads.allValues[4]).isEqualTo("S3.7=3670015,G3.7".toByteArray(StandardCharsets.US_ASCII))
        assertThat(prefixes.allValues[4]).isEqualTo("3.7=")

        // 6-8: algorithm insulin history (feature 2.1, Extended SET), sent identically 3
        // times - 2-byte length (168) + 24 all-zero 7-byte records. 176 bytes total,
        // matching the reference's confirmed real-device capture.
        val expectedHistory = "SE2.1=".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0x00, 0xA8.toByte()) + ByteArray(24 * 7)
        assertThat(expectedHistory.size).isEqualTo(176)
        for (i in 5..7) {
            assertThat(payloads.allValues[i]).isEqualTo(expectedHistory)
            assertThat(prefixes.allValues[i]).isEqualTo("ES2.1=")
        }
    }
}
