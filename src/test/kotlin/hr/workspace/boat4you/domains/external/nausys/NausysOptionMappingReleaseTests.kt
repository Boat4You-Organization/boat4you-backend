package hr.workspace.boat4you.domains.external.nausys

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionMappingException
import hr.workspace.boat4you.domains.external.model.ReservationData
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.nausys.service.NausysReservationIntegrationService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.nausys.model.RestYachtReservation
import org.openapitools.client.nausys.model.RestYachtReservationRequest
import java.time.LocalDateTime

/**
 * NauSys twin of MmkOptionMappingReleaseTests. Worth its own test because the release hand-builds the storno request
 * (id + uuid): a wrong field there shows up only as yachts blocked at NauSys with no booking on our side.
 */
class NausysOptionMappingReleaseTests {
    private val data =
        ReservationData(
            startDate = LocalDateTime.of(2027, 10, 16, 17, 0),
            endDate = LocalDateTime.of(2027, 10, 23, 9, 0),
            externalYachtId = 101,
            externalAgencyId = 202,
            name = "Test",
            surname = "Customer",
            selectedServices = null,
            selectedEquipment = null,
        )

    @Test
    fun `an option whose response cannot be mapped is released with its own id and uuid`() {
        // A bare response: every field the mapper needs is null, so mapping throws right after the option "exists".
        val created = RestYachtReservation(id = 555L, uuid = "0b7c1f5e-aaaa-bbbb-cccc-1234567890ab", status = "OK")
        val stornos = mutableListOf<RestYachtReservationRequest>()
        val client =
            mock(NauSysRetryableClient::class.java) { inv ->
                if (inv.method.name == "stornoOption") stornos += inv.getArgument<RestYachtReservationRequest>(0)
                created
            }

        shouldThrow<ExternalOptionMappingException> {
            NausysReservationIntegrationService(
                client,
                mock(NauSysAuthProvider::class.java),
                ObjectMapper(),
                mock(LocationQueryingService::class.java),
            ).createOption(data)
        }
        stornos shouldHaveSize 1
        stornos.single().id shouldBe 555L
        stornos.single().uuid shouldBe "0b7c1f5e-aaaa-bbbb-cccc-1234567890ab"
    }
}
