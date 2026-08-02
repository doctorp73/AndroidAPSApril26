package app.aaps.pump.omnipod.common.bledriver.comm.message

import app.aaps.core.utils.toHex
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.subtle.Hex
import org.junit.jupiter.api.Test

class StringLengthPrefixEncodingTest {

    private val p0Payload = Hex.decode("50,30,3d,00,01,a5".replace(",", "")) // from logs
    private val p0Content = Hex.decode("a5")

    @Test fun testFormatKeysP0() {
        val payload = StringLengthPrefixEncoding.formatKeys(arrayOf("P0="), arrayOf(p0Content))
        assertThat(p0Payload.toHex()).isEqualTo(payload.toHex())
    }

    @Test fun testParseKeysP0() {
        val parsed = StringLengthPrefixEncoding.parseKeys(arrayOf("P0="), p0Payload)
        assertThat(parsed).hasLength(1)
        assertThat(parsed[0].toHex()).isEqualTo(p0Content.toHex())
    }

    // Regression tests for a length-decode bug: parseKeys() previously computed the 2-byte
    // big-endian length as (byte0 shl 1) or byte1 instead of (byte0 shl 8) or byte1 - shifting
    // the high length byte by 1 bit instead of 1 byte. This was invisible for every payload
    // above (P0=, and everything else exercised so far: SP1/SP2/SPS0/SPS1), since they're all
    // under 256 bytes, where the high length byte is always 0x00 and the bug produces the same
    // result as correct code. O5's SPS2.1 response is ~634 bytes and SPS2 is larger still.

    @Test fun testParseKeysDecodesLengthByteCorrectly() {
        // Directly pins the length decode: 0x01,0x2c must decode to 300 (big-endian), not the
        // 46 a shl-1-instead-of-shl-8 bug would produce ((0x01 shl 1) or 0x2c = 46).
        val key = "K="
        val length = 300
        val payload = ByteArray(length) { 0x42 }
        val message = key.toByteArray() + byteArrayOf(0x01, 0x2c) + payload

        val parsed = StringLengthPrefixEncoding.parseKeys(arrayOf(key), message)

        assertThat(parsed).hasLength(1)
        assertThat(parsed[0]).hasLength(length)
        assertThat(parsed[0]).isEqualTo(payload)
    }

    @Test fun testFormatAndParseKeysRoundTripsPayloadsOver256Bytes() {
        val largePayload = ByteArray(634) { (it % 256).toByte() } // O5's actual SPS2.1 size

        val formatted = StringLengthPrefixEncoding.formatKeys(arrayOf("SPS2.1="), arrayOf(largePayload))
        val parsed = StringLengthPrefixEncoding.parseKeys(arrayOf("SPS2.1="), formatted)

        assertThat(parsed).hasLength(1)
        assertThat(parsed[0]).isEqualTo(largePayload)
    }
}
