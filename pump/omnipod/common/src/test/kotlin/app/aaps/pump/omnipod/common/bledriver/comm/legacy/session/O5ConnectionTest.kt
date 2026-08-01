package app.aaps.pump.omnipod.common.bledriver.comm.legacy.session

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.pump.omnipod.common.bledriver.comm.Id
import app.aaps.pump.omnipod.common.bledriver.comm.Ids
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.ConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.exceptions.FailedToConnectException
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CharacteristicType
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.callbacks.BleCommCallbacks
import app.aaps.pump.omnipod.common.bledriver.comm.session.Connected
import app.aaps.pump.omnipod.common.bledriver.comm.session.ConnectionWaitCondition
import app.aaps.pump.omnipod.common.bledriver.comm.session.NotConnected
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.bledriver.pod.util.P256KeyGenerator
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [O5Connection]'s GATT lifecycle. Unlike [app.aaps.pump.omnipod.common.bledriver.comm
 * .O5PairingCoordinatorTest], which stopped at the two guard checks before any BLE work,
 * this suite drives the real [connect] path through GATT connect, service discovery and
 * the hello() handshake by mocking the Android BLE framework classes directly
 * (already-proven-safe technique this session: Mockito's inline mock maker mocks these
 * final/framework classes fine). It stops short of `readyToRead()` - see the comment above
 * the happy-path test for why that step is a genuine environment boundary, not a gap in
 * this suite's mocking.
 *
 * The key simplification: [BleCommCallbacks]'s blocking waits ([BleCommCallbacks
 * .waitForConnection]/[waitForServiceDiscovery]/[confirmWrite]) are backed by a
 * [java.util.concurrent.CountDownLatch]/[java.util.concurrent.BlockingQueue] that don't
 * care *which thread* counts them down - so the real [BleCommCallbacks] instance
 * [O5Connection] constructs internally (captured here via the [BluetoothDevice.connectGatt]
 * argument) can have its callback methods invoked *synchronously inside* the `thenAnswer`
 * for the GATT call that would normally trigger them (`connectGatt`, `discoverServices`,
 * `writeCharacteristic`), so every blocking wait finds its latch/queue already satisfied
 * and returns immediately - no real waiting, no background thread, no flaky timing.
 */
class O5ConnectionTest {

    private val aapsLogger = AAPSLoggerTest()
    private val config = mock<Config>()
    private val context = mock<Context>()
    private val podState = mock<O5PodStateManager>()
    private val podDevice = mock<BluetoothDevice>()
    private val bluetoothManager = mock<BluetoothManager>()
    private val p256KeyGenerator = mock<P256KeyGenerator>()

