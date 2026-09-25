package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtSyncService.SkipReason
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.Product
import java.util.Optional

/**
 * 25.9.2026 sea charter only: an MMK yacht whose shipyard maps to an inland-only builder (Le Boat), or that is based at
 * an inland location (location.inland, V9_64 - a sea agency's boats at Lemmer), is never imported, and one imported
 * earlier is switched off (row and mapping kept). A sea builder's yacht at a sea base is untouched by the rule.
 * Repositories are answer-by-method-name mocks; only the sea base 1 has a location mapping.
 */
class MmkYachtSyncInlandSkipTests {
    private val leBoatShipyard = 300L
    private val bavariaShipyard = 301L
    private val seaBase = 1L
    private val lemmerBase = 1723L
    private val inlandBases = setOf(lemmerBase)
    private val agency =
        Agency().apply {
            id = 1883L
            name = "Le Boat"
        }
    private val existing =
        Yacht().apply {
            id = 77L
            name = "Caprice Comfort 51"
            sysActive = true
        }

    // same agency, imported earlier, based at sea
    private val seaYacht =
        Yacht().apply {
            id = 78L
            name = "Sea Yacht"
            sysActive = true
        }
    private val seaLocation = Location().apply { id = 40L }
    private val saved = mutableListOf<Yacht>()

    private fun mapping(
        externalId: Long,
        systemId: Long,
    ) = ExternalMapping(externalId, systemId, null, null, null)

    private fun manufacturer(
        id: Long,
        name: String,
    ) = Manufacturer().also {
        it.id = id
        it.name = name
    }

