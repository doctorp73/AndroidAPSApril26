package app.aaps.pump.omnipod.common

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.event.PodEvent
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.AlertType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.common.queue.command.CommandDeliverBasalCorrection
import app.aaps.pump.omnipod.common.queue.command.CommandDisableSuspendAlerts
import app.aaps.pump.omnipod.common.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.common.queue.command.CommandPairNewPod
import app.aaps.pump.omnipod.common.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.common.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.common.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.common.queue.command.CommandSuspendDelivery
import app.aaps.pump.omnipod.common.queue.command.CommandUpdateAlertConfiguration
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Covers the safety-relevant gating logic and simple property surface of [O5PumpPlugin] -
 * not exhaustive RxJava-flow coverage (no other pump plugin in this codebase unit-tests its
 * full blocking-RxJava dosing flow either, see [app.aaps.pump.omnipod.eros
 * .OmnipodErosPumpPluginTest] for the closest precedent). Focus: the gates that must reject
 * *before* any BLE command is sent (reservoir check, bolus-already-in-progress check,
 * no-op temp-basal-cancel), the `isBusy()`/`isConnected()`/`isInitialized()` state
 * transitions (the exact thing that caused the queue-deadlock bug during development), and
 * `executeCustomCommand` routing.
 */
class O5PumpPluginTest : TestBaseWithProfile() {

    @Mock lateinit var bleManager: O5BleManager
    @Mock lateinit var podStateManager: O5PodStateManager
    @Mock lateinit var pumpSync: PumpSync
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var bolusProgressData: BolusProgressData
    @Mock lateinit var protectionCheck: ProtectionCheck
    @Mock lateinit var blePreCheck: BlePreCheck

    private lateinit var plugin: O5PumpPlugin

