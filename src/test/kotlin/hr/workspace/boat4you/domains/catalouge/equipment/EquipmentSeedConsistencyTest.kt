package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R__1_05 is the single writer of equipment name / category / filter_order / match_keys, of the merged codes and of the
 * explicit partner links (equipment audit 8.10.2026): the keys were overwritten three times while V-migrations wrote
 * them too. This pins the seed's shape and keeps every later migration away from the catalogue.
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
    fun `no later V-migration and no other repeatable writes the catalogue, the merges or the explicit links`() {
        val version = Regex("""^V(\d+)_(\d+)__.*\.sql$""")
        val resources = PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql").toList()
        val later =
            resources.filter { resource ->
                val name = resource.filename ?: return@filter false
                if (name.startsWith("R__")) return@filter name != "R__1_05_equipment_import.sql"
                val (major, minor) = version.find(name)?.destructured ?: return@filter false
                major.toInt() > 9 || (major.toInt() == 9 && minor.toInt() > 75)
            }
        assertTrue(later.any { it.filename!!.startsWith("R__") }, "the other repeatables are scanned too")
        val writes =
            Regex(
                """(?i)\b(UPDATE|INSERT\s+INTO|DELETE\s+FROM|TRUNCATE|ALTER\s+TABLE|COPY)\s+(TABLE\s+)?(ONLY\s+)?(IF\s+EXISTS\s+)?(public\.)?"?(equipment|partner_equipment_mapping)\b(?!_)|\bmerged_into_id\b""",
            )
        val offenders =
            later.mapNotNull { resource ->
                writes.find(resource.inputStream.bufferedReader().readText())?.let { "${resource.filename}: ${it.value}" }
            }
        assertEquals(emptyList(), offenders, "the equipment catalogue lives in R__1_05 only")
        assertTrue(writes.containsMatchIn("UPDATE equipment SET filter_order = 1"), "V9_20-style write")
        assertTrue(writes.containsMatchIn("insert into equipment (name) values ('x')"))
        assertTrue(writes.containsMatchIn("INSERT INTO partner_equipment_mapping (note) VALUES ('x')"))
        assertTrue(writes.containsMatchIn("UPDATE public.equipment SET match_keys = ''"))
        assertTrue(!writes.containsMatchIn("SELECT e.label_code FROM yacht_equipment ye JOIN equipment e ON e.id = ye.equipment_id"))
        assertTrue(!writes.containsMatchIn("UPDATE yacht_equipment SET equipment_id = NULL"))
        assertTrue(!writes.containsMatchIn("UPDATE extras SET match_keys = '', filter_order = 1"), "R__1_04 writes extras, not equipment")
    }

    @Test
    fun `R__1_05 owns the merges and the 80 explicit links, by label_code`() {
        val sql = EquipmentCatalogueFixture.sql(EquipmentCatalogueFixture.R105)
        EquipmentCatalogueFixture.ALIASES.forEach { (alias, canonical) -> assertTrue(sql.contains("('$alias', '$canonical')"), alias) }
        val mappings = EquipmentCatalogueFixture.seedMappings
        assertEquals(80, mappings.size)
        assertEquals(mappings.size, mappings.map { Triple(it.systemId, it.partnerItemId, it.nameNorm) }.toSet().size)
        val labels = rows.filter { it.labelCode !in EquipmentCatalogueFixture.ALIASES }.map { it.labelCode }.toSet() + "NONE"
        mappings.forEach { assertTrue(it.targetLabel in labels, "${it.nameNorm}: ${it.targetLabel}") }
        mappings.forEach { assertTrue((it.partnerItemId > 0) == (it.nameNorm == ""), it.nameNorm) }
        assertTrue(!EquipmentCatalogueFixture.sql(EquipmentCatalogueFixture.V_SCHEMA).contains("INSERT INTO partner_equipment_mapping"))
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
