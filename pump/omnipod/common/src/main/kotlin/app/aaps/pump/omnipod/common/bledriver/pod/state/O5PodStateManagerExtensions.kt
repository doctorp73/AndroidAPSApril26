package app.aaps.pump.omnipod.common.bledriver.pod.state

import java.time.Duration
import java.time.ZonedDateTime

/**
 * Estimated pod expiration time, mirroring
 * [OmnipodDashPodStateManagerImpl.expiry]'s formula (including its unexplained
 * `.minusHours(8)` grace-period adjustment, copied verbatim rather than guessed at) -
 * null until [O5PodStateManager.podLifeInHours]/[O5PodStateManager.minutesSinceActivation]
 * are known (i.e. before [app.aaps.pump.omnipod.common.bledriver.pod.response
 * .SetUniqueIdResponse] has been received during activation).
 */
val O5PodStateManager.expiry: ZonedDateTime?
    get() {
        val hours = podLifeInHours ?: return null
        val minutes = minutesSinceActivation ?: return null
        val lastUpdated = lastStatusResponseReceived ?: return null
        return ZonedDateTime.now()
            .plusHours(hours.toLong())
            .minusMinutes(minutes.toLong())
            .minus(Duration.ofMillis(System.currentTimeMillis() - lastUpdated))
            .minusHours(8)
    }
