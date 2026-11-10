package hr.workspace.boat4you.domains.payment.mollie.admin

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderStatus
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentNotFoundException
import hr.workspace.boat4you.domains.reservation.service.payment.CardPaymentProviderException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.web.PagedModel
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/** HTTP contract of `/admin/card-payments` and `/admin/mollie-payments` (role check = class-level @PreAuthorize). */
class AdminCardPaymentControllerTests {
    private val adminService: AdminCardPaymentService = mock(AdminCardPaymentService::class.java)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(AdminCardPaymentController(adminService))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    private val dto =
        AdminCardPaymentDto(
            id = 9,
            createdAt = Instant.parse("2026-11-10T10:00:00Z"),
            completedAt = null,
            expiresAt = Instant.parse("2026-11-10T10:15:00Z"),
            status = ReservationCardOrderStatus.FAILED,
            reservationId = 1441,
            reservationNumber = "1441012/2026",
            reference = "1441012/2026",
            clientName = "Ana Kovac",
            clientEmail = "ana@example.com",
            clientPhone = "+385911234567",
            baseAmountMinor = 267_760,
            surchargeAmountMinor = 13_400,
            amountMinor = 281_160,
            refundedAmountMinor = 0,
            chargedBackAmountMinor = 0,
            currency = "EUR",
            providerOrderId = "tr_7UhSN1zuXS",
            payFullAmount = false,
            phaseIds = listOf(7),
            reviewReason = null,
            promoteState = null,
            lastCheckedAt = null,
        )

    @Test
    fun `list - a comma-separated status, q, reservation and paging reach the service`() {
        val statuses = listOf(ReservationCardOrderStatus.FAILED, ReservationCardOrderStatus.CANCELLED)
        `when`(adminService.list(statuses, "ana", 1441, 1, 10)).thenReturn(
            AdminCardPaymentPageDto(listOf(dto), PagedModel.PageMetadata(10, 1, 11, 2), 0),
        )
        mvc.get("/admin/card-payments?status=FAILED,CANCELLED&q=ana&reservationId=1441&page=1&size=10").andExpect {
            status { isOk() }
            jsonPath("$.content[0].id") { value(9) }
            jsonPath("$.content[0].status") { value("FAILED") }
            jsonPath("$.content[0].clientPhone") { value("+385911234567") }
            jsonPath("$.content[0].amountMinor") { value(281_160) }
            jsonPath("$.content[0].surchargeAmountMinor") { value(13_400) }
            jsonPath("$.content[0].providerOrderId") { value("tr_7UhSN1zuXS") }
            jsonPath("$.content[0].phaseIds[0]") { value(7) }
            jsonPath("$.page.totalElements") { value(11) }
            jsonPath("$.totalReceivedMinor") { value(0) }
        }

        `when`(adminService.list(null, null, null, 0, 20)).thenReturn(AdminCardPaymentPageDto(emptyList(), PagedModel.PageMetadata(20, 0, 0, 0), 0))
        mvc.get("/admin/card-payments").andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(0) }
        }
    }

    @Test
    fun `list - an unknown status is a 400, never a 500`() {
        mvc.get("/admin/card-payments?status=PAID").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `refresh - the row, 404 for an unknown id, 502 when Mollie cannot be read`() {
        `when`(adminService.refresh(9)).thenReturn(dto.copy(status = ReservationCardOrderStatus.COMPLETED))
        mvc.post("/admin/card-payments/9/refresh").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
        }
        `when`(adminService.refresh(404)).thenThrow(CardPaymentNotFoundException())
        mvc.post("/admin/card-payments/404/refresh").andExpect { status { isNotFound() } }
        `when`(adminService.refresh(502)).thenThrow(CardPaymentProviderException())
        mvc.post("/admin/card-payments/502/refresh").andExpect { status { isBadGateway() } }
    }

    @Test
    fun `mollie details - the live payment with refunds, 404 for a payment that is not ours`() {
        `when`(adminService.mollieDetails("tr_7UhSN1zuXS")).thenReturn(
            AdminMolliePaymentDto(
                id = "tr_7UhSN1zuXS",
                mode = "live",
                status = "paid",
                method = "creditcard",
                description = "Boat4You booking 1441012/2026",
                amountMinor = 281_160,
                refundedAmountMinor = 5_000,
                chargedBackAmountMinor = 0,
                currency = "EUR",
                createdAt = Instant.parse("2026-11-10T10:00:00Z"),
                paidAt = Instant.parse("2026-11-10T10:02:00Z"),
                expiresAt = null,
                expiredAt = null,
                failedAt = null,
                canceledAt = null,
                cardLabel = "Visa",
                cardLast4 = "9996",
                cardCountryCode = "HR",
                cardFunding = "credit",
                cardAudience = "consumer",
                feeRegion = "intra-eu",
                failureReason = null,
                failureMessage = null,
                refunds = listOf(AdminMollieRefundDto("re_4qqhO89gsT", 5_000, "refunded", Instant.parse("2026-11-11T08:00:00Z"), "Goodwill")),
                chargebacks = emptyList(),
            ),
        )
        mvc.get("/admin/mollie-payments/tr_7UhSN1zuXS").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("paid") }
            jsonPath("$.cardLast4") { value("9996") }
            jsonPath("$.refunds[0].amountMinor") { value(5_000) }
            jsonPath("$.refunds[0].status") { value("refunded") }
            jsonPath("$.chargebacks.length()") { value(0) }
        }
        `when`(adminService.mollieDetails("tr_notours")).thenThrow(CardPaymentNotFoundException())
        mvc.get("/admin/mollie-payments/tr_notours").andExpect { status { isNotFound() } }
    }
}
