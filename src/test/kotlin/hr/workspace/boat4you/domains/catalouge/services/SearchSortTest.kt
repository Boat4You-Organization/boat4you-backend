package hr.workspace.boat4you.domains.catalouge.services

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SearchSort.KNOWN must list exactly the sorts the listing query orders by: a branch missing from KNOWN would be
 * normalised away by the controller (R12) and silently become the Recommended order.
 */
class SearchSortTest {
    @Test
    fun `normalize keeps known sorts and maps everything else to null`() {
        SearchSort.KNOWN.forEach { assertEquals(it, SearchSort.normalize(it)) }
        listOf(null, "", "recommended", "recommendedScore", "ASC", " asc", "zzz", "price").forEach {
            assertNull(SearchSort.normalize(it), "$it")
        }
    }

    @Test
    fun `every branch of the sortBy when is in KNOWN, and the only literal labels are the Recommended ones`() {
        val source = File("src/main/kotlin/hr/workspace/boat4you/domains/catalouge/services/YachtQueryingService.kt").readText()
        val start = source.indexOf("when (sortBy) {")
        val end = source.indexOf("cq.orderBy(primaryOrders", start)
        val block = source.substring(start, end)

        val constants = Regex("""SearchSort\.([A-Z_]+) ->""").findAll(block).map { it.groupValues[1] }.toSet()
        val byName = SearchSort::class.java.declaredFields.filter { it.type == String::class.java }.associate { it.name to it.get(null) }
        assertEquals(SearchSort.KNOWN, constants.map { byName.getValue(it) }.toSet())

        val literals = Regex(""""([A-Za-z]*)"(?:, "([A-Za-z]*)")* ->""").findAll(block).flatMap { m -> m.groupValues.drop(1) }.filter { it.isNotEmpty() }
        assertEquals(setOf("recommendedScore", "recommended"), literals.toSet())
    }
}
