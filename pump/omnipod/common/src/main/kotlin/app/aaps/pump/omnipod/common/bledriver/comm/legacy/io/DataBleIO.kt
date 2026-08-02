package app.aaps.pump.omnipod.common.bledriver.comm.legacy.io

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.DataBleIO as DataBleIOInterface
import app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CharacteristicType
import app.aaps.pump.omnipod.common.bledriver.comm.legacy.callbacks.BleCommCallbacks
import java.util.concurrent.BlockingQueue

class DataBleIO(
    logger: AAPSLogger,
    characteristic: BluetoothGattCharacteristic,
    incomingPackets: BlockingQueue<ByteArray>,
    gatt: BluetoothGatt,
    bleCommCallbacks: BleCommCallbacks,
    // Omnipod 5 uses a different DATA characteristic (DATA_O5) than Dash's DATA - the
    // caller must pass the same type used to actually look up [characteristic] via
    // ServiceDiscoverer, or write-confirmation UUID matching in BleIO.sendAndConfirmPacket
    // will compare against the wrong UUID and reject every otherwise-successful write.
    type: CharacteristicType = CharacteristicType.DATA
) : BleIO(
    logger,
    characteristic,
    incomingPackets,
    gatt,
    bleCommCallbacks,
    type
), DataBleIOInterface
