package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySource
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySourceRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.Company

/**
 * 25.9.2026 sea charter only: the MMK agency mirror creates a NEW company whose name reads like a river/canal operator
 * inactive, as a manual OFF (syncDeactivatedBy null, so the mirror never re-activates it); a sea company stays active.
 */
class MmkAgencyMirrorRiverOperatorTests {
    private val created = mutableMapOf<String, Agency>()

    private val agencies =
        mock(AgencyRepository::class.java) { inv ->
            if (inv.method.name == "saveAndFlush") inv.getArgument<Agency>(0).also { created[it.name!!] = it } else null
        }
    private val sources =
        mock(AgencySourceRepository::class.java) { inv ->
            when (inv.method.name) {
                "findAllByExternalSystemId" -> emptyList<AgencySource>()
                "save" -> inv.getArgument(0)
                else -> null
            }
        }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        MmkCatalogueSyncService(
            externalSystemService = systems,
            externalMappingService = any(),
            countryRepository = any(),
            agencyRepository = agencies,
            agencySourceRepository = sources,
            regionRepository = any(),
            manufacturerRepository = any(),
            locationQueryingService = any(),
            locationRepository = any(),
            externalEquipmentRepository = any(),
            manufacturerAliasResolver = any(),
        )

    private fun company(
        id: Long,
        name: String,
    ) = Company(
        id = id,
        name = name,
        city = "",
        zip = "",
        country = "",
        telephone = "",
        mobile = "",
        vatCode = "",
        email = "",
        web = "",
        bankAccountNumber = "",
    )

    @Test
    fun `a new river operator is created inactive, a new sea company active`() {
        service.updateMmkAgencies(listOf(company(1, "Le Boat"), company(2, "Canal Evasion"), company(3, "Navigare Yachting")))

        created.getValue("Le Boat").active shouldBe false
        created.getValue("Le Boat").syncDeactivatedBy shouldBe null
        created.getValue("Canal Evasion").active shouldBe false
        created.getValue("Navigare Yachting").active shouldBe true
    }
}