    private val manufacturers =
        mock(ManufacturerRepository::class.java) { inv ->
            if (inv.method.name == "findAll" && inv.arguments.isEmpty()) {
                listOf(manufacturer(9L, "Le Boat"), manufacturer(10L, "Bavaria"))
            } else {
                null
            }
        }
    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByType" -> listOf(mapping(leBoatShipyard, 9L), mapping(bavariaShipyard, 10L))
                "getAllMappingsByTypeAndExtendedType" -> listOf(mapping(500L, existing.id!!), mapping(502L, seaYacht.id!!))
                // Location mappings: MMK home base 1 -> our sea location
                "getCachedAllMappingsByType" -> listOf(mapping(seaBase, seaLocation.id!!))
                else -> null
            }
        }
    private val locations =
        mock(LocationQueryingService::class.java) { inv ->
            when (inv.method.name) {
                "getInlandLocationExternalIds" -> inlandBases
                "getCachedLocationById" -> seaLocation
                else -> null
            }
        }
    private val yachts =
        mock(YachtRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.ofNullable(listOf(existing, seaYacht).find { inv.arguments[0] == it.id })
                "save" -> inv.getArgument<Yacht>(0).also { saved += it }
                "findAllByAgencyAndExternalIdNotIn" -> emptyList<Yacht>()
                else -> null
            }
        }
    private val agencies =
        mock(AgencyRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(agency) else null }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        MmkYachtSyncService(
            yachtRepository = yachts,
            externalMappingRepository = any(),
            externalSystemService = systems,
            modelRepository = any(),
            externalMappingService = mappings,
            yachtImageRepository = any(),
            manufacturerRepository = manufacturers,
            locationQueryingService = locations,
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentRepository = any(),
            modelQueryingService = any(),
            externalEquipmentRepository = any(),
            extraRepository = any(),
            yachtExtraRepository = any(),
            fileSystemService = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            agencyRepository = agencies,
            modelNameNormaliser = any(),
        )

    private fun mmkYacht(
        id: Long,
        shipyardId: Long?,
        products: List<String> = listOf("bareboat"),
        kind: String = "Motor boat",
        model: String? = null,
        homeBaseId: Long = seaBase,
    ) = org.openapitools.client.mmk.model.Yacht(
        id = id,
        name = "Yacht $id",
        kind = kind,
        homeBaseId = homeBaseId,
        homeBase = "Base",
        companyId = 1L,
        company = "Company",
        shipyardId = shipyardId,
        model = model,
        products = products.map { Product(name = it, extras = emptyList()) },
    )

    @Test
    fun `inland shipyards are resolved through our manufacturer names`() {
        service.inlandShipyardIds() shouldBe setOf(leBoatShipyard)
    }

    @Test
    fun `an inland builder is skipped first, a sea builder is eligible`() {
        val inland = setOf(leBoatShipyard)
        service.skipReason(mmkYacht(1, leBoatShipyard), inland, inlandBases) shouldBe SkipReason.INLAND_VESSEL
        // inland wins over the other reasons, so the sync always switches such a yacht off
        service.skipReason(mmkYacht(2, leBoatShipyard, products = emptyList()), inland, inlandBases) shouldBe SkipReason.INLAND_VESSEL
        service.skipReason(mmkYacht(3, bavariaShipyard), inland, inlandBases) shouldBe null
        service.skipReason(mmkYacht(4, null), inland, inlandBases) shouldBe null
        service.skipReason(mmkYacht(5, bavariaShipyard, products = emptyList()), inland, inlandBases) shouldBe SkipReason.NO_VALID_PRODUCTS
        service.shouldSkip(mmkYacht(6, leBoatShipyard), inland, inlandBases) shouldBe true
        service.shouldSkip(mmkYacht(7, bavariaShipyard), inland, inlandBases) shouldBe false
    }

    @Test
    fun `a shipyard without a manufacturer row is caught by the model name`() {
        // Kuhnle-Tours: MMK shipyard never mapped to a manufacturer of ours, the model name still names the builder
        val inland = setOf(leBoatShipyard)
        service.skipReason(mmkYacht(8, null, model = "Kormoran 1140"), inland, inlandBases) shouldBe SkipReason.INLAND_VESSEL
        service.skipReason(mmkYacht(9, 999L, model = "Pedro Skiron 35 "), inland, inlandBases) shouldBe SkipReason.INLAND_VESSEL
        service.skipReason(mmkYacht(10, null, model = "Bavaria 40 Vision"), inland, inlandBases) shouldBe null
        service.skipReason(mmkYacht(11, null, model = "Triton 48 - 4 + 1 cab."), inland, inlandBases) shouldBe null
    }

    @Test
    fun `an already imported inland yacht found by model name is switched off`() {
        service.syncYachtsForAgency(agency.id!!, listOf(mmkYacht(500L, null, model = "Kormoran 940")))

        existing.sysActive shouldBe false
        saved shouldContainExactly listOf(existing)
    }

    @Test
    fun `an already imported inland yacht is switched off, a new one is not imported`() {
        service.syncYachtsForAgency(agency.id!!, listOf(mmkYacht(500L, leBoatShipyard), mmkYacht(501L, leBoatShipyard)))

        existing.sysActive shouldBe false
        saved shouldContainExactly listOf(existing)
    }

    @Test
    fun `a sea builder at an inland base is skipped, at a sea base eligible`() {
        val inland = setOf(leBoatShipyard)
        service.skipReason(mmkYacht(12, bavariaShipyard, homeBaseId = lemmerBase), inland, inlandBases) shouldBe SkipReason.INLAND_VESSEL
        service.skipReason(mmkYacht(13, bavariaShipyard, homeBaseId = seaBase), inland, inlandBases) shouldBe null
        service.shouldSkip(mmkYacht(14, bavariaShipyard, homeBaseId = lemmerBase), inland, inlandBases) shouldBe true
    }

    @Test
    fun `a sea agency's yacht at an inland base is switched off, its yacht at a sea base stays`() {
        // Starsails: Bavaria at Lemmer (IJsselmeer) goes off, the same agency's Bavaria at sea is synced as usual;
        // a new one at Lemmer is not imported
        service.syncYachtsForAgency(
            agency.id!!,
            listOf(
                mmkYacht(500L, bavariaShipyard, homeBaseId = lemmerBase),
                mmkYacht(502L, bavariaShipyard, homeBaseId = seaBase),
                mmkYacht(503L, bavariaShipyard, homeBaseId = lemmerBase),
            ),
        )

        existing.sysActive shouldBe false
        seaYacht.sysActive shouldBe true
        seaYacht.location shouldBe seaLocation
        saved shouldContainExactly listOf(existing, seaYacht)
    }

    @Test
    fun `an inland yacht already off is left alone`() {
        existing.sysActive = false

        service.syncYachtsForAgency(agency.id!!, listOf(mmkYacht(500L, leBoatShipyard)))

        saved shouldContainExactly emptyList()
    }
}
