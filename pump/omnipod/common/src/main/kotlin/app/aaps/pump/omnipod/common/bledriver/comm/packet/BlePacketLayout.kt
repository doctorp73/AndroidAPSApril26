package app.aaps.pump.omnipod.common.bledriver.comm.packet

import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodType

/**
 * BLE packet framing parameters. Dash and Omnipod 5 share the same header byte layout but
 * use different maximum payload sizes per packet (Dash: 20 bytes, O5: 244 bytes), which in
 * turn changes every derived fragment capacity used when splitting/joining message payloads.
 *
 * Ported from OmnipodKit's BlePodProfile.swift (loopandlearn/OmnipodKit) `BlePacketLayout`
 * struct and its `omnipodDash`/`omnipod5` presets.
 */
data class BlePacketLayout(
    val maxPayloadSize: Int,
    val maxFragments: Int,
    val firstPacketHeaderSizeWithoutMiddlePackets: Int,
    val firstPacketHeaderSizeWithMiddlePackets: Int,
    val lastPacketHeaderSize: Int
) {
    val firstPacketCapacityWithoutMiddlePackets: Int
        get() = maxPayloadSize - firstPacketHeaderSizeWithoutMiddlePackets

    val firstPacketCapacityWithMiddlePackets: Int
        get() = maxPayloadSize - firstPacketHeaderSizeWithMiddlePackets

    val firstPacketCapacityWithOptionalPlusOnePacket: Int
        get() = firstPacketCapacityWithMiddlePackets

    val middlePacketCapacity: Int
        get() = maxPayloadSize - 1

    val lastPacketCapacity: Int
        get() = maxPayloadSize - lastPacketHeaderSize

    companion object {

        val DASH = BlePacketLayout(
            maxPayloadSize = 20,
            maxFragments = 15,
            firstPacketHeaderSizeWithoutMiddlePackets = 7,
            firstPacketHeaderSizeWithMiddlePackets = 2,
            lastPacketHeaderSize = 6
        )

        val OMNIPOD_5 = BlePacketLayout(
            maxPayloadSize = 244,
            maxFragments = 15,
            firstPacketHeaderSizeWithoutMiddlePackets = 7,
            firstPacketHeaderSizeWithMiddlePackets = 2,
            lastPacketHeaderSize = 6
        )
    }
}

val PodType.blePacketLayout: BlePacketLayout
    get() = if (isO5) BlePacketLayout.OMNIPOD_5 else BlePacketLayout.DASH
