package hr.workspace.boat4you.domains.external.nausys

import hr.workspace.boat4you.domains.external.nausys.model.NauSysTimeWrapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime

class NauSysTimeWrapperTests {
    @Test
    fun `test time conversion`() {
        val input1 = "12:30"
        val converted1 = NauSysTimeWrapper(input1)
        assertEquals(LocalTime.of(12, 30), converted1.value)

        val input2 = "12:30:30"
        val converted2 = NauSysTimeWrapper(input2)
        assertEquals(LocalTime.of(12, 30, 30), converted2.value)
    }
}
