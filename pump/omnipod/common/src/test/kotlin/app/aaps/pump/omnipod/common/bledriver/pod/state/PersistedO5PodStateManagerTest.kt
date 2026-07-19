package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.core.data.model.BS
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.definition.BasalProgram
import app.aaps.pump.omnipod.common.keys.O5StringNonPreferenceKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/**
 * Gson+[Preferences] persistence round-trip for [PersistedO5PodStateManager], with a
 * particular focus on [BasalProgram] - it has a custom `segments` property getter
 * (`Collections.unmodifiableList` wrapper), and no other class in this file's persisted
 * state had needed to serialize something with a custom getter before, so this is the
 * check that resolves that open question rather than assuming it round-trips correctly.
 *
 * [Preferences] is backed here by a single in-memory [String] var rather than individual
 * stubbed return values, so `store()` followed by a fresh manager's `load()` exercises the
 * real Gson serialize/deserialize round trip, not just mock plumbing.
 */
class PersistedO5PodStateManagerTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    private var backingStore: String? = null

    @BeforeEach
    fun setUp() {
        backingStore = null
        whenever(preferences.put(eq(O5StringNonPreferenceKey.PodState), any())).thenAnswer {
            backingStore = it.getArgument(1)
            Unit
        }
        whenever(preferences.getIfExists(O5StringNonPreferenceKey.PodState)).thenAnswer { backingStore }
        whenever(preferences.get(O5StringNonPreferenceKey.PodState)).thenAnswer { backingStore ?: "" }
    }

    private fun newManager() = PersistedO5PodStateManager(aapsLogger, preferences)

    @Test
    fun `fresh manager with no stored state returns defaults`() {
        val manager = newManager()

        assertThat(manager.activationProgress).isEqualTo(ActivationProgress.NOT_STARTED)
        assertThat(manager.ltk).isNull()
        assertThat(manager.basalProgram).isNull()
        assertThat(manager.msgSequenceNumber).isEqualTo(1.toByte())
    }

    @Test
    fun `basalProgram round-trips through Gson via a fresh manager instance`() {
        val program = BasalProgram(
            listOf(
                BasalProgram.Segment(0, 24, 100),
                BasalProgram.Segment(24, 48, 150)
            )
        )
        val writer = newManager()
        writer.basalProgram = program

        val reader = newManager()

        assertThat(reader.basalProgram).isEqualTo(program)
        assertThat(reader.basalProgram!!.segments).hasSize(2)
        assertThat(reader.basalProgram!!.segments[0].basalRateInHundredthUnitsPerHour).isEqualTo(100)
        assertThat(reader.basalProgram!!.segments[1].basalRateInHundredthUnitsPerHour).isEqualTo(150)
    }

    @Test
    fun `activationProgress and prime parameters round-trip`() {
        val writer = newManager()
        writer.activationProgress = ActivationProgress.PRIME_COMPLETED
        writer.primePulseRate = 20
        writer.firstPrimeBolusVolume = 52
        writer.secondPrimeBolusVolume = 10
        writer.podLifeInHours = 72

        val reader = newManager()

        assertThat(reader.activationProgress).isEqualTo(ActivationProgress.PRIME_COMPLETED)
        assertThat(reader.primePulseRate).isEqualTo(20.toShort())
        assertThat(reader.firstPrimeBolusVolume).isEqualTo(52.toShort())
        assertThat(reader.secondPrimeBolusVolume).isEqualTo(10.toShort())
        assertThat(reader.podLifeInHours).isEqualTo(72.toShort())
    }

    @Test
    fun `pendingDoseCommand round-trips including nested bolusType enum`() {
        val pending = O5PodStateManager.PendingDoseCommand(
            type = O5PodStateManager.PendingDoseType.BOLUS,
            requestedUnits = 1.25,
            bolusType = BS.Type.SMB,
            startedAt = 123456789L
        )
        val writer = newManager()
        writer.pendingDoseCommand = pending

        val reader = newManager()

        assertThat(reader.pendingDoseCommand).isEqualTo(pending)
        assertThat(reader.pendingDoseCommand!!.bolusType).isEqualTo(BS.Type.SMB)
    }

    @Test
    fun `dosing state fields round-trip`() {
        val writer = newManager()
        writer.deliverySuspended = true
        writer.lastBolusStartTime = 1_000L
        writer.lastBolusRequestedUnits = 2.5
        writer.lastBolusDeliveredUnits = 2.5
        writer.activeTempBasalStartTime = 2_000L
        writer.activeTempBasalRate = 0.5
        writer.activeTempBasalDurationMinutes = 30

        val reader = newManager()

        assertThat(reader.deliverySuspended).isTrue()
        assertThat(reader.lastBolusStartTime).isEqualTo(1_000L)
        assertThat(reader.lastBolusRequestedUnits).isEqualTo(2.5)
        assertThat(reader.lastBolusDeliveredUnits).isEqualTo(2.5)
        assertThat(reader.activeTempBasalStartTime).isEqualTo(2_000L)
        assertThat(reader.activeTempBasalRate).isEqualTo(0.5)
        assertThat(reader.activeTempBasalDurationMinutes).isEqualTo(30.toShort())
    }

    @Test
    fun `store() persists ltk but the logged copy strips it`() {
        val writer = newManager()
        writer.ltk = byteArrayOf(1, 2, 3, 4)

        // The persisted JSON must still contain the real ltk - store()'s ltk=null copy is only
        // used for the debug log line, not for what actually gets written to Preferences.
        val reader = newManager()
        assertThat(reader.ltk).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `increaseMessageSequenceNumber wraps at 4 bits`() {
        val manager = newManager()
        manager.msgSequenceNumber = 0x0e

        manager.increaseMessageSequenceNumber()
        assertThat(manager.msgSequenceNumber).isEqualTo(0x0f.toByte())

        manager.increaseMessageSequenceNumber()
        assertThat(manager.msgSequenceNumber).isEqualTo(0x00.toByte())
    }

    @Test
    fun `reset clears activation progress, prime parameters, and dosing state`() {
        val manager = newManager()
        manager.activationProgress = ActivationProgress.COMPLETED
        manager.basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
        manager.primePulseRate = 20
        manager.pendingDoseCommand = O5PodStateManager.PendingDoseCommand(
            type = O5PodStateManager.PendingDoseType.BASAL_PROGRAM, startedAt = 1L
        )
        manager.ltk = byteArrayOf(9)

        manager.reset()

        assertThat(manager.activationProgress).isEqualTo(ActivationProgress.NOT_STARTED)
        assertThat(manager.basalProgram).isNull()
        assertThat(manager.primePulseRate).isNull()
        assertThat(manager.pendingDoseCommand).isNull()
        assertThat(manager.ltk).isNull()
    }
}
