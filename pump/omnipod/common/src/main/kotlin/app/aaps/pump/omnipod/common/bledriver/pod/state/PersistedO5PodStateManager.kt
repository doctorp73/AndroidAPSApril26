package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.comm.pair.PairResult
import app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodStatus
import app.aaps.pump.omnipod.common.bledriver.pod.definition.SoftwareVersion
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.VersionResponse
import app.aaps.pump.omnipod.common.keys.O5StringNonPreferenceKey
import com.google.gson.Gson
import java.io.Serializable
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [O5PodStateManager] persisted across app restarts, mirroring
 * [OmnipodDashPodStateManagerImpl]'s Gson + [Preferences] store/load pattern exactly -
 * just for O5's smaller state surface (see [O5PodStateManager]'s class doc for why).
 */
@Singleton
class PersistedO5PodStateManager @Inject constructor(
    private val logger: AAPSLogger,
    private val preferences: Preferences
) : O5PodStateManager {

    private val gson = Gson()

    private var _podState: PodState? = null

    /** Internal (rather than private) to allow unit testing within this module.
     *  Lazily deserialized on first access, matching Dash's rationale: keep Gson
     *  reflection off the main thread during app startup (Dagger constructs this
     *  @Singleton eagerly). */
    internal var podState: PodState
        get() = _podState ?: synchronized(this) { _podState ?: load().also { _podState = it } }
        set(value) {
            synchronized(this) { _podState = value }
        }

    override var bluetoothConnectionState: O5PodStateManager.BluetoothConnectionState
        get() = podState.bluetoothConnectionState
        set(value) {
            podState.bluetoothConnectionState = value
            store()
        }

    override var connectionAttempts: Int
        get() = podState.connectionAttempts
        set(value) {
            podState.connectionAttempts = value
            store()
        }

    override var successfulConnections: Int
        get() = podState.successfulConnections
        set(value) {
            podState.successfulConnections = value
            store()
        }

    override var bluetoothAddress: String?
        get() = podState.bluetoothAddress
        set(value) {
            podState.bluetoothAddress = value
            store()
        }

    override var controllerId: Long?
        get() = podState.controllerId
        set(value) {
            podState.controllerId = value
            store()
        }

    override var podId: Long?
        get() = podState.podId
        set(value) {
            podState.podId = value
            store()
        }

    override var ltk: ByteArray?
        get() = podState.ltk
        set(value) {
            podState.ltk = value
            store()
        }

    override var msgSequenceNumber: Byte
        get() = podState.msgSequenceNumber
        set(value) {
            podState.msgSequenceNumber = value
            store()
        }

    override var eapAkaSequenceNumber: Long
        get() = podState.eapAkaSequenceNumber
        set(value) {
            podState.eapAkaSequenceNumber = value
            store()
        }

    private var pendingEapAkaSequenceNumber: Long = 0

    override val podStatus: PodStatus? get() = podState.podStatus
    override val deliveryStatus: DeliveryStatus? get() = podState.deliveryStatus
    override val firmwareVersion: SoftwareVersion? get() = podState.firmwareVersion
    override val bleVersion: SoftwareVersion? get() = podState.bleVersion
    override val lotNumber: Long? get() = podState.lotNumber
    override val podSequenceNumber: Long? get() = podState.podSequenceNumber
    override val totalPulsesDelivered: Short? get() = podState.totalPulsesDelivered
    override val bolusPulsesRemaining: Short? get() = podState.bolusPulsesRemaining
    override val reservoirPulsesRemaining: Short? get() = podState.reservoirPulsesRemaining
    override val activeAlerts: EnumSet<AlertType>? get() = podState.activeAlerts
    override val minutesSinceActivation: Short? get() = podState.minutesSinceActivation
    override val sequenceNumberOfLastProgrammingCommand: Short? get() = podState.sequenceNumberOfLastProgrammingCommand
    override val lastStatusResponseReceived: Long? get() = podState.lastStatusResponseReceived

    override fun increaseEapAkaSequenceNumber(): ByteArray {
        pendingEapAkaSequenceNumber = eapAkaSequenceNumber + 1
        return EapSqn(pendingEapAkaSequenceNumber).value
    }

    override fun commitEapAkaSequenceNumber() {
        eapAkaSequenceNumber = pendingEapAkaSequenceNumber
        store()
    }

    override fun updateFromPairing(controllerId: Long, podId: Long, pairResult: PairResult) {
        podState.controllerId = controllerId
        podState.podId = podId
        podState.ltk = pairResult.ltk
        podState.msgSequenceNumber = pairResult.msgSeq
        store()
    }

    override fun updateFromVersionResponse(response: VersionResponse) {
        podState.podStatus = response.podStatus
        podState.firmwareVersion = SoftwareVersion(
            response.firmwareVersionMajor, response.firmwareVersionMinor, response.firmwareVersionInterim
        )
        podState.bleVersion = SoftwareVersion(
            response.bleVersionMajor, response.bleVersionMinor, response.bleVersionInterim
        )
        podState.lotNumber = response.lotNumber
        podState.podSequenceNumber = response.podSequenceNumber
        podState.lastStatusResponseReceived = System.currentTimeMillis()
        store()
    }

    override fun updateFromDefaultStatusResponse(response: DefaultStatusResponse) {
        podState.podStatus = response.podStatus
        podState.deliveryStatus = response.deliveryStatus
        podState.totalPulsesDelivered = response.totalPulsesDelivered
        podState.bolusPulsesRemaining = response.bolusPulsesRemaining
        podState.reservoirPulsesRemaining = response.reservoirPulsesRemaining
        podState.activeAlerts = response.activeAlerts
        podState.minutesSinceActivation = response.minutesSinceActivation
        podState.sequenceNumberOfLastProgrammingCommand = response.sequenceNumberOfLastProgrammingCommand
        podState.lastStatusResponseReceived = System.currentTimeMillis()
        store()
    }

    override fun reset() {
        podState = PodState()
        store()
    }

    private fun store() {
        try {
            val cleanPodState = podState.copy(ltk = null) // do not log ltk
            logger.debug(LTag.PUMPCOMM, "Storing O5 Pod state: ${gson.toJson(cleanPodState)}")

            val serialized = gson.toJson(podState)
            preferences.put(O5StringNonPreferenceKey.PodState, serialized)
        } catch (ex: Exception) {
            logger.error(LTag.PUMPCOMM, "Failed to store O5 Pod state", ex)
        }
    }

    private fun load(): PodState {
        if (preferences.getIfExists(O5StringNonPreferenceKey.PodState) != null) {
            try {
                return gson.fromJson(preferences.get(O5StringNonPreferenceKey.PodState), PodState::class.java)
            } catch (ex: Exception) {
                logger.error(LTag.PUMPCOMM, "Failed to deserialize O5 Pod state", ex)
            }
        }
        return PodState()
    }

    /** Internal (rather than private) to allow unit testing within this module. */
    internal data class PodState(
        var bluetoothConnectionState: O5PodStateManager.BluetoothConnectionState =
            O5PodStateManager.BluetoothConnectionState.DISCONNECTED,
        var connectionAttempts: Int = 0,
        var successfulConnections: Int = 0,
        var bluetoothAddress: String? = null,
        var controllerId: Long? = null,
        var podId: Long? = null,
        var ltk: ByteArray? = null,
        var msgSequenceNumber: Byte = 1,
        var eapAkaSequenceNumber: Long = 0,
        var podStatus: PodStatus? = null,
        var deliveryStatus: DeliveryStatus? = null,
        var firmwareVersion: SoftwareVersion? = null,
        var bleVersion: SoftwareVersion? = null,
        var lotNumber: Long? = null,
        var podSequenceNumber: Long? = null,
        var totalPulsesDelivered: Short? = null,
        var bolusPulsesRemaining: Short? = null,
        var reservoirPulsesRemaining: Short? = null,
        var activeAlerts: EnumSet<AlertType>? = null,
        var minutesSinceActivation: Short? = null,
        var sequenceNumberOfLastProgrammingCommand: Short? = null,
        var lastStatusResponseReceived: Long? = null
    ) : Serializable
}
