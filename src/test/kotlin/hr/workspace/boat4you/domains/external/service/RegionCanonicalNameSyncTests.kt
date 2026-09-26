package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Region
import hr.workspace.boat4you.domains.catalouge.jpa.RegionAliasRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.mmk.service.MmkCatalogueSyncService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysCatalogueSyncService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.SailingArea
import org.openapitools.client.nausys.model.RestInternationalText
import org.openapitools.client.nausys.model.RestRegion
import org.openapitools.client.nausys.model.RestRegionList
import java.util.Optional

/**
 * 26.9.2026 audit B01 (critical): r-3 / r-4 / r-5 / r-193 are mapped by BOTH partners and their name - the key of the
 * region landing URL, canonical, sitemap and index gate - flipped with whichever catalogue sync ran last ("Zadar" /
 * "Zadar region", "Istria / Kvarner" / "Kvarner"). A sync now never renames an existing region; it records the partner's
 * spelling as an alias, and an unmapped area whose name is a known spelling joins that region instead of forking one.
 * Repositories are answer-by-method-name mocks over an in-memory region table.
 */
class RegionCanonicalNameSyncTests {
    private val regions =
        mutableMapOf(
            3 to region(3, "Zadar", "HR"),
            193 to region(193, "Istria / Kvarner", "HR"),
        )

    /** (region id, spelling, source) as recorded. */
    private val aliases = mutableListOf(Triple(193, "Kvarner", "HISTORIC"))
    private val savedMappings = mutableListOf<Pair<Long, Long>>()

    private fun region(
        id: Int,
        name: String,
        cc: String,
    ) = Region().apply {
        this.id = id
        this.name = name
        this.countryCode = cc
    }

    private val regionRepository =
        mock(RegionRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.ofNullable(regions[(inv.getArgument<Long>(0)).toInt()])
                "findByName" -> regions.values.firstOrNull { it.name == inv.getArgument<String>(0) }
                "findByAlias" -> {
                    val name = inv.getArgument<String>(0).lowercase()
                    aliases.firstOrNull { it.second.lowercase() == name }?.let { regions[it.first] }
                }
                "saveAndFlush" ->
                    inv.getArgument<Region>(0).also { r ->
                        if (r.id == null) r.id = 1000 + regions.size
                        regions[r.id!!] = r
                    }
                else -> null
            }
        }
    private val regionAliasRepository =
        mock(RegionAliasRepository::class.java) { inv ->
            if (inv.method.name == "record") {
                val regionId = inv.getArgument<Int>(0)
                val alias = inv.getArgument<String>(1)
                aliases.removeIf { it.first == regionId && it.second.equals(alias, ignoreCase = true) }
                aliases.add(Triple(regionId, alias, inv.getArgument(2)))
                1
            } else {
                null
            }
        }

    private fun mappings(vararg pairs: Pair<Long, Long>) =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByType" ->
                    when (inv.getArgument<String>(0)) {
                        "Region" -> pairs.map { (ext, sys) -> ExternalMapping(ext, sys, "Region", null, null) }
                        "Country" -> listOf(ExternalMapping(1L, 54L, "Country", null, null))
                        else -> emptyList<ExternalMapping>()
                    }
                "saveMapping" -> {
                    savedMappings.add(inv.getArgument<Long>(0) to inv.getArgument<Long>(1))
                    null
                }
                else -> null
            }
        }

    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private val countries =
        mock(CountryRepository::class.java) { inv ->
            if (inv.method.name == "findById") {
                Optional.of(
                    Country().apply {
                        id = 54
                        code2 = "HR"
                    },
                )
            } else {
                null
            }
        }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private fun mmk(mappingService: ExternalMappingService) =
        MmkCatalogueSyncService(
            externalSystemService = systems,
            externalMappingService = mappingService,
            countryRepository = countries,
            agencyRepository = any(),
            agencySourceRepository = any(),
            regionRepository = regionRepository,
            regionAliasRepository = regionAliasRepository,
            manufacturerRepository = any(),
            locationQueryingService = any(),
            locationRepository = any(),
            externalEquipmentRepository = any(),
            manufacturerAliasResolver = any(),
        )

    private fun nausys(mappingService: ExternalMappingService) =
        NauSysCatalogueSyncService(
            externalSystemService = systems,
            externalMappingService = mappingService,
            countryRepository = countries,
            regionRepository = regionRepository,
            regionAliasRepository = regionAliasRepository,
            locationRepository = any(),
            locationQueryingService = any(),
            manufacturerRepository = any(),
            modelRepository = any(),
            categoryRepository = any(),
            agencyRepository = any(),
            agencySourceRepository = any(),
            externalEquipmentRepository = any(),
            externalSeasonRepository = any(),
            externalBaseRepository = any(),
            yachtRepository = any(),
            manufacturerAliasResolver = any(),
            modelNameNormaliser = any(),
        )

    private fun nausysRegions(vararg r: Pair<Long, String>) =
        RestRegionList(regions = r.map { (id, name) -> RestRegion(id = id, name = RestInternationalText(textEN = name), countryId = 1L) })

    @Test
    fun `a partner rename of a mapped region never moves its name - both syncs, in either order`() {
        // MMK area 100 and NauSys region 200 both map r-3; each partner spells it differently
        nausys(mappings(200L to 3L)).regionsSync(nausysRegions(200L to "Zadar region"))
        mmk(mappings(100L to 3L)).updateMmkSailigAreas(listOf(SailingArea(100L, "Zadar")))
        nausys(mappings(200L to 3L)).regionsSync(nausysRegions(200L to "Zadar region"))

        regions.getValue(3).name shouldBe "Zadar"
        aliases.filter { it.first == 3 }.map { it.second to it.third } shouldContainExactlyInAnyOrder
            listOf("Zadar region" to "NAUSYS", "Zadar" to "MMK")
        // the NauSys sync still owns the region's country
        regions.getValue(3).countryCode shouldBe "HR"
    }

    @Test
    fun `an unmapped area under a known spelling joins that region, a new name creates a region named after it`() {
        nausys(mappings()).regionsSync(nausysRegions(300L to "Kvarner"))
        mmk(mappings()).updateMmkSailigAreas(listOf(SailingArea(400L, "Lošinj Archipelago")))

        regions.getValue(193).name shouldBe "Istria / Kvarner"
        val created = regions.values.single { it.id!! >= 1000 }
        created.name shouldBe "Lošinj Archipelago"
        savedMappings shouldContainExactly listOf(300L to 193L, 400L to created.id!!.toLong())
    }
}
