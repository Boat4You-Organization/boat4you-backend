package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMapping
import org.springframework.core.io.ClassPathResource

/**
 * The equipment catalogue exactly as the migrations ship it: rows and keys from the R__1_05 seed VALUES (ids = seed
 * order = prod ids), the three merges (V9_75 and R__1_05) and the explicit links seeded by R__1_05. Tests read the SQL
 * itself, so a key change is tested the moment it is written.
 */
object EquipmentCatalogueFixture {
    data class SeedRow(
        val seedOrder: Int,
        val labelCode: String,
        val name: String,
        val category: String,
        val filterOrder: Short?,
        val matchKeys: String,
    )

    data class SeedMapping(
        val systemId: Int,
        val partnerItemId: Long,
        val nameNorm: String,
        val targetLabel: String,
    )

    /** alias -> canonical (V9_75 S1, R__1_05). */
    val ALIASES: Map<String, String> = mapOf("bow-thruster-deck" to "bow-thruster", "refrigerator" to "fridge", "sundeck-cushions" to "sun-pads")

    const val R105 = "db/migration/R__1_05_equipment_import.sql"
    const val V_SCHEMA = "db/migration/V9_74__equipment_alias_and_partner_mapping.sql"
    const val V_DATA = "db/migration/V9_75__fix_equipment_links.sql"

    private val SEED_ROW = Regex("""^\s*\((\d+), '([^']*)', '([^']*)', '([A-Z_]+)', (NULL|\d+), '([^']*)'\)[,;]""")
    private val MAPPING_ROW = Regex("""^\s*\(\d+, (\d+), (-?\d+), '((?:[^']|'')*)', '([^']*)', '(?:[^']|'')*'\)[,;]""")

    fun sql(path: String): String = ClassPathResource(path).inputStream.bufferedReader().readText()

    val seedRows: List<SeedRow> by lazy {
        sql(R105).lines().mapNotNull { line ->
            SEED_ROW.find(line)?.destructured?.let { (order, label, name, category, filter, keys) ->
                SeedRow(order.toInt(), label, name, category, filter.takeIf { it != "NULL" }?.toShort(), keys)
            }
        }
    }

    val seedMappings: List<SeedMapping> by lazy {
        sql(R105).lines().mapNotNull { line ->
            MAPPING_ROW.find(line)?.destructured?.let { (system, item, norm, label) ->
                SeedMapping(system.toInt(), item.toLong(), norm.replace("''", "'"), label)
            }
        }
    }

    /** The catalogue as Equipment entities, ids = seed order (the prod layout) unless [idOf] says otherwise. */
    fun catalogue(idOf: (SeedRow) -> Long = { it.seedOrder.toLong() }): List<Equipment> {
        val rows = seedRows.associateWith { row -> equipment(idOf(row), row) }
        val byLabel = rows.values.associateBy { it.labelCode }
        ALIASES.forEach { (alias, canonical) -> byLabel.getValue(alias).mergedIntoId = byLabel.getValue(canonical).id }
        return rows.values.sortedBy { it.id }
    }

    fun mappings(catalogue: List<Equipment>): List<PartnerEquipmentMapping> {
        val byLabel = catalogue.associateBy { it.labelCode }
        return seedMappings.mapIndexed { index, seed ->
            PartnerEquipmentMapping().apply {
                id = index + 1L
                externalSystemId = seed.systemId
                partnerItemId = seed.partnerItemId
                partnerNameNorm = seed.nameNorm
                equipmentId = if (seed.targetLabel == "NONE") null else byLabel.getValue(seed.targetLabel).id
                note = "seed"
            }
        }
    }

    fun equipment(
        id: Long,
        row: SeedRow,
    ): Equipment =
        Equipment().apply {
            this.id = id
            labelCode = row.labelCode
            name = row.name
            category = CategoryEnum.valueOf(row.category)
            filterOrder = row.filterOrder
            matchKeys = row.matchKeys
        }

    /** One partner name of the 8.10.2026 prod snapshot with the matcher's code and the final link (NONE = no link). */
    data class PartnerName(
        val systemId: Int,
        val partnerItemId: Long,
        val name: String,
        val matcher: String,
        val final: String,
    )

    val partnerNames: List<PartnerName> by lazy {
        ClassPathResource("equipment/partner_names_8_10.tsv")
            .inputStream
            .bufferedReader()
            .readLines()
            .filterNot { it.startsWith("#") || it.startsWith("system\t") || it.isEmpty() }
            .map { line ->
                val f = line.split('\t')
                PartnerName(f[0].toInt(), f[1].toLong(), f[2], f[3], f[4])
            }
    }
}
