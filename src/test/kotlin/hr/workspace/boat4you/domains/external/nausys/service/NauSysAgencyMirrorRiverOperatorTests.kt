package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestCharterCompany
import org.openapitools.client.nausys.model.RestCharterCompanyList

/**
 * 25.9.2026 sea charter only: the NauSys agency mirror creates a NEW company whose name reads like a river/canal
 * operator inactive, as a manual OFF (syncDeactivatedBy null, so the mirror never re-activates it); a sea company -
 * "Canal Yachting" of the Corinth Canal included - stays active.
 */
class NauSysAgencyMirrorRiverOperatorTests {
    private val created = mutableMapOf<String, Agency>()

    private val agencies =
        mock(AgencyRepository::class.java) { inv ->
            when (inv.method.name) {
                "saveAndFlush" -> inv.getArgument<Agency>(0).also { it.id = 1000L + created.size }.also { created[it.name!!] = it }
                "findAllByVatCode" -> emptyList<Agency>()
                else -> null
            }
        }
    private val systems =
        mock(ExternalSystemService::class.java) { inv -> if (inv.method.name == "findById") ExternalSystem() else null }

    private inline fun <reified T> any(): T = mock(T::class.java)

    private val service =
        NauSysCatalogueSyncService(
            externalSystemService = systems,
            externalMappingService = any(),
            countryRepository = any(),
            regionRepository = any(),
            locationRepository = any(),
            locationQueryingService = any(),
            manufacturerRepository = any(),
            modelRepository = any(),
            categoryRepository = any(),
            agencyRepository = agencies,
            agencySourceRepository = any(),
            externalEquipmentRepository = any(),
            externalSeasonRepository = any(),
            externalBaseRepository = any(),
            yachtRepository = any(),
            manufacturerAliasResolver = any(),
            modelNameNormaliser = any(),
        )

    @Test
    fun `a new river operator is created inactive, a new sea company active`() {
        service.syncAgenciesByVatCode(
            RestCharterCompanyList(
                companies =
                    listOf(
                        RestCharterCompany(id = 1L, name = "Riverly"),
                        RestCharterCompany(id = 2L, name = "Gota Kanal Charter"),
                        RestCharterCompany(id = 3L, name = "Navigare Yachting"),
                        RestCharterCompany(id = 4L, name = "Canal Yachting"),
                    ),
            ),
        )

        created.getValue("Riverly").active shouldBe false
        created.getValue("Riverly").syncDeactivatedBy shouldBe null
        created.getValue("Gota Kanal Charter").active shouldBe false
        created.getValue("Navigare Yachting").active shouldBe true
        created.getValue("Canal Yachting").active shouldBe true
    }
}
