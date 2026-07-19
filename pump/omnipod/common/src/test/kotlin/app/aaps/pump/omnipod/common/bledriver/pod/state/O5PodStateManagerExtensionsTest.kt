package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.ZonedDateTime

/**
 * [expiry] reads wall-clock time directly ([ZonedDateTime.now]/[System.currentTimeMillis])
 * rather than an injectable clock, so these tests assert against a tolerance window instead
 * of an exact instant - tight enough to catch a wrong formula (wrong sign, wrong field) but
 * not flaky on slow CI. The `.minusHours(8)` term is copied verbatim from
 * [app.aaps.pump.omnipod.common.bledriver.pod.state.OmnipodDashPodStateManagerImpl.expiry]
 * with no explanation found anywhere in this codebase for why it's there - this test locks
 * down the current (copied) behavior, it does not claim to justify the 8 hours.
 */
class O5PodStateManagerExtensionsTest : TestBase() {

    private fun mockState(
        podLifeInHours: Short? = null,
        minutesSinceActivation: Short? = null,
        lastStatusResponseReceived: Long? = null
    ): O5PodStateManager {
        val state = mock<O5PodStateManager>()
        whenever(state.podLifeInHours).thenReturn(podLifeInHours)
        whenever(state.minutesSinceActivation).thenReturn(minutesSinceActivation)
        whenever(state.lastStatusResponseReceived).thenReturn(lastStatusResponseReceived)
        return state
    }

    @Test
    fun `expiry is null when podLifeInHours is unknown`() {
        val state = mockState(minutesSinceActivation = 0, lastStatusResponseReceived = System.currentTimeMillis())

        assertThat(state.expiry).isNull()
    }

    @Test
    fun `expiry is null when minutesSinceActivation is unknown`() {
        val state = mockState(podLifeInHours = 72, lastStatusResponseReceived = System.currentTimeMillis())

        assertThat(state.expiry).isNull()
    }

    @Test
    fun `expiry is null when no status response has ever been received`() {
        val state = mockState(podLifeInHours = 72, minutesSinceActivation = 0)

        assertThat(state.expiry).isNull()
    }

    @Test
    fun `freshly activated pod expires close to podLifeInHours minus the 8h grace period from now`() {
        val state = mockState(podLifeInHours = 72, minutesSinceActivation = 0, lastStatusResponseReceived = System.currentTimeMillis())

        val expiresAt = requireNotNull(state.expiry)

        // Expected: now + 72h - 0min - (negligible elapsed since status) - 8h == now + 64h
        val expected = ZonedDateTime.now().plusHours(64)
        val diffSeconds = Duration.between(expected, expiresAt).abs().seconds
        assertThat(diffSeconds).isLessThan(10)
    }

    @Test
    fun `elapsed minutesSinceActivation shortens the remaining life`() {
        val state = mockState(podLifeInHours = 72, minutesSinceActivation = 60, lastStatusResponseReceived = System.currentTimeMillis())

        val expiresAt = requireNotNull(state.expiry)

        // now + 72h - 60min - 8h == now + 63h
        val expected = ZonedDateTime.now().plusHours(63)
        val diffSeconds = Duration.between(expected, expiresAt).abs().seconds
        assertThat(diffSeconds).isLessThan(10)
    }

    @Test
    fun `time elapsed since the last status response is also subtracted`() {
        // Simulate a status response received 30 minutes ago rather than "just now".
        val state = mockState(
            podLifeInHours = 72,
            minutesSinceActivation = 0,
            lastStatusResponseReceived = System.currentTimeMillis() - Duration.ofMinutes(30).toMillis()
        )

        val expiresAt = requireNotNull(state.expiry)

        // now + 72h - 0min - 30min-since-response - 8h == now + 63h30m
        val expected = ZonedDateTime.now().plusHours(63).plusMinutes(30)
        val diffSeconds = Duration.between(expected, expiresAt).abs().seconds
        assertThat(diffSeconds).isLessThan(10)
    }
}