    @BeforeEach
    fun setup() {
        plugin = O5PumpPlugin(
            aapsLogger, rh, preferences, commandQueue, bleManager, podStateManager, pumpSync,
            notificationManager, pumpEnactResultProvider, bolusProgressData, protectionCheck, blePreCheck, config
        )
        // TestBaseWithProfile's rh mock only stubs a handful of generic strings (ok/error/
        // mgdl/mmol) - these are the ones O5PumpPlugin itself uses for PumpEnactResult
        // comments/warnings, needed so String.format(rh.gs(id), ...) doesn't NPE on a null
        // template in the gs(Int, String) etc. overload stubs.
        whenever(rh.gs(R.string.omnipod_5_error_not_enough_insulin)).thenReturn("Not enough insulin")
        whenever(rh.gs(R.string.omnipod_5_error_bolus_already_in_progress)).thenReturn("Bolus already in progress")
        whenever(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported)).thenReturn("Extended bolus not supported")
        whenever(rh.gs(R.string.omnipod_common_error_unsupported_custom_command)).thenReturn("Unsupported custom command: %1\$s")
        whenever(rh.gs(R.string.omnipod_5_error_no_active_profile)).thenReturn("No active profile")
        whenever(rh.gs(R.string.omnipod_5_error_no_active_alerts)).thenReturn("No active alerts")
    }

    // -- isBusy / isConnected / isInitialized (the exact bug class already hit once) -------

    @Test
    fun `isBusy is false when activation has not started - pairing must never be blocked`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.NOT_STARTED)

        assertThat(plugin.isBusy()).isFalse()
    }

    @Test
    fun `isBusy is true while activation is in progress`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.GOT_POD_VERSION)

        assertThat(plugin.isBusy()).isTrue()
    }

    @Test
    fun `isBusy is false once activation is completed`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)

        assertThat(plugin.isBusy()).isFalse()
    }

    @Test
    fun `isInitialized is true only when activation is fully completed`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        assertThat(plugin.isInitialized()).isTrue()

        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.PRIME_COMPLETED)
        assertThat(plugin.isInitialized()).isFalse()
    }

    @Test
    fun `isConnected is true when unpaired - nothing to connect to yet`() {
        whenever(podStateManager.ltk).thenReturn(null)

        assertThat(plugin.isConnected()).isTrue()
    }

    @Test
    fun `isConnected reflects bluetoothConnectionState once paired`() {
        whenever(podStateManager.ltk).thenReturn(byteArrayOf(1))
        whenever(podStateManager.bluetoothConnectionState).thenReturn(O5PodStateManager.BluetoothConnectionState.CONNECTED)
        assertThat(plugin.isConnected()).isTrue()

        whenever(podStateManager.bluetoothConnectionState).thenReturn(O5PodStateManager.BluetoothConnectionState.DISCONNECTED)
        assertThat(plugin.isConnected()).isFalse()
    }

    // -- deliverTreatment: guards that must reject before any BLE command is sent ----------

    @Test
    fun `deliverTreatment rejects carbs`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 5.0; it.insulin = 1.0 }) }
        }
    }

    @Test
    fun `deliverTreatment rejects non-positive insulin`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 0.0 }) }
        }
    }

    @Test
    fun `deliverTreatment rejects a bolus larger than the reservoir without sending any command`() {
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(20) // 20 * 0.05 = 1.0 U
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(podStateManager.lastBolusStartTime).thenReturn(null)
        whenever(podStateManager.lastBolusRequestedUnits).thenReturn(null)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)

        val result = runBlocking {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 5.0 })
        }

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        assertThat(result.bolusDelivered).isEqualTo(0.0)
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `deliverTreatment rejects when a bolus is already delivering without sending any command`() {
        whenever(podStateManager.reservoirPulsesRemaining).thenReturn(2000) // plenty
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(podStateManager.lastBolusStartTime).thenReturn(null)
        whenever(podStateManager.lastBolusRequestedUnits).thenReturn(null)
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BOLUS_AND_BASAL_ACTIVE)

        val result = runBlocking {
            plugin.deliverTreatment(DetailedBolusInfo().also { it.carbs = 0.0; it.insulin = 1.0 })
        }

        assertThat(result.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    // -- temp basal --------------------------------------------------------------------------

    @Test
    fun `setTempBasalPercent is unsupported`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { plugin.setTempBasalPercent(80, 30, false, PumpSync.TemporaryBasalType.NORMAL) }
        }
    }

    @Test
    fun `cancelTempBasal is a no-op when nothing is running - never touches the pod`() {
        whenever(podStateManager.deliveryStatus).thenReturn(DeliveryStatus.BASAL_ACTIVE)

        val result = runBlocking {
            whenever(pumpSync.expectedPumpState()).thenReturn(
                PumpSync.PumpState(temporaryBasal = null, extendedBolus = null, bolus = null, profile = null, serialNumber = "")
            )
            plugin.cancelTempBasal(false)
        }

        assertThat(result.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    // -- executeCustomCommand routing ---------------------------------------------------------

    @Test
    fun `executeCustomCommand pairs a new pod for CommandPairNewPod`() {
        whenever(bleManager.pairNewPod()).thenReturn(Observable.empty<PodEvent>())

        val result = plugin.executeCustomCommand(CommandPairNewPod())

        assertThat(result).isNotNull()
        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).pairNewPod()
    }

    @Test
    fun `executeCustomCommand rejects an unrecognized custom command`() {
        val unknown = object : CustomCommand {
            override val statusDescription = "UNKNOWN"
        }

        val result = plugin.executeCustomCommand(unknown)

        assertThat(result).isNotNull()
        assertThat(result!!.success).isFalse()
        assertThat(result.enacted).isFalse()
    }

    @Test
    fun `executeCustomCommand deactivates the pod for CommandDeactivatePod`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandDeactivatePod())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(bleManager).removeBond()
        verify(podStateManager).reset()
    }

    @Test
    fun `executeCustomCommand silences alerts for CommandSilenceAlerts when there are active alerts`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.activeAlerts).thenReturn(java.util.EnumSet.of(AlertType.LOW_RESERVOIR))
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandSilenceAlerts())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand rejects CommandSilenceAlerts when there are no active alerts`() {
        whenever(podStateManager.activeAlerts).thenReturn(null)

        val result = plugin.executeCustomCommand(CommandSilenceAlerts())

        assertThat(result!!.success).isFalse()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand suspends delivery for CommandSuspendDelivery`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandSuspendDelivery())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).deliverySuspended = true
    }

    @Test
    fun `executeCustomCommand plays a test beep for CommandPlayTestBeep`() {
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandPlayTestBeep())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand for CommandResumeDelivery fails cleanly when there is no active profile`() {
        runBlocking {
            whenever(pumpSync.expectedPumpState()).thenReturn(
                PumpSync.PumpState(temporaryBasal = null, extendedBolus = null, bolus = null, profile = null, serialNumber = "")
            )

            val result = plugin.executeCustomCommand(CommandResumeDelivery())

            assertThat(result!!.success).isFalse()
            assertThat(result.enacted).isFalse()
            verify(bleManager, never()).sendCommand(any(), any())
        }
    }

    @Test
    fun `executeCustomCommand for CommandHandleTimeChange fails cleanly when there is no active profile`() {
        runBlocking {
            whenever(pumpSync.expectedPumpState()).thenReturn(
                PumpSync.PumpState(temporaryBasal = null, extendedBolus = null, bolus = null, profile = null, serialNumber = "")
            )

            val result = plugin.executeCustomCommand(CommandHandleTimeChange(true))

            assertThat(result!!.success).isFalse()
            assertThat(result.enacted).isFalse()
            verify(bleManager, never()).sendCommand(any(), any())
        }
    }

    // -- simple property surface --------------------------------------------------------------

    @Test
    fun `manufacturer, model, and DST support are fixed`() {
        assertThat(plugin.manufacturer()).isEqualTo(ManufacturerType.Insulet)
        assertThat(plugin.model()).isEqualTo(PumpType.OMNIPOD_5)
        assertThat(plugin.canHandleDST()).isFalse()
        assertThat(plugin.isFakingTempsByExtendedBoluses).isFalse()
    }

    @Test
    fun `serialNumber falls back to a placeholder before pairing`() {
        whenever(podStateManager.podId).thenReturn(null)
        assertThat(plugin.serialNumber()).isEqualTo("O5-unpaired")

        whenever(podStateManager.podId).thenReturn(4242L)
        assertThat(plugin.serialNumber()).isEqualTo("4242")
    }

    @Test
    fun `extended bolus is unsupported`() {
        val deliver = runBlocking { plugin.setExtendedBolus(1.0, 60) }
        assertThat(deliver.success).isFalse()

        val cancel = runBlocking { plugin.cancelExtendedBolus() }
        assertThat(cancel.success).isFalse()
    }

    // -- isThisProfileSet early-outs ----------------------------------------------------------

    @Test
    fun `isThisProfileSet is true before pairing - prevents premature basal-set attempts`() {
        whenever(podStateManager.ltk).thenReturn(null)

        assertThat(plugin.isThisProfileSet(mock())).isTrue()
    }

    @Test
    fun `isThisProfileSet is false while delivery is suspended - a set is genuinely needed`() {
        whenever(podStateManager.ltk).thenReturn(byteArrayOf(1))
        whenever(podStateManager.deliverySuspended).thenReturn(true)

        assertThat(plugin.isThisProfileSet(mock())).isFalse()
    }

    // -- alert config sync / suspend-alert silencing / basal-drift correction --------------
    // (the 3 previously-dead command classes wired this session)

    @Test
    fun `executeCustomCommand rejects CommandUpdateAlertConfiguration when settings already match`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        // Every OmnipodBooleanPreferenceKey/OmnipodIntPreferenceKey read below is unstubbed on
        // the mock preferences, i.e. Mockito's false/0 defaults - matching this exactly proves
        // the comparison is real, not incidentally always-different.
        whenever(podStateManager.syncedAlertSettings).thenReturn(
            O5PodStateManager.SyncedAlertSettings(
                expirationReminderEnabled = false, expirationReminderHours = 0,
                expirationAlarmEnabled = false, expirationAlarmHours = 0,
                lowReservoirAlertEnabled = false, lowReservoirAlertUnits = 0
            )
        )

        val result = plugin.executeCustomCommand(CommandUpdateAlertConfiguration())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand rejects CommandUpdateAlertConfiguration before activation completes`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.PRIME_COMPLETED)

        val result = plugin.executeCustomCommand(CommandUpdateAlertConfiguration())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand pushes CommandUpdateAlertConfiguration when settings changed and records the sync`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.syncedAlertSettings).thenReturn(null) // never synced
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(podStateManager.podLifeInHours).thenReturn(80.toShort())
        whenever(podStateManager.minutesSinceActivation).thenReturn(60.toShort())
        whenever(podStateManager.lastStatusResponseReceived).thenReturn(System.currentTimeMillis())
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandUpdateAlertConfiguration())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).syncedAlertSettings = any()
    }

    @Test
    fun `executeCustomCommand rejects CommandDisableSuspendAlerts when nothing is armed`() {
        whenever(podStateManager.suspendAlertsEnabled).thenReturn(false)

        val result = plugin.executeCustomCommand(CommandDisableSuspendAlerts(rh))

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }

    @Test
    fun `executeCustomCommand silences the pod for CommandDisableSuspendAlerts when armed`() {
        whenever(podStateManager.suspendAlertsEnabled).thenReturn(true)
        whenever(podStateManager.podId).thenReturn(12345L)
        whenever(bleManager.sendCommand(any(), any())).thenReturn(Observable.empty())

        val result = plugin.executeCustomCommand(CommandDisableSuspendAlerts(rh))

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isTrue()
        verify(bleManager).sendCommand(any(), any())
        verify(podStateManager).suspendAlertsEnabled = false
    }

    @Test
    fun `executeCustomCommand rejects CommandDeliverBasalCorrection when drift compensation is disabled - the default`() {
        // config.isEnabled(ENABLE_OMNIPOD_DRIFT_COMPENSATION) is unstubbed - Mockito's false
        // default - proving this experimental feature stays off unless explicitly enabled.
        val result = plugin.executeCustomCommand(CommandDeliverBasalCorrection())

        assertThat(result!!.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(bleManager, never()).sendCommand(any(), any())
    }
}
