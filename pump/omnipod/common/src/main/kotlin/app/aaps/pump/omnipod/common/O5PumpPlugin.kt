package app.aaps.pump.omnipod.common

import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginOmnipod
import app.aaps.pump.omnipod.common.bledriver.comm.O5BleManager
import app.aaps.pump.omnipod.common.bledriver.pod.command.DeactivateCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBeepsCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.SilenceAlertsCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.common.bledriver.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BeepType
import app.aaps.pump.omnipod.common.bledriver.pod.definition.O5_FIXED_NONCE
import app.aaps.pump.omnipod.common.bledriver.pod.definition.PodConstants
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.common.bledriver.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.common.bledriver.pod.response.ResponseType
import app.aaps.pump.omnipod.common.bledriver.pod.state.O5PodStateManager
import app.aaps.pump.omnipod.common.keys.OmnipodBooleanPreferenceKey
import app.aaps.pump.omnipod.common.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.common.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.common.queue.command.CommandPairNewPod
import app.aaps.pump.omnipod.common.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.common.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.common.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.common.queue.command.CommandSuspendDelivery
import app.aaps.pump.omnipod.common.ui.compose.OmnipodO5ComposeContent
import app.aaps.pump.omnipod.common.util.mapProfileToBasalProgram
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx3.rxCompletable
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * `Pump`-interface implementation for Omnipod 5 - the missing piece [O5Module]'s doc
 * comment describes. Connects the already-built pairing/BLE/command/persistence layers
 * (see [O5BleManager], [O5PodStateManager]) to AAPS's dosing/control surface.
 *
 * Runs O5 as a manual pod under AAPS's control, identical capabilities to Omnipod Dash -
 * the pod's own onboard automated-delivery mode is never engaged (mirrors
 * [PumpType.OMNIPOD_DASH]'s capability fields).
 *
 * Every dose-affecting command (bolus, temp basal, basal program) records a
 * [O5PodStateManager.pendingDoseCommand] marker *before* sending, and clears it only once
 * the outcome is confirmed. If the BLE response never arrives, the marker survives (it's
 * part of the persisted pod state) and [reconcilePendingDose] - run at the end of every
 * [getPumpStatus] call, including the periodic [statusChecker] heartbeat - resolves it on
 * the next successful status read. This is what makes an uncertain delivery outcome
 * recoverable instead of silently lost; see [O5PodStateManager]'s class doc for why this
 * doesn't need a Dash-style persisted command ledger to do so.
 */
