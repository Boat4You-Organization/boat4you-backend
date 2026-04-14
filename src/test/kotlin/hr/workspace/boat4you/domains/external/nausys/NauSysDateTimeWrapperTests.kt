package hr.workspace.boat4you.domains.external.nausys

import hr.workspace.boat4you.domains.external.nausys.model.NauSysDateTimeWrapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class NauSysDateTimeWrapperTests {
    @Test
    fun `test time conversion`() {
        val input1 = "15.09.2024 12:30"
        val converted1 = NauSysDateTimeWrapper(input1)
        assertEquals(LocalDateTime.of(2024, 9, 15, 12, 30), converted1.value)

        val input2 = "15.09.2024 12:30:30"
        val converted2 = NauSysDateTimeWrapper(input2)
        assertEquals(LocalDateTime.of(2024, 9, 15, 12, 30, 30), converted2.value)
    }
}
