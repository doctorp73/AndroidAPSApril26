package app.aaps.pump.omnipod.common.bledriver.comm.legacy.session

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.toHex
import app.aaps.pump.omnipod.common.bledriver.comm.Ids
import app.aaps.pump.omnipod.common.bledriver.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.ConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.FailedToConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CharacteristicType
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.session.BleConnection
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.callbacks.BleCommCallbacks
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.io.CmdBleIO
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.io.DataBleIO
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.io.IncomingPackets
import app.aaps.pump.omnipod.common.bledriver.comm.message.MessageIO
import app.aaps.pump.omnipod.common.bledriver.comm.session.Connected
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionState
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionWaitCondition
import app.aaps.pump.omnipod.common.bledriver.comm.session.DisconnectHandler
import app.aaps.pump.omnipod.common.bledriver.comm.session.EapSqn
import app.aaps.pump.omnipod.common.bledriver.comm.session.NotConnected
import app.aaps.pump.omnipod.common.bledriver.comm.session.STOP_CONNECTING_CHECK_INTERVAL_MS
import app.aaps.pump.omnipod.common.bledriver.comm.session.Session
import app.aaps.pump.omnipod.common.bledriver.comm.session.SessionEstablisher
import app.aaps.pump.omnipod.common.bledriver.comm.session.SessionKeys
import app.aaps.pump.omnipod.common.bledriver.comm.session.SessionNegotiationResynchronization
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodType
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager

/**
 * BLE GATT connection lifecycle and session establishment for Omnipod 5, parallel to
 * Dash's [Connection] rather than a modification of it - [Connection] is coupled
 * throughout to the much larger [app.aaps.pump.omnipod.common.bledriver.pod.state
 * .OmnipodDashPodStateManager] (full pod status surface: basal programs, alerts,
 * delivery status, etc.), which doesn't fit O5's connection-only [O5PodStateManager].
 *
 * Everything below the GATT-characteristic-selection level - [ServiceDiscoverer] (called
 * here with [PodType.OMNIPOD_5]), [CmdBleIO], [DataBleIO], [MessageIO],
 * [SessionEstablisher], [Session], [EnDecrypt] - is reused unmodified from the existing
 * Dash connection path, since none of it is actually Dash-specific once given the right
 * pod type and identity.
 */
