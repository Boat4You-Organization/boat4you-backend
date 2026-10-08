package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R__1_05 is the single writer of equipment name / category / filter_order / match_keys (equipment audit 8.10.2026):
 * the keys were overwritten three times while V-migrations wrote them too. This pins the seed's shape and keeps every
 * later V-migration away from those columns.
 */
class EquipmentSeedConsistencyTest {
    private val rows = EquipmentCatalogueFixture.seedRows
    private val prefixes = listOf("token-match:", "full-match:", "case:", "case-substring:", "not:")

    @Test
    fun `108 rows, unique label codes and seed order, known categories, unique filter positions`() {
        assertEquals(108, rows.size)
        assertEquals(rows.size, rows.map { it.labelCode }.toSet().size)
        assertEquals((1..108).toList(), rows.map { it.seedOrder })
        rows.forEach { CategoryEnum.valueOf(it.category) }
        val filters = rows.mapNotNull { it.filterOrder }
        assertEquals(filters.size, filters.toSet().size)
        assertTrue(rows.any { it.labelCode == "depth-sounder" && it.category == "NAVIGATION" && it.filterOrder == null })
        assertTrue(rows.any { it.labelCode == "cockpit-cushions" && it.category == "COMFORT" && it.filterOrder == null })
    }

    @Test
    fun `aliases carry empty keys, every other row prefixed keys without apostrophes or empty entries`() {
        rows.forEach { row ->
            if (row.labelCode in EquipmentCatalogueFixture.ALIASES) {
                assertEquals("", row.matchKeys, row.labelCode)
                assertEquals(null, row.filterOrder, row.labelCode)
                return@forEach
            }
            val keys = row.matchKeys.split(",").map { it.trim() }
            assertTrue(keys.isNotEmpty() && keys.none { it.isEmpty() }, "${row.labelCode}: empty key")
            keys.forEach { key ->
                assertTrue(prefixes.any { key.startsWith(it) }, "${row.labelCode}: '$key' has no known prefix")
                assertTrue(!key.contains('\''), "${row.labelCode}: '$key'")
            }
            assertTrue(keys.any { !it.startsWith("not:") }, "${row.labelCode}: only vetoes")
        }
        assertTrue(rows.none { it.matchKeys.contains("case-substring:") }, "case-substring is retired in v2")
    }

    @Test
    fun `no V-migration after the equipment fix writes the catalogue columns`() {
        val version = Regex("""^V(\d+)_(\d+)__.*\.sql$""")
        val later =
            PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql")
                .mapNotNull { resource ->
                    val name = resource.filename ?: return@mapNotNull null
                    val (major, minor) = version.find(name)?.destructured ?: return@mapNotNull null
                    if (major.toInt() > 9 || (major.toInt() == 9 && minor.toInt() > 75)) resource else null
                }
        val offenders = later.filter { it.inputStream.bufferedReader().readText().contains("match_keys", ignoreCase = true) }.map { it.filename }
        assertEquals(emptyList(), offenders, "equipment keys live in R__1_05 only")
    }

    @Test
    fun `the migrations reference equipment by label_code, never by a numeric id`() {
        val numericId = Regex("""(?i)\b(equipment_id|merged_into_id|e\.id|equipment\.id)\s*(=|<>|IN\s*\()\s*\d""")
        listOf(EquipmentCatalogueFixture.R105, EquipmentCatalogueFixture.V_SCHEMA, EquipmentCatalogueFixture.V_DATA).forEach { path ->
            val sql = EquipmentCatalogueFixture.sql(path)
            assertEquals(null, numericId.find(sql)?.value, path)
        }
    }
}
