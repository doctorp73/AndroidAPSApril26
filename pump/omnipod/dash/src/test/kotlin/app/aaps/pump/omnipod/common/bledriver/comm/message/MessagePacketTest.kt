package app.aaps.pump.omnipod.common.bledriver.comm.message

import app.aaps.core.utils.toHex
import app.aaps.pump.omnipod.common.bledriver.comm.Id
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.subtle.Hex
import org.junit.jupiter.api.Test

class MessagePacketTest {

    private val payload =
        "54,57,11,01,07,00,03,40,08,20,2e,a8,08,20,2e,a9,ab,35,d8,31,60,9b,b8,fe,3a,3b,de,5b,18,37,24,9a,16,db,f8,e4,d3,05,e9,75,dc,81,7c,37,07,cc,41,5f,af,8a".replace(
            ",",
            ""
        )

    @Test fun testParseMessagePacket() {
        val msg = MessagePacket.parse(Hex.decode(payload))
        assertThat(msg.type).isEqualTo(MessageType.ENCRYPTED)
        assertThat(msg.source).isEqualTo(Id.fromLong(136326824))
        assertThat(msg.destination).isEqualTo(Id.fromLong(136326825))
        assertThat(msg.sequenceNumber).isEqualTo(7.toByte())
        assertThat(msg.ackNumber).isEqualTo(0.toByte())
        assertThat(msg.eqos).isEqualTo(1.toShort())
        assertThat(msg.priority).isFalse()
        assertThat(msg.lastMessage).isFalse()
        assertThat(msg.gateway).isFalse()
        assertThat(msg.sas).isTrue()
        assertThat(msg.tfs).isFalse()
        assertThat(msg.version).isEqualTo(0.toShort())
        assertThat(payload.substring(32, payload.length)).isEqualTo(msg.payload.toHex())
    }

    @Test fun testSerializeMessagePacket() {
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = Id.fromLong(136326824),
            destination = Id.fromLong(136326825),
            sequenceNumber = 7.toByte(),
            ackNumber = 0.toByte(),
            eqos = 1.toShort(),
            priority = false,
            lastMessage = false,
            gateway = false,
            sas = true,
            tfs = false,
            payload = Hex.decode(payload.substring(32, payload.length))
        )
        assertThat(msg.asByteArray().toHex()).isEqualTo(payload)
    }

    @Test fun testParseDecodesTypeFromF2NotF1() {
        // Regression test: parse() previously decoded `type` from f1 (payload[2]) instead of
        // f2 (payload[3]) - the same byte/bits used for eqos - even though asByteArray()
        // correctly writes type into f2. This was masked in testParseMessagePacket above
        // purely by coincidence: that packet's eqos(1) and tfs(false) happen to numerically
        // produce the same 4-bit value as type=ENCRYPTED(1). Using eqos=5 (distinct from
        // type=PAIRING=3) here makes the two unambiguous - the old code would decode type=13
        // (eqos's 3 bits shl-adjusted, or'd with tfs) and throw "Unknown MessageType: 13".
        val msg = MessagePacket(
            type = MessageType.PAIRING,
            source = Id.fromLong(1),
            destination = Id.fromLong(2),
            sequenceNumber = 1.toByte(),
            eqos = 5.toShort(),
            tfs = true,
            payload = ByteArray(4) { it.toByte() }
        )

        val parsed = MessagePacket.parse(msg.asByteArray())

        assertThat(parsed.type).isEqualTo(MessageType.PAIRING)
        assertThat(parsed.eqos).isEqualTo(5.toShort())
    }

    @Test fun testEncryptedSignedRoundTripAccountsForTagLikeEncrypted() {
        // Regression test: MessagePacket previously only checked type == ENCRYPTED for the
        // 8-byte MAC-tag size adjustment in both asByteArray() and parse() - Swift checks
        // both ENCRYPTED and ENCRYPTED_SIGNED. Not yet reachable via any caller in this
        // codebase (no signed-command layer built yet), but pins the wire-format math
        // correctly for whenever one exists, mirroring the ENCRYPTED-type coverage above.
        val fullPayload = ByteArray(20) { it.toByte() } // ciphertext + 8-byte tag
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED_SIGNED,
            source = Id.fromLong(1),
            destination = Id.fromLong(2),
            sequenceNumber = 1.toByte(),
            payload = fullPayload
        )

        val parsed = MessagePacket.parse(msg.asByteArray())

        assertThat(parsed.type).isEqualTo(MessageType.ENCRYPTED_SIGNED)
        assertThat(parsed.payload).isEqualTo(fullPayload)
    }
}
