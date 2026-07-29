package app.aaps.pump.omnipod.common.bledriver.comm.packet

import app.aaps.pump.omnipod.common.bledriver.comm.message.CrcMismatchException
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Round-trips payloads through [PayloadSplitter] -> [BlePacket.toByteArray] -> [PayloadJoiner]
 * for both [BlePacketLayout.DASH] and [BlePacketLayout.OMNIPOD_5].
 *
 * The O5 sweep in particular targets the bug fixed alongside the O5 layout support: packet
 * "rest"/size fields are on-wire unsigned bytes (0..255), but Kotlin's Byte is signed, so any
 * payload whose last-packet remainder exceeds 127 previously sign-extended into a negative
 * length and broke [PayloadJoiner] parsing. Dash never produced a remainder over 127 (its
 * packets max out at 20 bytes), so this was unreachable there - O5's 244-byte packets reach it
 * routinely for realistic message sizes (e.g. certificate-carrying pairing messages).
 */
class PayloadSplitterJoinerTest : TestBase() {

    private fun payloadOf(size: Int, seed: Int): ByteArray = Random(seed).nextBytes(size)

    private fun roundTrip(payload: ByteArray, layout: BlePacketLayout): ByteArray {
        val packets = PayloadSplitter(payload, layout).splitInPackets()
        val encoded = packets.map { it.toByteArray(layout) }

        val joiner = PayloadJoiner(encoded.first(), layout)
        for (packetBytes in encoded.drop(1)) {
            joiner.accumulate(packetBytes)
        }
        return joiner.finalize()
    }

    @Test
    fun `Dash round-trip holds for every supported payload size`() {
        // maxFragments=15 means fullFragments (middleFragments+1) must stay under 15, i.e.
        // middleFragments <= 13 - which caps the largest payload the Dash layout can carry
        // at 283 bytes (18 + 13*19 + 18, the last term being the max rest before it would
        // need a 14th middle fragment instead). Sizes beyond that are out of protocol range.
        for (size in 1..283) {
            val payload = payloadOf(size, seed = size)
            val result = roundTrip(payload, BlePacketLayout.DASH)
            assertThat(result).isEqualTo(payload)
        }
    }

    @Test
    fun `O5 round-trip holds for every payload size from 1 to 2000 bytes`() {
        // Covers, among other things, every case where the last packet's "rest" byte -
        // and the optional continuation packet's size byte - legitimately exceeds 127,
        // which is where the signed-Byte bug lived.
        for (size in 1..2000) {
            val payload = payloadOf(size, seed = size)
            val result = roundTrip(payload, BlePacketLayout.OMNIPOD_5)
            assertThat(result).isEqualTo(payload)
        }
    }

    @Test
    fun `O5 last-packet remainder over 127 round-trips correctly (regression for the signed-Byte bug)`() {
        // firstPacketCapacityWithMiddlePackets=242, middlePacketCapacity=243: 2 middle
        // fragments then a remainder of exactly 200 bytes in the last packet - 200 doesn't
        // fit in a signed Kotlin Byte (max 127), which is exactly what the fix addresses.
        val size = 242 + 2 * 243 + 200
        val payload = payloadOf(size, seed = 4242)

        val packets = PayloadSplitter(payload, BlePacketLayout.OMNIPOD_5).splitInPackets()
        val lastPacket = packets.filterIsInstance<LastBlePacket>().single()
        assertThat(lastPacket.size.toUnsignedInt()).isEqualTo(200)

        val result = roundTrip(payload, BlePacketLayout.OMNIPOD_5)
        assertThat(result).isEqualTo(payload)
    }

    @Test
    fun `LastOptionalPlusOneBlePacket round-trips a size byte over 127`() {
        // The splitter can never actually hand this packet type a size over 127 for O5's
        // current capacity ratio (243 middle / 238 last caps its overflow at 4 bytes), but
        // BLEPacket.swift's on-wire format supports the full 0..255 range for this field
        // and nothing in BlePacket.kt itself assumes otherwise - so pin toByteArray()/parse()
        // directly, independent of what the splitter happens to produce today.
        val layout = BlePacketLayout.OMNIPOD_5
        val payload = payloadOf(200, seed = 55)
        val packet = LastOptionalPlusOneBlePacket(index = 5, payload = payload, size = 200.toByte())

        val encoded = packet.toByteArray(layout)
        val parsed = LastOptionalPlusOneBlePacket.parse(encoded, layout)

        assertThat(parsed.size.toUnsignedInt()).isEqualTo(200)
        assertThat(parsed.payload).isEqualTo(payload)
    }

    @Test
    fun `Byte toUnsignedInt reinterprets the full 0-255 range correctly`() {
        assertThat(0.toByte().toUnsignedInt()).isEqualTo(0)
        assertThat(127.toByte().toUnsignedInt()).isEqualTo(127)
        assertThat(128.toByte().toUnsignedInt()).isEqualTo(128)
        assertThat(200.toByte().toUnsignedInt()).isEqualTo(200)
        assertThat(255.toByte().toUnsignedInt()).isEqualTo(255)
    }

    @Test
    fun `corrupted payload is still detected via CRC mismatch after joining (O5)`() {
        val payload = payloadOf(500, seed = 99)
        val packets = PayloadSplitter(payload, BlePacketLayout.OMNIPOD_5).splitInPackets()
        val encoded = packets.map { it.toByteArray(BlePacketLayout.OMNIPOD_5) }.toMutableList()

        // Flip the first byte of the last packet's actual payload (right after its 6-byte
        // header). toByteArray() always returns a full maxPayloadSize-length array, zero-
        // padded past the real content - corrupting the padding tail wouldn't be seen by
        // parse(), since it only reads up to the packet's own declared "rest" length.
        val corruptIndex = 6
        encoded[encoded.lastIndex][corruptIndex] = (encoded.last()[corruptIndex] + 1).toByte()

        val joiner = PayloadJoiner(encoded.first(), BlePacketLayout.OMNIPOD_5)
        for (packetBytes in encoded.drop(1)) {
            joiner.accumulate(packetBytes)
        }

        assertThrows(CrcMismatchException::class.java) {
            joiner.finalize()
        }
    }
}
