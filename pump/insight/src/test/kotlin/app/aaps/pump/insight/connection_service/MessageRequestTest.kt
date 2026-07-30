package app.aaps.pump.insight.connection_service

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.exceptions.TimeoutException
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MessageRequestTest {

    private fun request() = MessageRequest(AppLayerMessage(MessagePriority.NORMAL, false, false, null))

    @Test
    fun `await with timeout throws TimeoutException when no response ever arrives`() {
        val request = request()

        // Looping on the fixed `timeout` on every wakeup (instead of tracking a deadline) meant this
        // could never actually time out - it behaved identically to the no-arg await(). This should
        // return promptly (well under a test-suite-breaking delay) with a real TimeoutException.
        assertThrows(TimeoutException::class.java) { request.await(50) }
    }

    @Test
    fun `await with timeout returns the response if it arrives before the deadline`() {
        val request = request()
        val response = AppLayerMessage(MessagePriority.NORMAL, false, false, null)

        Thread {
            Thread.sleep(20)
            synchronized(request) {
                request.response = response
                (request as Object).notifyAll()
            }
        }.start()

        assertThat(request.await(5000)).isSameInstanceAs(response)
    }
}
