package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.external.exceptions.ExternalOptionException
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.exceptions.BookingCreationException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import java.time.LocalDate
import java.util.Optional

class BookingFailureAlertServiceTests {
    private val stelina = Yacht().apply { id = 9020; name = "Stelina" }
    private val week =
        Offer().apply {
            id = 595995
            yacht = stelina
            dateFrom = LocalDate.of(2027, 10, 16)
            dateTo = LocalDate.of(2027, 10, 23)
        }

    private fun flowOf(customerEmail: String) =
        ReservationFlow().apply {
            name = "Tom"
            surname = "Tester"
            email = customerEmail
            phoneNumber = "+385911234567"
            yacht = stelina
            offer = week
        }

    private var flow = flowOf("tom@example.com")
    private var mailBroken = false
    private val sent = mutableListOf<InvocationOnMock>()
    private val emailService =
        mock(EmailService::class.java) { inv ->
            if (inv.method.name == "sendEmail") {
                if (mailBroken) throw IllegalStateException("bad admin address")
                sent += inv
            }
            null
        }
    private val users = mock(UserRepository::class.java) { inv -> if (inv.method.name == "findAllAdminEmailAddresses") listOf("admin@example.com") else null }
    private val flows = mock(ReservationFlowRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(flow) else null }
    private val offers = mock(OfferRepository::class.java) { inv -> if (inv.method.name == "findById") Optional.of(week) else null }
    private val service = BookingFailureAlertService(emailService, users, flows, offers)

    private val ourFault =
        BookingCreationException(
            "Booking orchestration failed for flow 83",
            IllegalStateException("ERROR: null value in column \"x\"\n  Detail: Failing row contains (41, someone.else@example.com, …)."),
        )

    private fun subject(i: Int = 0) = sent[i].arguments[1] as String

    @Suppress("UNCHECKED_CAST")
    private fun body(i: Int = 0) = (sent[i].arguments[3] as Map<String, Any?>)["reportBody"] as String

    @Test
    fun `a system failure names the customer, the yacht and the root cause`() {
        service.alertFailedFlow(83, ourFault, partnerRejection = false)

        sent shouldHaveSize 1
        sent[0].arguments[0] shouldBe listOf("admin@example.com")
        subject() shouldContain "Rezervacija NIJE prošla"
        subject() shouldContain "Tom Tester"
        subject() shouldContain "Stelina"
        subject() shouldContain "16.10.2027 - 23.10.2027"
        body() shouldContain "tom@example.com"
        body() shouldContain "+385911234567"
        body() shouldContain "IllegalStateException: ERROR: null value in column"
    }

    @Test
    fun `database detail lines never reach the mail and there is no reply-to the customer`() {
        service.alertFailedFlow(83, ourFault, partnerRejection = false)

        body() shouldNotContain "someone.else@example.com"
        sent[0].arguments[5] shouldBe null
    }

    @Test
    fun `a partner rejection is a different, calmer mail`() {
        service.alertFailedFlow(83, ExternalOptionException("MMK: Yacht not available in period."), partnerRejection = true)

        subject() shouldContain "Partner odbio rezervaciju"
        body() shouldContain "PARTNER odbio termin"
    }

    @Test
    fun `retries by the same customer on the same offer do not send a mail each`() {
        repeat(8) { service.alertFailedFlow(83, ourFault, partnerRejection = false) }

        sent shouldHaveSize 1
    }

    @Test
    fun `a mail that could not be sent does not silence the next attempt`() {
        mailBroken = true
        runCatching { service.alertFailedFlow(83, ourFault, partnerRejection = false) }
        mailBroken = false
        service.alertFailedFlow(83, ourFault, partnerRejection = false)

        sent shouldHaveSize 1
    }

    @Test
    fun `a flood of different customers is capped per hour`() {
        repeat(40) { i ->
            flow = flowOf("bot$i@example.com")
            service.alertFailedFlow(83, ourFault, partnerRejection = false)
        }

        sent shouldHaveSize 20
    }

    @Test
    fun `a defect before the flow exists is reported from the request itself`() {
        val dto =
            CreateReservationDto(
                yachtId = 9020,
                offerId = 595995,
                email = "ana@example.com",
                name = "Ana",
                surname = "Anić",
                phoneNumber = "+385981112223",
                specialRequest = null,
                selectedExtras = null,
            )

        service.alertRejectedRequest(dto, IllegalStateException("size must be between 0 and 255"))

        subject() shouldContain "Ana Anić"
        subject() shouldContain "Stelina"
        body() shouldContain "ana@example.com"
        body() shouldContain "no flow was created"
    }
}
