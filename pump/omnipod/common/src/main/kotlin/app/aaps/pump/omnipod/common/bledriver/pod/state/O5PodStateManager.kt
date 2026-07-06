package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.pump.omnipod.common.bledriver.comm.pair.PairResult
import app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal persisted/in-memory state needed to connect to and establish a session with an
 * Omnipod 5 pod. Deliberately much smaller than [OmnipodDashPodStateManager] - it covers
 * only connection lifecycle and pairing/session state, not the full pod status surface
 * (basal programs, alerts, delivery status, etc.), since that belongs to a future O5
 * command-layer/pod-status subsystem, not connection plumbing.
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

    var eapAkaSequenceNumber: Long

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
 * Simple in-memory [O5PodStateManager]. Not yet persisted across process restarts -
 * wiring this up to actual storage (Room/SharedPreferences, matching
 * [OmnipodDashPodStateManager]'s persistence approach) is a separate follow-up; this is
 * enough to support connecting to and pairing with a pod within a single app session.
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
    }
}
