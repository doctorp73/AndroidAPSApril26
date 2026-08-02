package app.aaps.pump.insight.connection_service

import app.aaps.core.utils.wait
import app.aaps.core.utils.waitMillis
import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.exceptions.TimeoutException

open class MessageRequest<T : AppLayerMessage> internal constructor(var request: T) : Comparable<MessageRequest<*>> {

    var response: T? = null
    var exception: Exception? = null

    @Throws(Exception::class)
    open fun await(): T {
        synchronized(this) {
            while (exception == null && response == null) wait()
            exception?.let { e -> throw e }
            return response!!
        }
    }

    @Throws(Exception::class)
    open fun await(timeout: Long): T {
        synchronized(this) {
            // wait()/waitMillis() can return spuriously (or be notified for a reason other than this
            // request completing) before the full timeout elapses - looping on a fixed `timeout` (as
            // before) re-armed the same full wait every time, so this could never actually time out.
            // Track the deadline instead and only wait the remaining budget each iteration.
            val deadline = System.currentTimeMillis() + timeout
            while (exception == null && response == null) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw TimeoutException()
                waitMillis(remaining)
            }
            exception?.let { e -> throw e }
            return response!!
        }
    }

    override fun compareTo(other: MessageRequest<*>): Int {
        return request.compareTo(other.request)
    }
}