@Singleton
class O5PumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val bleManager: O5BleManager,
    private val podStateManager: O5PodStateManager,
    private val pumpSync: PumpSync,
    private val notificationManager: NotificationManager,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val bolusProgressData: BolusProgressData,
    private val protectionCheck: ProtectionCheck,
    private val blePreCheck: BlePreCheck
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .composeContent { _ ->
            OmnipodO5ComposeContent(
                pluginName = rh.gs(R.string.omnipod_5_name),
                protectionCheck = protectionCheck,
                blePreCheck = blePreCheck,
                rh = rh
            )
        }
        .icon(IcPluginOmnipod)
        .pluginName(R.string.omnipod_5_name)
        .shortName(R.string.omnipod_5_name_short)
        .description(R.string.omnipod_5_pump_description),
    ownPreferences = listOf(OmnipodBooleanPreferenceKey::class.java),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    @Volatile private var bolusCanceled = false
    @Volatile private var bolusDeliveryInProgress = false
    @Volatile private var stopConnecting: CountDownLatch? = null

    private var statusChecker: Runnable

    companion object {

        private const val BOLUS_RETRY_INTERVAL_MS = 2000L
        private const val BOLUS_RETRIES = 5
        private const val STATUS_CHECK_INTERVAL_MS = 60L * 1000
        private const val RESERVOIR_OVER_50_UNITS_DEFAULT = 75.0

        private const val FIXED_NONCE = O5_FIXED_NONCE

        /** Matches Dash's fixed pulse-delay constant for bolus delivery pacing - a
         *  pod-firmware-level property of the shared command layer. */
        private const val BOLUS_DELAY_BETWEEN_PULSES_EIGHTH_SECONDS: Byte = 16

        private val pumpDescription = PumpDescription().fillFor(PumpType.OMNIPOD_5)
    }

    init {
        statusChecker = Runnable {
            try {
                runBlocking { getPumpStatus("O5 statusChecker") }
            } catch (e: Exception) {
                aapsLogger.warn(LTag.PUMP, "Error in O5 statusChecker: $e")
            }
            handler?.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
        }
    }

    override suspend fun onStart() {
        super.onStart()
        handler?.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
    }

    override suspend fun onStop() {
        super.onStop()
        handler?.removeCallbacks(statusChecker)
    }

    // -- connection lifecycle -------------------------------------------------------------

    override fun isInitialized(): Boolean = podStateManager.activationProgress == ActivationProgress.COMPLETED
    override fun isSuspended(): Boolean = podStateManager.deliverySuspended

    // isBusy() gates the ENTIRE command queue, including custom commands like
    // CommandPairNewPod (see QueueWorker's isBusy() check) - NOT_STARTED is deliberately
    // excluded so pairing itself is never blocked (that's the step that transitions out of
    // NOT_STARTED). Only an activation already in progress blocks the queue, matching Dash's
    // ActivationProgress-based isBusy(). O5OmnipodWizardViewModel calls bleManager/command
    // classes directly (not via CommandQueue), so this gate never blocks its own steps.
    override fun isBusy(): Boolean =
        podStateManager.activationProgress != ActivationProgress.NOT_STARTED &&
            podStateManager.activationProgress.isBefore(ActivationProgress.COMPLETED)

    override fun isConnected(): Boolean =
        podStateManager.ltk == null ||
            podStateManager.bluetoothConnectionState == O5PodStateManager.BluetoothConnectionState.CONNECTED

    override fun isConnecting(): Boolean = stopConnecting != null

    override fun isHandshakeInProgress(): Boolean =
        stopConnecting != null &&
            podStateManager.bluetoothConnectionState == O5PodStateManager.BluetoothConnectionState.CONNECTED

    override fun finishHandshaking() {}

    override fun connect(reason: String) {
        aapsLogger.info(LTag.PUMP, "O5 connect reason=$reason")
        podStateManager.bluetoothConnectionState = O5PodStateManager.BluetoothConnectionState.CONNECTING
        synchronized(this) {
            stopConnecting?.let {
                aapsLogger.warn(LTag.PUMP, "O5 already connecting: $it")
                return
            }
            stopConnecting = CountDownLatch(1)
        }
        thread(start = true, name = "O5ConnectionThread") {
            try {
                stopConnecting?.let { latch ->
                    bleManager.connect(latch).ignoreElements().blockingAwait()
                }
            } catch (e: Exception) {
                aapsLogger.info(LTag.PUMPCOMM, "O5 connect error=$e")
            } finally {
                synchronized(this) { stopConnecting = null }
            }
        }
    }

    override fun disconnect(reason: String) {
        aapsLogger.info(LTag.PUMP, "O5 disconnect reason=$reason")
        stopConnecting?.countDown()
        bleManager.disconnect(false)
    }

    override fun stopConnecting() {
        aapsLogger.info(LTag.PUMP, "O5 stopConnecting")
        stopConnecting?.countDown()
        bleManager.disconnect(true)
    }

    // -- status polling + uncertain-delivery reconciliation --------------------------------

    override suspend fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "O5 getPumpStatus reason=$reason")
        if (podStateManager.ltk == null) {
            return // not paired yet, nothing to read
        }
        try {
            fetchStatus().blockingAwait()
            reconcilePendingDose()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 getPumpStatus", e)
        }
        syncPumpFlows()
    }

    private fun fetchStatus(): Completable = Completable.defer {
        val cmd = GetStatusCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
            .build()
        bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements()
    }

    /**
     * Resolves a [O5PodStateManager.pendingDoseCommand] left over from a dose-affecting
     * call whose BLE response never arrived - see this class's doc comment. Called after
     * every successful status read, so an uncertain outcome is recovered automatically
     * rather than only on the next user-triggered action.
     */
    private suspend fun reconcilePendingDose() {
        val pending = podStateManager.pendingDoseCommand ?: return
        when (pending.type) {
            O5PodStateManager.PendingDoseType.BOLUS              ->
                if (podStateManager.deliveryStatus?.bolusDeliveringActive() != true) {
                    val deliveredUnits = (pending.requestedUnits ?: 0.0) -
                        (podStateManager.bolusPulsesRemaining?.toInt() ?: 0) * PodConstants.POD_PULSE_BOLUS_UNITS
                    if (podStateManager.lastBolusDeliveredUnits == null) {
                        pumpSync.syncBolusWithPumpId(
                            timestamp = pending.startedAt,
                            amount = PumpInsulin(deliveredUnits),
                            type = pending.bolusType ?: BS.Type.NORMAL,
                            pumpId = pending.startedAt,
                            pumpType = PumpType.OMNIPOD_5,
                            pumpSerial = serialNumber()
                        )
                    }
                    podStateManager.lastBolusDeliveredUnits = deliveredUnits
                    podStateManager.pendingDoseCommand = null
                }

            O5PodStateManager.PendingDoseType.TEMP_BASAL_START   ->
                if (podStateManager.deliveryStatus?.tempBasalActive() == true) {
                    podStateManager.pendingDoseCommand = null
                }

            O5PodStateManager.PendingDoseType.TEMP_BASAL_CANCEL  ->
                if (podStateManager.deliveryStatus?.tempBasalActive() != true) {
                    podStateManager.activeTempBasalStartTime = null
                    podStateManager.activeTempBasalRate = null
                    podStateManager.activeTempBasalDurationMinutes = null
                    podStateManager.pendingDoseCommand = null
                }

            O5PodStateManager.PendingDoseType.BASAL_PROGRAM      ->
                if (podStateManager.deliveryStatus?.basalActive() == true) {
                    podStateManager.pendingDoseCommand = null
                }
        }
    }

    private fun requirePodId(): Int =
        podStateManager.podId?.toInt() ?: throw IllegalStateException("O5 pod not paired")

    // -- basal profile ----------------------------------------------------------------------

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        if (podStateManager.ltk == null) {
            // nothing paired yet - same "prevent setBasal requests" guard Dash uses
            return pumpEnactResultProvider.get().success(true).enacted(true)
        }
        val basalProgram = mapProfileToBasalProgram(profile, PumpType.OMNIPOD_5)
        return try {
            if (podStateManager.deliveryStatus?.suspended() != true) {
                val cmd = SuspendDeliveryCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                    .setNonce(FIXED_NONCE)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                podStateManager.deliverySuspended = true
            }

            podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.BASAL_PROGRAM,
                startedAt = System.currentTimeMillis()
            )
            val basalBeeps = preferences.get(OmnipodBooleanPreferenceKey.BasalBeepsEnabled)
            val cmd = ProgramBasalCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setBasalProgram(basalProgram)
                .setProgramReminder(ProgramReminder(atStart = basalBeeps, atEnd = false, atInterval = 0))
                .setCurrentTime(Date())
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            podStateManager.basalProgram = basalProgram
            podStateManager.deliverySuspended = false
            podStateManager.pendingDoseCommand = null
            notificationManager.post(NotificationId.PROFILE_SET_OK, app.aaps.core.ui.R.string.profile_set_ok)
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 setNewBasalProfile", e)
            notifyUncertain(NotificationId.FAILED_UPDATE_PROFILE, rh.gs(R.string.omnipod_5_error_setting_basal_profile_might_have_failed))
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (podStateManager.ltk == null) return true
        if (podStateManager.deliverySuspended) return false
        return mapProfileToBasalProgram(profile, PumpType.OMNIPOD_5) == podStateManager.basalProgram
    }

    // -- StateFlow status surface -------------------------------------------------------------

    private val _lastDataTime = MutableStateFlow(0L)
    override val lastDataTime: StateFlow<Long> = _lastDataTime

    private val _lastBolusTime = MutableStateFlow<Long?>(null)
    override val lastBolusTime: StateFlow<Long?> = _lastBolusTime

    private val _lastBolusAmount = MutableStateFlow<PumpInsulin?>(null)
    override val lastBolusAmount: StateFlow<PumpInsulin?> = _lastBolusAmount

    override val baseBasalRate: PumpRate
        get() {
            val rate = if (podStateManager.alarmType != null) 0.0
            else podStateManager.basalProgram?.rateAt(System.currentTimeMillis()) ?: 0.0
            return PumpRate(rate)
        }

    private val _reservoirLevel = MutableStateFlow(PumpInsulin(0.0))
    override val reservoirLevel: StateFlow<PumpInsulin> = _reservoirLevel

    // Omnipod 5 doesn't report its battery level (same finding as Dash)
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)

    private fun syncPumpFlows() {
        _lastDataTime.value = podStateManager.lastStatusResponseReceived ?: 0L
        _lastBolusTime.value = podStateManager.lastBolusStartTime
        _lastBolusAmount.value = podStateManager.lastBolusRequestedUnits?.let { PumpInsulin(it) }
        _reservoirLevel.value = PumpInsulin(
            podStateManager.reservoirPulsesRemaining?.let { it * PodConstants.POD_PULSE_BOLUS_UNITS }
                ?: RESERVOIR_OVER_50_UNITS_DEFAULT
        )
    }

    // -- bolus ----------------------------------------------------------------------------

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }

        try {
            bolusDeliveryInProgress = true
            // Refresh the reservoir from current pod state before the gate below, same staleness
            // guard Dash uses: right after activation _reservoirLevel is otherwise still its 0.0
            // init until the next status poll.
            syncPumpFlows()
            val requestedUnits = detailedBolusInfo.insulin
            if (requestedUnits > reservoirLevel.value.cU) {
                return pumpEnactResultProvider.get().success(false).enacted(false).bolusDelivered(0.0)
                    .comment(rh.gs(R.string.omnipod_5_error_not_enough_insulin))
            }
            if (podStateManager.deliveryStatus?.bolusDeliveringActive() == true) {
                return pumpEnactResultProvider.get().success(false).enacted(false).bolusDelivered(0.0)
                    .comment(rh.gs(R.string.omnipod_5_error_bolus_already_in_progress))
            }

            val bolusBeepsKey = if (detailedBolusInfo.bolusType == BS.Type.SMB) OmnipodBooleanPreferenceKey.SmbBeepsEnabled
            else OmnipodBooleanPreferenceKey.BolusBeepsEnabled
            val bolusBeeps = preferences.get(bolusBeepsKey)
            val startedAt = System.currentTimeMillis()

            podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.BOLUS,
                requestedUnits = requestedUnits,
                bolusType = detailedBolusInfo.bolusType,
                startedAt = startedAt
            )
            podStateManager.lastBolusStartTime = startedAt
            podStateManager.lastBolusRequestedUnits = requestedUnits
            podStateManager.lastBolusDeliveredUnits = null

            val cmd = ProgramBolusCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setNumberOfUnits(requestedUnits)
                .setDelayBetweenPulsesInEighthSeconds(BOLUS_DELAY_BETWEEN_PULSES_EIGHTH_SECONDS)
                .setProgramReminder(ProgramReminder(atStart = bolusBeeps, atEnd = bolusBeeps, atInterval = 0))
                .build()

            var deliveredUnits = 0.0
            val ret = bleManager.sendCommand(cmd, DefaultStatusResponse::class)
                .filter { it.isCommandSent() }
                .concatMapCompletable {
                    rxCompletable(Dispatchers.IO) {
                        pumpSync.syncBolusWithPumpId(
                            timestamp = startedAt,
                            amount = PumpInsulin(requestedUnits),
                            type = detailedBolusInfo.bolusType,
                            pumpId = startedAt,
                            pumpType = PumpType.OMNIPOD_5,
                            pumpSerial = serialNumber()
                        )
                    }
                }
                .andThen(waitForBolusDeliveryToComplete(requestedUnits).map { deliveredUnits = it }.ignoreElement())
                .toSingle { pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(deliveredUnits) }
                .doOnError { throwable -> aapsLogger.error(LTag.PUMP, "O5 deliverTreatment error: $throwable") }
                .onErrorReturnItem(pumpEnactResultProvider.get().success(bolusCanceled).enacted(false))
                .blockingGet()

            if (detailedBolusInfo.bolusType == BS.Type.SMB) {
                notifyUncertain(NotificationId.OMNIPOD_UNCERTAIN_SMB, rh.gs(R.string.omnipod_5_error_uncertain_smb, requestedUnits))
            } else if (podStateManager.pendingDoseCommand != null) {
                notifyUncertain(NotificationId.OMNIPOD_POD_FAULT, rh.gs(R.string.omnipod_5_error_bolus_delivery_status_uncertain))
            }
            return ret
        } finally {
            bolusCanceled = false
            bolusDeliveryInProgress = false
        }
    }

    private fun waitForBolusDeliveryToComplete(requestedUnits: Double): Single<Double> = Single.defer {
        val estimatedSeconds = ceil(requestedUnits / PodConstants.POD_PULSE_BOLUS_UNITS).toLong() * 2 + 3
        var waited = 0L
        while (waited < estimatedSeconds && !bolusCanceled) {
            waited += 1
            Thread.sleep(1000)
            val percent = (waited.toFloat() / estimatedSeconds) * 100
            bolusProgressData.updateProgress(percent.toInt())
        }

        repeat(BOLUS_RETRIES) {
            val cmd = if (bolusCanceled) cancelBolus() else fetchStatus()
            try {
                cmd.blockingAwait()
            } catch (e: Exception) {
                aapsLogger.debug(LTag.PUMP, "waitForBolusDeliveryToComplete errorGettingStatus=$e")
                Thread.sleep(BOLUS_RETRY_INTERVAL_MS)
                return@repeat
            }
            val bolusActive = podStateManager.deliveryStatus?.bolusDeliveringActive() == true
            if (bolusActive) {
                val remainingUnits = (podStateManager.bolusPulsesRemaining?.toInt() ?: 0) * PodConstants.POD_PULSE_BOLUS_UNITS
                val delivered = requestedUnits - remainingUnits
                val percent = (delivered / requestedUnits) * 100
                bolusProgressData.updateProgress(percent.toInt())
                val sleepSeconds = if (bolusCanceled) BOLUS_RETRY_INTERVAL_MS / 1000
                else ceil(remainingUnits / PodConstants.POD_PULSE_BOLUS_UNITS).toLong() * 2 + 3
                Thread.sleep(sleepSeconds * 1000)
            } else {
                val deliveredUnits = requestedUnits - (podStateManager.bolusPulsesRemaining?.toInt() ?: 0) * PodConstants.POD_PULSE_BOLUS_UNITS
                podStateManager.lastBolusDeliveredUnits = deliveredUnits
                podStateManager.pendingDoseCommand = null
                return@defer Single.just(deliveredUnits)
            }
        }
        Single.just(requestedUnits) // still uncertain - left for reconcilePendingDose() on the next status poll
    }

    private fun cancelBolus(): Completable = Completable.defer {
        val cmd = StopDeliveryCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setNonce(FIXED_NONCE)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.BOLUS)
            .build()
        bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements()
    }

    override fun stopBolusDelivering() {
        aapsLogger.info(LTag.PUMP, "O5 stopBolusDelivering called")
        if (bolusDeliveryInProgress) {
            bolusCanceled = true
        }
    }

    // -- temp basal -------------------------------------------------------------------------

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        aapsLogger.info(LTag.PUMP, "O5 setTempBasalAbsolute: rate=$absoluteRate U/h duration=$durationInMinutes min enforce=$enforceNew type=$tbrType")
        return try {
            if (podStateManager.deliveryStatus?.tempBasalActive() == true) {
                cancelActiveTempBasal()
            }
            val tempBasalBeeps = preferences.get(OmnipodBooleanPreferenceKey.TbrBeepsEnabled)
            val startedAt = System.currentTimeMillis()
            podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
                type = O5PodStateManager.PendingDoseType.TEMP_BASAL_START,
                requestedRate = absoluteRate,
                requestedDurationMinutes = durationInMinutes.toShort(),
                startedAt = startedAt
            )
            val cmd = ProgramTempBasalCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .setProgramReminder(ProgramReminder(atStart = tempBasalBeeps, atEnd = tempBasalBeeps, atInterval = 0))
                .setRateInUnitsPerHour(absoluteRate)
                .setDurationInMinutes(durationInMinutes.toShort())
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()

            pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = startedAt,
                rate = PumpRate(absoluteRate),
                duration = T.mins(durationInMinutes.toLong()).msecs(),
                isAbsolute = true,
                type = tbrType,
                pumpId = startedAt,
                pumpType = PumpType.OMNIPOD_5,
                pumpSerial = serialNumber()
            )
            podStateManager.activeTempBasalStartTime = startedAt
            podStateManager.activeTempBasalRate = absoluteRate
            podStateManager.activeTempBasalDurationMinutes = durationInMinutes.toShort()
            podStateManager.pendingDoseCommand = null
            pumpEnactResultProvider.get().success(true).enacted(true).isPercent(false).absolute(absoluteRate).duration(durationInMinutes)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 setTempBasalAbsolute", e)
            notifyUncertain(NotificationId.OMNIPOD_TBR_ALERTS, rh.gs(R.string.omnipod_5_error_setting_temp_basal_might_have_failed))
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    override suspend fun setTempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        error("Pump doesn't support percent basal rate")

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (podStateManager.deliveryStatus?.tempBasalActive() != true && pumpSync.expectedPumpState().temporaryBasal == null) {
            return pumpEnactResultProvider.get().success(true).enacted(false)
        }
        return try {
            cancelActiveTempBasal()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in O5 cancelTempBasal", e)
            notifyUncertain(NotificationId.OMNIPOD_TBR_ALERTS, rh.gs(R.string.omnipod_5_error_cancel_temp_basal_result_is_uncertain))
            pumpEnactResultProvider.get().success(false).enacted(false)
        }
    }

    private fun cancelActiveTempBasal() {
        podStateManager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
            type = O5PodStateManager.PendingDoseType.TEMP_BASAL_CANCEL,
            startedAt = System.currentTimeMillis()
        )
        val cmd = StopDeliveryCommand.Builder()
            .setUniqueId(requirePodId())
            .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
            .setNonce(FIXED_NONCE)
            .setDeliveryType(StopDeliveryCommand.DeliveryType.TEMP_BASAL)
            .build()
        bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
        podStateManager.activeTempBasalStartTime = null
        podStateManager.activeTempBasalRate = null
        podStateManager.activeTempBasalDurationMinutes = null
        podStateManager.pendingDoseCommand = null
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported))

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_extended_bolus_not_supported))

    // -- misc ---------------------------------------------------------------------------------

    override fun updateExtendedJsonStatus(extendedStatus: JSONObject) {}

    override val pumpDescription: PumpDescription = Companion.pumpDescription
    override fun manufacturer(): ManufacturerType = ManufacturerType.Insulet
    override fun model(): PumpType = pumpDescription.pumpType
    override fun serialNumber(): String = podStateManager.podId?.toString() ?: "O5-unpaired"
    override val isFakingTempsByExtendedBoluses: Boolean = false

    override suspend fun loadTDDs(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false)
            .comment(rh.gs(R.string.omnipod_5_error_tdd_not_supported))

    override fun canHandleDST(): Boolean = false

    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult =
        when (customCommand) {
            is CommandPairNewPod      -> pairNewPod()
            is CommandDeactivatePod   -> deactivatePod()
            is CommandSilenceAlerts   -> silenceAlerts()
            is CommandResumeDelivery  -> runBlocking { resumeOrHandleTimeChange() }
            is CommandSuspendDelivery -> suspendDelivery()
            is CommandPlayTestBeep    -> playTestBeep()
            is CommandHandleTimeChange -> runBlocking { resumeOrHandleTimeChange() }
            else                      -> {
                aapsLogger.warn(LTag.PUMP, "Unsupported custom command: " + customCommand.javaClass.name)
                pumpEnactResultProvider.get().success(false).enacted(false).comment(
                    rh.gs(R.string.omnipod_common_error_unsupported_custom_command, customCommand.javaClass.name)
                )
            }
        }

    private fun pairNewPod(): PumpEnactResult =
        try {
            bleManager.pairNewPod().ignoreElements().blockingAwait()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error pairing new O5 pod", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    private fun deactivatePod(): PumpEnactResult =
        try {
            val cmd = DeactivateCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            bleManager.removeBond()
            podStateManager.reset()
            notificationManager.dismiss(NotificationId.OMNIPOD_POD_FAULT)
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error deactivating O5 pod", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    private fun silenceAlerts(): PumpEnactResult =
        podStateManager.activeAlerts?.let { alerts ->
            try {
                val cmd = SilenceAlertsCommand.Builder()
                    .setUniqueId(requirePodId())
                    .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                    .setNonce(FIXED_NONCE)
                    .setAlertTypes(alerts)
                    .build()
                bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
                pumpEnactResultProvider.get().success(true).enacted(true)
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "Error silencing O5 alerts", e)
                pumpEnactResultProvider.get().success(false).enacted(false)
            }
        } ?: pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.omnipod_5_error_no_active_alerts))

    private fun suspendDelivery(): PumpEnactResult =
        try {
            val cmd = SuspendDeliveryCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setNonce(FIXED_NONCE)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            podStateManager.deliverySuspended = true
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error suspending O5 delivery", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    private fun playTestBeep(): PumpEnactResult =
        try {
            val silentReminder = ProgramReminder(atStart = false, atEnd = false, atInterval = 0)
            val cmd = ProgramBeepsCommand.Builder()
                .setUniqueId(requirePodId())
                .setSequenceNumber(podStateManager.msgSequenceNumber.toShort())
                .setImmediateBeepType(BeepType.LONG_SINGLE_BEEP)
                .setBasalReminder(silentReminder)
                .setTempBasalReminder(silentReminder)
                .setBolusReminder(silentReminder)
                .build()
            bleManager.sendCommand(cmd, DefaultStatusResponse::class).ignoreElements().blockingAwait()
            pumpEnactResultProvider.get().success(true).enacted(true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error playing O5 test beep", e)
            pumpEnactResultProvider.get().success(false).enacted(false)
        }

    /** Resume Delivery and Handle Time Change are both just "(re)program the current
     *  basal profile" - [ProgramBasalCommand] always sets the pod's current time and
     *  implicitly resumes delivery, same as Dash's identical handling of both. */
    private suspend fun resumeOrHandleTimeChange(): PumpEnactResult =
        pumpSync.expectedPumpState().profile?.let { setNewBasalProfile(it) }
            ?: pumpEnactResultProvider.get().success(false).enacted(false).comment(rh.gs(R.string.omnipod_5_error_no_active_profile))

    private fun notifyUncertain(id: NotificationId, message: String) {
        if (podStateManager.pendingDoseCommand != null) {
            notificationManager.post(id, message, soundRes = app.aaps.core.ui.R.raw.boluserror)
        }
    }

}
