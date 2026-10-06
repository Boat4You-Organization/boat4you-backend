package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.enums.SailKind
import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sail labels -> display kind, and the corrected filter enum (capacity contract v1, 5.4; Mario decision 3). */
class SailLabelsTest {
    @Test
    fun `MMK labels map to the filter enum - battens, classic and jibs CLASSIC, furling ROLLING, none UNKNOWN`() {
        val table =
            mapOf(
                "Full batten" to SailTypeEnum.CLASSIC_SAIL,
                "Semi full batten" to SailTypeEnum.CLASSIC_SAIL,
                "Half batten" to SailTypeEnum.CLASSIC_SAIL,
                "Classic" to SailTypeEnum.CLASSIC_SAIL,
                "Standard" to SailTypeEnum.CLASSIC_SAIL,
                "Self tacking jib" to SailTypeEnum.CLASSIC_SAIL,
                "Jib" to SailTypeEnum.CLASSIC_SAIL,
                "Furling" to SailTypeEnum.ROLLING_SAIL,
                "Roll" to SailTypeEnum.ROLLING_SAIL,
                "None" to SailTypeEnum.UNKNOWN,
                "" to SailTypeEnum.UNKNOWN,
                "Lattengroß" to SailTypeEnum.UNKNOWN,
            )
        table.forEach { (label, expected) -> assertEquals(expected, SailTypeEnum.fromMmkValue(label), label) }
        assertEquals(SailTypeEnum.UNKNOWN, SailTypeEnum.fromMmkValue(null))
    }

    @Test
    fun `NauSys sail ids map to the filter enum`() {
        assertEquals(SailTypeEnum.ROLLING_SAIL, SailTypeEnum.fromNausysValue(1))
        listOf(3, 4, 112782, 492236, 10403978).forEach { assertEquals(SailTypeEnum.CLASSIC_SAIL, SailTypeEnum.fromNausysValue(it), "id $it") }
        listOf(null, 0, 2, 999).forEach { assertEquals(SailTypeEnum.UNKNOWN, SailTypeEnum.fromNausysValue(it), "id $it") }
    }

    @Test
    fun `display kind from the partner label`() {
        val table =
            mapOf(
                "Full batten" to SailKind.FULL_BATTEN,
                "full batten" to SailKind.FULL_BATTEN,
                "Semi full batten" to SailKind.SEMI_FULL_BATTEN,
                "half batten" to SailKind.HALF_BATTEN,
                "Furling" to SailKind.FURLING,
                "furling/roll" to SailKind.FURLING,
                "Self tacking jib" to SailKind.SELF_TACKING_JIB,
                "Self-tacking jib" to SailKind.SELF_TACKING_JIB,
                "jib" to SailKind.JIB,
                "classic/standard" to SailKind.CLASSIC,
                "Classic" to SailKind.CLASSIC,
            )
        table.forEach { (label, expected) -> assertEquals(expected, SailLabels.kind(label), label) }
        SailLabels.NAUSYS_SAIL_LABELS.values.forEach { assertTrue(SailLabels.kind(it) != null, it) }
        assertNull(SailLabels.kind("None"))
        assertTrue(SailLabels.isNone("None"))
        assertTrue(SailLabels.isNone(" none "))
        assertFalse(SailLabels.isNone("Furling"))
        assertNull(SailLabels.kind("Code zero"), "outside the closed set: shown as the partner's label")
        assertNull(SailLabels.kind(null))
    }
}
