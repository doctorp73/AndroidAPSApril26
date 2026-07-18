package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.core.data.model.BS
import app.aaps.pump.omnipod.common.bledriver.comm.pair.PairResult
import app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlarmType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BasalProgram
import app.aaps.pump.omnipod.common.bledriver.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.SoftwareVersion
import app.aaps.pump.omnipod.common.bledriver.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.VersionResponse
import java.io.Serializable
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persisted/in-memory state needed to connect to, pair with, and read status from an
 * Omnipod 5 pod, plus the dosing/control state ([O5PumpPlugin][app.aaps.pump.omnipod
 * .common.O5PumpPlugin] needs to track basal program, last bolus, active temp basal, and
 * an in-flight dose command whose outcome is still uncertain (BLE response never
 * arrived) so it can be reconciled on the next status poll. Deliberately does NOT use a
 * Dash-style historyId/persisted-ledger ([OmnipodDashPodStateManager.ActiveCommand])
 * to track in-flight commands - [app.aaps.pump.omnipod.common.bledriver.comm
 * .O5BleManager.sendCommand] already gives 1:1 request/response correlation per call
 * (unlike Dash's continuous connection-scoped event bus), so [pendingDoseCommand] only
 * needs to be a single flat marker, not a ledger.
 *
 * [updateFromVersionResponse] and [updateFromDefaultStatusResponse] are read-only status
 * updates - reused directly from [VersionResponse]/[DefaultStatusResponse], which turned
 * out to have no Dash-specific assumptions baked in (pure byte-offset parsing), matching
 * the same finding as [app.aaps.pump.omnipod.common.bledriver.pod.command.base
 * .HeaderEnabledCommand].
 */
interface O5PodStateManager {

    enum class BluetoothConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

    var bluetoothConnectionState: BluetoothConnectionState
    var connectionAttempts: Int
    var successfulConnections: Int

    /** The Bluetooth MAC address of the paired pod, once known. */
    var bluetoothAddress: String?

    /** The certificate-derived controller id used for this pod's O5 identity (see
     *  [app.aaps.pump.omnipod.common.bledriver.comm.pair.O5CertificateStore.controllerId]). */
    var controllerId: Long?

    /** The pod's own id, once paired. */
    var podId: Long?

    /** The pod's long-term key, once paired. Null until [updateFromPairing] is called. */
    var ltk: ByteArray?

    /** Message sequence number to resume from after pairing/reconnection. */
    var msgSequenceNumber: Byte

    /** Advances [msgSequenceNumber] by one, wrapping at 4 bits (0x0-0xf) - the pod's
     *  command-header sequence number, distinct from the BLE-packet-level session
     *  sequence number the [app.aaps.pump.omnipod.common.bledriver.comm.session.Session]
     *  layer manages on its own. Must be called after every command round trip (sent,
     *  send-unconfirmed, or response received) or the pod will NAK every command after
     *  the first in a session - mirrors [OmnipodDashPodStateManager
     *  .increaseMessageSequenceNumber]. */
    fun increaseMessageSequenceNumber()

    var eapAkaSequenceNumber: Long

    // -- dosing/control state ------------------------------------------------------------

    /** The basal program currently believed to be running on the pod. */
    var basalProgram: BasalProgram?

    /** True while the pod's delivery is suspended (no basal/temp basal delivery). */
    var deliverySuspended: Boolean

    var lastBolusStartTime: Long?
    var lastBolusRequestedUnits: Double?

    /** Null until the bolus is confirmed delivered (fully or partially - see
     *  [app.aaps.pump.omnipod.common.O5PumpPlugin]'s bolus-completion polling). */
    var lastBolusDeliveredUnits: Double?

    var activeTempBasalStartTime: Long?
    var activeTempBasalRate: Double?
    var activeTempBasalDurationMinutes: Short?

    /**
     * A single dose-affecting command whose outcome is not yet confirmed - set
     * optimistically right before the [app.aaps.pump.omnipod.common.bledriver.comm
     * .O5BleManager.sendCommand] `Observable` for a bolus/temp-basal/basal-program
     * command is subscribed, and cleared once the next status response confirms (or
     * denies) it actually took effect. This is the field that makes uncertain BLE
     * outcomes (dose sent but response never arrived) recoverable rather than silently
     * lost - see [app.aaps.pump.omnipod.common.O5PumpPlugin]'s reconciliation logic.
     */
    var pendingDoseCommand: PendingDoseCommand?

    enum class PendingDoseType { BOLUS, TEMP_BASAL_START, TEMP_BASAL_CANCEL, BASAL_PROGRAM }

    data class PendingDoseCommand(
        val type: PendingDoseType,
        val requestedUnits: Double? = null,
        val requestedRate: Double? = null,
        val requestedDurationMinutes: Short? = null,
        /** Only set for [PendingDoseType.BOLUS] - needed to correctly finalize
         *  [app.aaps.core.interfaces.pump.PumpSync.syncBolusWithPumpId] if the original
         *  call never reached its own sync step. */
        val bolusType: BS.Type? = null,
        val startedAt: Long
    ) : Serializable

    // -- read-only pod status, populated from VersionResponse / DefaultStatusResponse ----

    val podStatus: PodStatus?
    val deliveryStatus: DeliveryStatus?
    val firmwareVersion: SoftwareVersion?
    val bleVersion: SoftwareVersion?
    val lotNumber: Long?
    val podSequenceNumber: Long?
    val totalPulsesDelivered: Short?
    val bolusPulsesRemaining: Short?
    val reservoirPulsesRemaining: Short?
    val activeAlerts: EnumSet<AlertType>?
    val minutesSinceActivation: Short?
    val sequenceNumberOfLastProgrammingCommand: Short?

    /** System.currentTimeMillis() when the last status was received, or null if never. */
    val lastStatusResponseReceived: Long?

    // -- alarm/fault diagnostics, populated from AlarmStatusResponse --------------------
    // Only fields not already covered by DefaultStatusResponse above - see that response
    // class's own field overlap with AlarmStatusResponse for why (both report podStatus/
    // deliveryStatus/pulses/etc; only these are unique to the alarm report).

    val alarmType: AlarmType?
    /** Pod-clock minutes-since-activation timestamp of when the alarm occurred (not a
     *  wall-clock time - the pod has no wall clock of its own). */
    val alarmTime: Short?
    val occlusionAlarm: Boolean?
    val podStatusWhenAlarmOccurred: PodStatus?
    val rssi: Short?

    fun updateFromVersionResponse(response: VersionResponse)
    fun updateFromDefaultStatusResponse(response: DefaultStatusResponse)
    fun updateFromAlarmStatusResponse(response: AlarmStatusResponse)

    /**
     * Returns the next EAP-AKA sequence number (as its 6-byte on-wire [EapSqn]
     * representation) without yet committing it - matching
     * [OmnipodDashPodStateManager]'s increment-then-commit-on-success pattern, so a
     * failed session establishment doesn't advance the persisted counter.
     */
    fun increaseEapAkaSequenceNumber(): ByteArray
    fun commitEapAkaSequenceNumber()

    fun updateFromPairing(controllerId: Long, podId: Long, pairResult: PairResult)

    fun reset()
}

/**
 * Simple in-memory [O5PodStateManager]. Not persisted across process restarts - use
 * [PersistedO5PodStateManager] for that; this is mainly useful for tests.
 */
class InMemoryO5PodStateManager : O5PodStateManager {

    @Volatile override var bluetoothConnectionState: O5PodStateManager.BluetoothConnectionState =
        O5PodStateManager.BluetoothConnectionState.DISCONNECTED

    private val connectionAttemptsCounter = AtomicInteger(0)
    override var connectionAttempts: Int
        get() = connectionAttemptsCounter.get()
        set(value) { connectionAttemptsCounter.set(value) }

    private val successfulConnectionsCounter = AtomicInteger(0)
    override var successfulConnections: Int
        get() = successfulConnectionsCounter.get()
        set(value) { successfulConnectionsCounter.set(value) }

    @Volatile override var bluetoothAddress: String? = null
    @Volatile override var controllerId: Long? = null
    @Volatile override var podId: Long? = null
    @Volatile override var ltk: ByteArray? = null
    @Volatile override var msgSequenceNumber: Byte = 1

    @Volatile override var eapAkaSequenceNumber: Long = 0
    @Volatile private var pendingEapAkaSequenceNumber: Long = 0

    @Volatile override var basalProgram: BasalProgram? = null
    @Volatile override var deliverySuspended: Boolean = false
    @Volatile override var lastBolusStartTime: Long? = null
    @Volatile override var lastBolusRequestedUnits: Double? = null
    @Volatile override var lastBolusDeliveredUnits: Double? = null
    @Volatile override var activeTempBasalStartTime: Long? = null
    @Volatile override var activeTempBasalRate: Double? = null
    @Volatile override var activeTempBasalDurationMinutes: Short? = null
    @Volatile override var pendingDoseCommand: O5PodStateManager.PendingDoseCommand? = null

    override fun increaseMessageSequenceNumber() {
        msgSequenceNumber = ((msgSequenceNumber.toInt() + 1) and 0x0f).toByte()
    }

    @Volatile override var podStatus: PodStatus? = null
        private set
    @Volatile override var deliveryStatus: DeliveryStatus? = null
        private set
    @Volatile override var firmwareVersion: SoftwareVersion? = null
        private set
    @Volatile override var bleVersion: SoftwareVersion? = null
        private set
    @Volatile override var lotNumber: Long? = null
        private set
    @Volatile override var podSequenceNumber: Long? = null
        private set
    @Volatile override var totalPulsesDelivered: Short? = null
        private set
    @Volatile override var bolusPulsesRemaining: Short? = null
        private set
    @Volatile override var reservoirPulsesRemaining: Short? = null
        private set
    @Volatile override var activeAlerts: EnumSet<AlertType>? = null
        private set
    @Volatile override var minutesSinceActivation: Short? = null
        private set
    @Volatile override var sequenceNumberOfLastProgrammingCommand: Short? = null
        private set
    @Volatile override var lastStatusResponseReceived: Long? = null
        private set

    @Volatile override var alarmType: AlarmType? = null
        private set
    @Volatile override var alarmTime: Short? = null
        private set
    @Volatile override var occlusionAlarm: Boolean? = null
        private set
    @Volatile override var podStatusWhenAlarmOccurred: PodStatus? = null
        private set
    @Volatile override var rssi: Short? = null
        private set

    override fun increaseEapAkaSequenceNumber(): ByteArray {
        pendingEapAkaSequenceNumber = eapAkaSequenceNumber + 1
        return EapSqn(pendingEapAkaSequenceNumber).value
    }

    override fun commitEapAkaSequenceNumber() {
        eapAkaSequenceNumber = pendingEapAkaSequenceNumber
    }

    override fun updateFromPairing(controllerId: Long, podId: Long, pairResult: PairResult) {
        this.controllerId = controllerId
        this.podId = podId
        ltk = pairResult.ltk
        msgSequenceNumber = pairResult.msgSeq
    }

    override fun updateFromVersionResponse(response: VersionResponse) {
        podStatus = response.podStatus
        firmwareVersion = SoftwareVersion(response.firmwareVersionMajor, response.firmwareVersionMinor, response.firmwareVersionInterim)
        bleVersion = SoftwareVersion(response.bleVersionMajor, response.bleVersionMinor, response.bleVersionInterim)
        lotNumber = response.lotNumber
        podSequenceNumber = response.podSequenceNumber
        lastStatusResponseReceived = System.currentTimeMillis()
    }

    override fun updateFromDefaultStatusResponse(response: DefaultStatusResponse) {
        podStatus = response.podStatus
        deliveryStatus = response.deliveryStatus
        totalPulsesDelivered = response.totalPulsesDelivered
        bolusPulsesRemaining = response.bolusPulsesRemaining
        reservoirPulsesRemaining = response.reservoirPulsesRemaining
        activeAlerts = response.activeAlerts
        minutesSinceActivation = response.minutesSinceActivation
        sequenceNumberOfLastProgrammingCommand = response.sequenceNumberOfLastProgrammingCommand
        lastStatusResponseReceived = System.currentTimeMillis()
    }

    override fun updateFromAlarmStatusResponse(response: AlarmStatusResponse) {
        podStatus = response.podStatus
        deliveryStatus = response.deliveryStatus
        totalPulsesDelivered = response.totalPulsesDelivered
        bolusPulsesRemaining = response.bolusPulsesRemaining
        reservoirPulsesRemaining = response.reservoirPulsesRemaining
        activeAlerts = response.activeAlerts
        minutesSinceActivation = response.minutesSinceActivation
        sequenceNumberOfLastProgrammingCommand = response.sequenceNumberOfLastProgrammingCommand
        alarmType = response.alarmType
        alarmTime = response.alarmTime
        occlusionAlarm = response.occlusionAlarm
        podStatusWhenAlarmOccurred = response.podStatusWhenAlarmOccurred
        rssi = response.rssi
        lastStatusResponseReceived = System.currentTimeMillis()
    }

    override fun reset() {
        bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
        connectionAttemptsCounter.set(0)
        successfulConnectionsCounter.set(0)
        bluetoothAddress = null
        controllerId = null
        podId = null
        ltk = null
        msgSequenceNumber = 1
        eapAkaSequenceNumber = 0
        pendingEapAkaSequenceNumber = 0
        basalProgram = null
        deliverySuspended = false
        lastBolusStartTime = null
        lastBolusRequestedUnits = null
        lastBolusDeliveredUnits = null
        activeTempBasalStartTime = null
        activeTempBasalRate = null
        activeTempBasalDurationMinutes = null
        pendingDoseCommand = null
        podStatus = null
        deliveryStatus = null
        firmwareVersion = null
        bleVersion = null
        lotNumber = null
        podSequenceNumber = null
        totalPulsesDelivered = null
        bolusPulsesRemaining = null
        reservoirPulsesRemaining = null
        activeAlerts = null
        minutesSinceActivation = null
        sequenceNumberOfLastProgrammingCommand = null
        lastStatusResponseReceived = null
        alarmType = null
        alarmTime = null
        occlusionAlarm = null
        podStatusWhenAlarmOccurred = null
        rssi = null
    }
}
