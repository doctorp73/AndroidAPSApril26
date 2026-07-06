package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.comm.pair.PairResult
import app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn
import app.aaps.pump.omnipod.common.keys.O5StringNonPreferenceKey
import com.google.gson.Gson
import java.io.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [O5PodStateManager] persisted across app restarts, mirroring
 * [OmnipodDashPodStateManagerImpl]'s Gson + [Preferences] store/load pattern exactly -
 * just for O5's much smaller state surface (see [O5PodStateManager]'s class doc for why
 * it's smaller than Dash's).
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
        var eapAkaSequenceNumber: Long = 0
    ) : Serializable
}