    private fun newConnection(bluetoothServiceAvailable: Boolean = true): O5Connection {
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE))
            .thenReturn(if (bluetoothServiceAvailable) bluetoothManager else null)
        return O5Connection(podDevice, aapsLogger, config, context, podState, p256KeyGenerator)
    }

    // ---- connectionState() --------------------------------------------------------------

    @Test fun `connectionState reports Connected when the GATT profile is connected`() {
        whenever(bluetoothManager.getConnectionState(podDevice, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)

        assertThat(newConnection().connectionState()).isInstanceOf(Connected::class.java)
    }

    @Test fun `connectionState reports NotConnected when the GATT profile is not connected`() {
        whenever(bluetoothManager.getConnectionState(podDevice, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_DISCONNECTED)

        assertThat(newConnection().connectionState()).isInstanceOf(NotConnected::class.java)
    }

    @Test fun `connectionState reports NotConnected when the BluetoothManager system service is unavailable`() {
        assertThat(newConnection(bluetoothServiceAvailable = false).connectionState())
            .isInstanceOf(NotConnected::class.java)
    }

    // ---- establishSession() --------------------------------------------------------------

    @Test fun `establishSession throws ConnectException when there is no active BLE connection`() {
        val ids = Ids.forController(Id.fromInt(1), Id.fromInt(2))

        assertThrows(ConnectException::class.java) {
            newConnection().establishSession(ByteArray(16), 0, ids, ByteArray(6))
        }
    }

    // ---- disconnect() ----------------------------------------------------------------------

    @Test fun `disconnect is a no-op when there is no GATT connection yet`() {
        newConnection().disconnect(closeGatt = true)
        newConnection().disconnect(closeGatt = false)
    }

    @Test fun `disconnect(closeGatt=false) disconnects the GATT object but keeps it for reuse`() {
        val gatt = mock<BluetoothGatt>()
        val connection = connectionWithGattConnectionEstablished(gatt)

        connection.disconnect(closeGatt = false)

        verify(gatt).disconnect()
        verify(gatt, never()).close()
    }

    @Test fun `disconnect(closeGatt=true) closes and clears the GATT object and resets the session`() {
        val gatt = mock<BluetoothGatt>()
        val connection = connectionWithGattConnectionEstablished(gatt)

        connection.disconnect(closeGatt = true)

        verify(gatt).close()
        // A second disconnect(true) must now hit the "no GATT connection" no-op path, not
        // re-close an already-cleared gattConnection.
        connection.disconnect(closeGatt = true)
        verify(gatt, times(1)).close()
    }

    /** Drives [O5Connection.connect] just far enough to populate its internal
     *  `gattConnection` (set immediately after a successful [BluetoothDevice.connectGatt])
     *  then lets it fail at the connection-wait step, so [O5Connection.disconnect] has a
     *  real (mocked) GATT object to act on without needing the full handshake below. */
    private fun connectionWithGattConnectionEstablished(gatt: BluetoothGatt): O5Connection {
        whenever(
            podDevice.connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        ).thenReturn(gatt)
        whenever(bluetoothManager.getConnectionState(podDevice, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_DISCONNECTED)
        val connection = newConnection()
        assertThrows(FailedToConnectException::class.java) {
            connection.connect(ConnectionWaitCondition(timeoutMs = 10L))
        }
        return connection
    }

    // ---- connect() failure paths -----------------------------------------------------------

    @Test fun `connect throws FailedToConnectException when the connection wait times out`() {
        val gatt = mock<BluetoothGatt>()
        whenever(
            podDevice.connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        ).thenReturn(gatt)
        whenever(bluetoothManager.getConnectionState(podDevice, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_DISCONNECTED)

        assertThrows(FailedToConnectException::class.java) {
            newConnection().connect(ConnectionWaitCondition(timeoutMs = 10L))
        }
    }

    // ---- connect() happy path --------------------------------------------------------------
    //
    // Full end-to-end coverage (through readyToRead()) is not reachable in this pure-JVM
    // unit test: BleIO.readyToRead() reads the real, unmocked static field
    // BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, and this project's unit-test
    // android.jar stub only strips *method bodies* - non-primitive static fields like this
    // byte[] never run their real initializer, so the field is genuinely null here (not a
    // mocking gap: it's a real `getstatic` read of production code, which Mockito cannot
    // intercept - only method calls on mock() objects can be stubbed). Kotlin's own
    // null-safety check on that platform-type field then throws NPE
    // ("ENABLE_INDICATION_VALUE must not be null"). This is an environment boundary, not a
    // code defect - the same call would work fine against a real device or an
    // instrumented/Robolectric test, neither in scope here (no on-device testing without
    // explicit request per project rules). This test therefore covers everything that *is*
    // reachable - GATT connect, service discovery, and the hello() write/confirm cycle -
    // and pins the NPE as the expected, documented stopping point.

    @Test fun `connect completes GATT connection, service discovery and hello() before hitting the readyToRead() environment boundary`() {
        val gatt = mock<BluetoothGatt>()
        val service = mock<BluetoothGattService>()
        val cmdChar = mock<BluetoothGattCharacteristic>()
        val dataChar = mock<BluetoothGattCharacteristic>()
        val cmdDescriptor = mock<BluetoothGattDescriptor>()
        val dataDescriptor = mock<BluetoothGattDescriptor>()
        var bleCommCallbacks: BleCommCallbacks? = null

        whenever(bluetoothManager.getConnectionState(podDevice, BluetoothProfile.GATT))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)

        // connectGatt: capture the real BleCommCallbacks instance O5Connection builds
        // internally, and fire the connected-state callback synchronously so the
        // subsequent blocking wait finds its latch already counted down.
        whenever(
            podDevice.connectGatt(eq(context), eq(false), any(), eq(BluetoothDevice.TRANSPORT_LE))
        ).thenAnswer { invocation ->
            val callbacks = invocation.getArgument<BleCommCallbacks>(2)
            bleCommCallbacks = callbacks
            callbacks.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            gatt
        }

        // requestMtu(): fire onMtuChanged synchronously, echoing back whatever MTU was
        // actually requested (granted in full) - same reason as the other GATT calls
        // below, avoids blocking on O5Connection's real MTU_NEGOTIATION_TIMEOUT_MS wait.
        whenever(gatt.requestMtu(any())).thenAnswer { invocation ->
            val requestedMtu = invocation.getArgument<Int>(0)
            bleCommCallbacks!!.onMtuChanged(gatt, requestedMtu, BluetoothGatt.GATT_SUCCESS)
            true
        }

        // discoverServices: fire the services-discovered callback synchronously for the
        // same reason - avoids O5Connection's 10s MIN_DISCOVERY_TIMEOUT_MS floor turning
        // this into a slow test.
        whenever(gatt.discoverServices()).thenAnswer {
            bleCommCallbacks!!.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            true
        }
        whenever(gatt.getService(any())).thenReturn(service)
        whenever(service.getCharacteristic(CharacteristicType.CMD.uuid)).thenReturn(cmdChar)
        whenever(service.getCharacteristic(CharacteristicType.DATA_O5.uuid)).thenReturn(dataChar)

        // hello(): CmdBleIO.sendAndConfirmPacket sets the payload via setValue() then
        // blocks on confirmWrite() - fire onCharacteristicWrite synchronously from
        // writeCharacteristic() so the queue is already populated by the time confirmWrite
        // polls it, echoing back whatever payload was actually set (captured, not
        // hand-computed, since BleCommandHello's exact bytes aren't this test's concern).
        var cmdCharValue: ByteArray? = null
        whenever(cmdChar.uuid).thenReturn(CharacteristicType.CMD.uuid)
        whenever(cmdChar.setValue(any<ByteArray>())).thenAnswer { invocation ->
            cmdCharValue = invocation.getArgument(0)
            true
        }
        whenever(cmdChar.value).thenAnswer { cmdCharValue }
        whenever(cmdChar.descriptors).thenReturn(listOf(cmdDescriptor))
        whenever(gatt.writeCharacteristic(cmdChar)).thenAnswer {
            bleCommCallbacks!!.onCharacteristicWrite(gatt, cmdChar, BluetoothGatt.GATT_SUCCESS)
            true
        }

        // readyToRead() gets as far as writing the indication descriptor (mocked to
        // succeed) before reaching the unreachable step documented above - reading the
        // real, null ENABLE_INDICATION_VALUE field as confirmWrite()'s expected-payload
        // argument, which Kotlin's own null-safety check on that parameter rejects.
        whenever(gatt.setCharacteristicNotification(any(), any())).thenReturn(true)
        whenever(dataChar.descriptors).thenReturn(listOf(dataDescriptor))
        whenever(gatt.writeDescriptor(any())).thenReturn(true)

        val connection = newConnection()
        val thrown = assertThrows(NullPointerException::class.java) {
            connection.connect(ConnectionWaitCondition(timeoutMs = 50L))
        }
        assertThat(thrown).hasMessageThat().contains("ENABLE_INDICATION_VALUE")

        // Everything up to that boundary genuinely ran: GATT connect, service discovery,
        // and a real, confirmed hello() write - not just "didn't throw yet".
        verify(gatt).discoverServices()
        verify(gatt).writeCharacteristic(cmdChar)
        assertThat(cmdCharValue).isNotNull()
        assertThat(cmdCharValue).isNotEmpty()
        assertThat(connection.msgIO).isNotNull()
    }
}
