package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * NauSys lists no yachts for an agency: the first answer marks the agency, the answer still empty two nights later
 * switches every yacht off, and a listed fleet clears the mark again.
 */
class NauSysYachtSyncEmptyFleetTests {
    private val now = Instant.parse("2026-10-25T06:10:00Z")
    private val agency =
        Agency().apply {
            id = 1795L
            name = "Hibo Yachtcharter"
        }
    private val boats =
        listOf(
            Yacht().apply {
                id = 88L
                sysActive = true
            },
            Yacht().apply {
                id = 89L
                sysActive = true
            },
        )
    private val saved = mutableListOf<Yacht>()
    private val savedAgencies = mutableListOf<Agency>()

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
                "countByPartnerFleetEmptySinceIsNotNull" -> 3L
                else -> null
            }
        }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        NauSysYachtSyncService(
            yachtRepository = yachts,
            externalMappingRepository = any(),
            externalSystemService = any(),
            modelRepository = any(),
            externalMappingService = any(),
            yachtImageRepository = any(),
            reservationOptionRepository = any(),
            yachtEquipmentRepository = any(),
            equipmentLinkResolver = any(),
            locationQueryingService = any(),
            externalEquipmentRepository = any(),
            yachtExtraRepository = any(),
            extraRepository = any(),
            languageRepository = any(),
            yachtTranslationRepository = any(),
            fileSystemService = any(),
            agencyRepository = agencies,
            externalSeasonRepository = any(),
            realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(),
        ).also { it.clock = Clock.fixed(now, ZoneOffset.UTC) }

    @Test
    fun `the first empty answer marks the agency and switches nothing off`() {
        service.deactivateYachtsForAgency(agency.id!!, emptyList())

        agency.partnerFleetEmptySince shouldBe now
        savedAgencies shouldContainExactly listOf(agency)
        saved.shouldBeEmpty()
    }

    @Test
    fun `an answer empty for two nights mirrors the partner - every yacht goes off`() {
        agency.partnerFleetEmptySince = Instant.parse("2026-10-22T06:10:00Z")

        service.deactivateYachtsForAgency(agency.id!!, emptyList())

        boats.map { it.sysActive } shouldBe listOf(false, false)
        saved shouldContainExactly boats
    }

    @Test
    fun `a listed fleet clears the mark`() {
        agency.partnerFleetEmptySince = Instant.parse("2026-10-24T06:10:00Z")

        service.deactivateYachtsForAgency(agency.id!!, listOf(501L, 502L))

        agency.partnerFleetEmptySince shouldBe null
        savedAgencies shouldContainExactly listOf(agency)
        saved.shouldBeEmpty()
    }
}
