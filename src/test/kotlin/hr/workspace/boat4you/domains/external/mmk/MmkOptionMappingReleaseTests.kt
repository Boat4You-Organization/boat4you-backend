package hr.workspace.boat4you.domains.external.mmk

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionException
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionMappingException
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.service.MmkReservationIntegrationService
import hr.workspace.boat4you.domains.external.model.ReservationData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.openapitools.client.mmk.model.ReservationResponse
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.LocalDateTime

/**
 * Once MMK has created the option, only code that knows its id can release it. The booking controller releases by
 * wrapper, so a response that cannot be mapped into one used to leave the option dangling at the partner.
 */
class MmkOptionMappingReleaseTests {
    private val data =
        ReservationData(
            startDate = LocalDateTime.of(2027, 10, 16, 17, 0),
            endDate = LocalDateTime.of(2027, 10, 23, 9, 0),
            externalYachtId = 2537536420000103140,
            externalAgencyId = 1,
            name = "Test",
            surname = "Customer",
            selectedServices = null,
            selectedEquipment = null,
        )

    // No Location is mapped and no fallback is passed, so toResponseWrapper throws - the cheapest real mapping failure.
    private val locations = mock(LocationQueryingService::class.java)

    @Test
    fun `an option whose response cannot be mapped is released and reported as our failure`() {
        val response = mock(ReservationResponse::class.java) { inv -> if (inv.method.name == "getId") 777L else null }
        val cancelled = mutableListOf<Long>()
        val client =
            mock(MmkRetryableClient::class.java) { inv ->
                if (inv.method.name == "cancelOption") cancelled += inv.getArgument<Long>(0)
                response
            }

        shouldThrow<ExternalOptionMappingException> {
            MmkReservationIntegrationService(ObjectMapper(), locations, client).createOption(data)
        }
        cancelled shouldBe listOf(777L)
    }

    @Test
    fun `a partner rejection stays an ExternalOptionException and releases nothing`() {
        val cancelled = mutableListOf<Long>()
        val client =
            mock(MmkRetryableClient::class.java) { inv ->
                if (inv.method.name == "cancelOption") cancelled += inv.getArgument<Long>(0)
                throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "Yacht not available in period.")
            }

        shouldThrow<ExternalOptionException> {
            MmkReservationIntegrationService(ObjectMapper(), locations, client).createOption(data)
        }
        cancelled shouldBe emptyList()
    }
}
