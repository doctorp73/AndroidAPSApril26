package app.aaps.pump.omnipod.common.bledriver.pod.definition

import java.util.*

class BasalProgram(
    segments: List<Segment>
) {

    // Nullable, despite mutableSegments always being non-null through this constructor: Gson
    // deserializes persisted BasalProgram instances via reflection, which allocates the object
    // and sets fields directly WITHOUT ever calling this constructor - so a BasalProgram
    // persisted under an older field layout (e.g. from before this class had a
    // mutableSegments field at all) deserializes with this backing field left null, despite
    // the type system's guarantee. Self-heals to an empty list on first access rather than
    // NPEing, which previously crashed every rateAt() call - and therefore the entire loop
    // calculation cycle - for anyone with pre-existing persisted pump state.
    private var mutableSegmentsOrNull: MutableList<Segment>? = segments.toMutableList()
    private val mutableSegments: MutableList<Segment>
        get() = mutableSegmentsOrNull ?: mutableListOf<Segment>().also { mutableSegmentsOrNull = it }

    val segments: MutableList<Segment> get() = Collections.unmodifiableList(mutableSegments)

    fun addSegment(segment: Segment) {
        mutableSegments.add(segment)
    }

    fun hasZeroUnitSegments() = segments.any { it.basalRateInHundredthUnitsPerHour == 0 }

    fun rateAt(date: Long): Double {
        val instance = Calendar.getInstance()
        instance.timeInMillis = date
        val hourOfDay = instance[Calendar.HOUR_OF_DAY]
        val minuteOfHour = instance[Calendar.MINUTE]
        val slotIndex = hourOfDay * 2 + minuteOfHour.div(30)
        val slot = segments.find { it.startSlotIndex <= slotIndex && slotIndex < it.endSlotIndex }
        return (slot?.basalRateInHundredthUnitsPerHour ?: 0).toDouble() / 100
    }

    class Segment(
        val startSlotIndex: Short,
        val endSlotIndex: Short,
        val basalRateInHundredthUnitsPerHour: Int
    ) {

        fun getPulsesPerHour(): Short {
            return (basalRateInHundredthUnitsPerHour * PULSES_PER_UNIT / 100).toShort()
        }

        fun getNumberOfSlots(): Short {
            return (endSlotIndex - startSlotIndex).toShort()
        }

        override fun toString(): String {
            return "Segment{" +
                "startSlotIndex=" + startSlotIndex +
                ", endSlotIndex=" + endSlotIndex +
                ", basalRateInHundredthUnitsPerHour=" + basalRateInHundredthUnitsPerHour +
                '}'
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Segment

            if (startSlotIndex != other.startSlotIndex) return false
            if (endSlotIndex != other.endSlotIndex) return false
            if (basalRateInHundredthUnitsPerHour != other.basalRateInHundredthUnitsPerHour) return false

            return true
        }

        override fun hashCode(): Int {
            var result: Int = startSlotIndex.toInt()
            result = 31 * result + endSlotIndex
            result = 31 * result + basalRateInHundredthUnitsPerHour
            return result
        }

        companion object {

            private const val PULSES_PER_UNIT: Byte = 20
        }
    }

    override fun toString(): String {
        return "BasalProgram{" +
            "segments=" + segments +
            '}'
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BasalProgram

        return segments == other.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }
}
