package app.aaps.pump.omnipod.common.bledriver.pod.command.insulin.program.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProgramBasalUtilTest {

    @Test
    fun `mapTenthPulsesPerSlotToLongInsulinProgramElements advances startSlotIndex by the finished element's slot count`() {
        // 3 slots @10, 2 slots @20, 4 slots @30 -> startSlotIndex should be 0, 3, 5 (not 0, 1, 2, which
        // is what you get if startSlotIndex is advanced by 1 every time instead of by the previous
        // element's actual slot count).
        val tenthPulsesPerSlot = shortArrayOf(10, 10, 10, 20, 20, 30, 30, 30, 30)

        val elements = ProgramBasalUtil.mapTenthPulsesPerSlotToLongInsulinProgramElements(tenthPulsesPerSlot)

        assertThat(elements).hasSize(3)
        assertThat(elements[0].startSlotIndex).isEqualTo(0)
        assertThat(elements[0].numberOfSlots).isEqualTo(3)
        assertThat(elements[1].startSlotIndex).isEqualTo(3)
        assertThat(elements[1].numberOfSlots).isEqualTo(2)
        assertThat(elements[2].startSlotIndex).isEqualTo(5)
        assertThat(elements[2].numberOfSlots).isEqualTo(4)
    }
}
