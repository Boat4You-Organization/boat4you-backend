package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Manufacturer
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * MMK answers with no yachts for an agency (Multihull Yachting 1605, 22.10.2026): the first answer marks the agency,
 * the answer still empty two nights later switches every yacht off, a partner-wide outage switches nothing off.
 */
class MmkYachtSyncEmptyFleetTests {
    private val now = Instant.parse("2026-10-25T06:10:00Z")
    private val agency =
        Agency().apply {
            id = 1605L
            name = "Multihull Yachting"
        }
    private val boats =
        listOf(
            Yacht().apply {
                id = 1L
                sysActive = true
            },
            Yacht().apply {
                id = 2L
                sysActive = true
            },
        )
    private val saved = mutableListOf<Yacht>()
    private val savedAgencies = mutableListOf<Agency>()
    private var emptyAgencies = 8L

    private val yachts =
        mock(YachtRepository::class.java) { inv ->
            when (inv.method.name) {
                "findAllByAgencyAndSysActiveTrue" -> boats.filter { it.sysActive == true }
                "findAllByAgencyAndExternalIdNotIn" -> emptyList<Yacht>()
                "save" -> inv.getArgument<Yacht>(0).also { saved += it }
                else -> null
            }
        }
    private val agencies =
        mock(AgencyRepository::class.java) { inv ->
            when (inv.method.name) {
                "findById" -> Optional.of(agency)
                "save" -> inv.getArgument<Agency>(0).also { savedAgencies += it }
                "countByPartnerFleetEmptySinceIsNotNull" -> emptyAgencies
                else -> null
            }
        }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }
    private val mappings =
        mock(ExternalMappingService::class.java) { inv ->
            when (inv.method.name) {
                "getAllMappingsByTypeAndExtendedType", "getCachedAllMappingsByType" -> emptyList<ExternalMapping>()
                else -> null
            }
        }
    private val locations =
        mock(LocationQueryingService::class.java) { inv ->
            if (inv.method.name == "getInlandLocationExternalIds") emptySet<Long>() else null
        }
    private val manufacturers =
        mock(ManufacturerRepository::class.java) { inv ->
            if (inv.method.name == "findAll" && inv.arguments.isEmpty()) emptyList<Manufacturer>() else null
        }

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
            equipmentLinkResolver = any(),
            modelQueryingService = any(),
            externalEquipmentRepository = any(),
            extraRepository = any(),
            yachtExtraRepository = any(),
            fileSystemService = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            agencyRepository = agencies,
            modelNameNormaliser = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        ).also { it.clock = Clock.fixed(now, ZoneOffset.UTC) }

    @Test
    fun `the first empty answer marks the agency and switches nothing off`() {
        service.syncYachtsForAgency(agency.id!!, emptyList())

        agency.partnerFleetEmptySince shouldBe now
        savedAgencies shouldContainExactly listOf(agency)
        saved.shouldBeEmpty()
        boats.map { it.sysActive } shouldBe listOf(true, true)
    }

    @Test
    fun `an answer empty for less than two nights switches nothing off`() {
        val since = now.minus(Duration.ofHours(30))
        agency.partnerFleetEmptySince = since

        service.syncYachtsForAgency(agency.id!!, emptyList())

        saved.shouldBeEmpty()
        savedAgencies.shouldBeEmpty()
        agency.partnerFleetEmptySince shouldBe since
    }

    @Test
    fun `an answer empty for two nights mirrors the partner - every yacht goes off`() {
        agency.partnerFleetEmptySince = Instant.parse("2026-10-22T06:10:00Z")

        service.syncYachtsForAgency(agency.id!!, emptyList())

        boats.map { it.sysActive } shouldBe listOf(false, false)
        saved shouldContainExactly boats
    }

    @Test
    fun `more than fifty agencies empty at once is MMK being down - nothing goes off`() {
        agency.partnerFleetEmptySince = Instant.parse("2026-10-22T06:10:00Z")
        emptyAgencies = 51L

        service.syncYachtsForAgency(agency.id!!, emptyList())

        saved.shouldBeEmpty()
        boats.map { it.sysActive } shouldBe listOf(true, true)
    }
}