class O5Connection(
    private val podDevice: BluetoothDevice,
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val context: Context,
    private val podState: O5PodStateManager
) : BleConnection, DisconnectHandler {

    private val incomingPackets = IncomingPackets()
    private val bleCommCallbacks = BleCommCallbacks(aapsLogger, incomingPackets, this)
    private var gattConnection: BluetoothGatt? = null

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?

    private var _connectionWaitCond: ConnectionWaitCondition? = null

    @Volatile
    override var session: Session? = null

    @Volatile
    override var msgIO: MessageIO? = null

    @Synchronized
    override fun connect(connectionWaitCond: ConnectionWaitCondition) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting (O5) connectionWaitCond=$connectionWaitCond")
        _connectionWaitCond = connectionWaitCond
        podState.connectionAttempts++
        podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.CONNECTING
        val autoConnect = false
        var gatt = gattConnection
        if (gatt == null) {
            gatt = podDevice.connectGatt(context, autoConnect, bleCommCallbacks, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                Thread.sleep(SLEEP_WHEN_FAILING_TO_CONNECT_GATT) // Do not retry too often
                throw FailedToConnectException("connectGatt() returned null")
            }
            gattConnection = gatt
        } else if (!gatt.connect()) {
            throw FailedToConnectException("connect() returned false")
        }
        val before = SystemClock.elapsedRealtime()
        if (waitForConnection(connectionWaitCond) !is Connected) {
            podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
            _connectionWaitCond = null
            throw FailedToConnectException(podDevice.address)
        }
        val waitedMs = SystemClock.elapsedRealtime() - before
        val timeoutMs = connectionWaitCond.timeoutMs
        if (timeoutMs != null) {
            var newTimeout = timeoutMs - waitedMs
            if (newTimeout < MIN_DISCOVERY_TIMEOUT_MS) {
                newTimeout = MIN_DISCOVERY_TIMEOUT_MS
            }
            connectionWaitCond.timeoutMs = newTimeout
        }
        podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.CONNECTED

        val discoverer = ServiceDiscoverer(aapsLogger, gatt, bleCommCallbacks, this)
        val discovered = discoverer.discoverServices(connectionWaitCond, PodType.OMNIPOD_5)
        val cmdBleIO = CmdBleIO(
            aapsLogger,
            discovered.getValue(CharacteristicType.CMD),
            incomingPackets.cmdQueue,
            gatt,
            bleCommCallbacks
        )
        val dataBleIO = DataBleIO(
            aapsLogger,
            discovered.getValue(CharacteristicType.DATA),
            incomingPackets.dataQueue,
            gatt,
            bleCommCallbacks,
            CharacteristicType.DATA_O5
        )
        msgIO = MessageIO(aapsLogger, cmdBleIO, dataBleIO, PodType.OMNIPOD_5)
        cmdBleIO.hello()
        cmdBleIO.readyToRead()
        dataBleIO.readyToRead()
        _connectionWaitCond = null
    }

    @Synchronized
    override fun disconnect(closeGatt: Boolean) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnecting (O5) closeGatt=$closeGatt")
        if (!closeGatt && gattConnection != null) {
            gattConnection?.disconnect()
            podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
        } else {
            gattConnection?.close()
            bleCommCallbacks.resetConnection()
            gattConnection = null
            session = null
            msgIO = null
            podState.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.DISCONNECTED
        }
    }

    private fun waitForConnection(connectionWaitCond: ConnectionWaitCondition): ConnectionState {
        aapsLogger.debug(LTag.PUMPBTCOMM, "waitForConnection (O5) connectionWaitCond=$connectionWaitCond")
        try {
            connectionWaitCond.timeoutMs?.let {
                bleCommCallbacks.waitForConnection(it)
            }
            val startWaiting = System.currentTimeMillis()
            connectionWaitCond.stopConnection?.let {
                while (!bleCommCallbacks.waitForConnection(STOP_CONNECTING_CHECK_INTERVAL_MS)) {
                    if (it.count == 0L) {
                        throw ConnectException("stopConnecting called")
                    }
                    val secondsElapsed = (System.currentTimeMillis() - startWaiting) / 1000
                    if (secondsElapsed > MAX_WAIT_FOR_CONNECTION_SECONDS) {
                        throw ConnectException("connection timeout")
                    }
                }
            }
        } catch (e: InterruptedException) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Interrupted while waiting for connection (O5)")
        }
        return connectionState()
    }

    override fun connectionState(): ConnectionState {
        val connectionState = bluetoothManager?.getConnectionState(podDevice, BluetoothProfile.GATT)
        aapsLogger.debug(LTag.PUMPBTCOMM, "GATT connection state (O5): $connectionState")
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return NotConnected
        }
        return Connected
    }

    /**
     * [ids] here must be built via [Ids.forController] using the pod's certificate-derived
     * controller id and its own O5 pod id - O5 has no [Ids] constructor of its own since it
     * has no Dash-style pod state manager to derive one from (see [Ids]'s class doc).
     */
    override fun establishSession(ltk: ByteArray, msgSeq: Byte, ids: Ids, eapSqn: ByteArray): EapSqn? {
        val mIO = msgIO ?: throw ConnectException("Connection lost")

        val eapAkaExchanger = SessionEstablisher(aapsLogger, config, mIO, ltk, eapSqn, ids, msgSeq)
        return when (val keys = eapAkaExchanger.negotiateSessionKeys()) {
            is SessionNegotiationResynchronization -> {
                if (config.DEBUG) {
                    aapsLogger.info(LTag.PUMPCOMM, "EAP AKA resynchronization (O5): ${keys.synchronizedEapSqn}")
                }
                keys.synchronizedEapSqn
            }

            is SessionKeys                         -> {
                if (config.DEBUG) {
                    aapsLogger.info(LTag.PUMPCOMM, "CK (O5): ${keys.ck.toHex()}")
                    aapsLogger.info(LTag.PUMPCOMM, "msgSequenceNumber (O5): ${keys.msgSequenceNumber}")
                    aapsLogger.info(LTag.PUMPCOMM, "Nonce (O5): ${keys.nonce}")
                }
                val enDecrypt = EnDecrypt(aapsLogger, keys.nonce, keys.ck)
                session = Session(aapsLogger, mIO, ids, sessionKeys = keys, enDecrypt = enDecrypt)
                null
            }
        }
    }

    // This will be called from a different thread !!!
    override fun onConnectionLost(status: Int) {
        aapsLogger.info(LTag.PUMPBTCOMM, "Lost connection (O5) with status: $status")
        _connectionWaitCond?.stopConnection?.let {
            if (it.count > 0) {
                it.countDown()
            }
        }
        disconnect(true)
    }

    companion object {
        const val MIN_DISCOVERY_TIMEOUT_MS = 10000L
        const val MAX_WAIT_FOR_CONNECTION_SECONDS = Constants.PUMP_MAX_CONNECTION_TIME_IN_SECONDS + 10
        const val SLEEP_WHEN_FAILING_TO_CONNECT_GATT = 10000L
    }
}
