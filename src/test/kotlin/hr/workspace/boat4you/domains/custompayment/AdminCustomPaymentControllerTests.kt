package hr.workspace.boat4you.domains.custompayment

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.custompayment.controllers.AdminCustomPaymentController
import hr.workspace.boat4you.domains.custompayment.dto.AdminCustomPaymentDto
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentPageDto
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentNotFoundException
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentProviderException
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.custompayment.service.CustomPaymentAdminService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.web.PagedModel
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/** HTTP contract of `/admin/custom-payments` (role check is the class-level @PreAuthorize, as AdminInquiryController). */
class AdminCustomPaymentControllerTests {
    private val adminService: CustomPaymentAdminService = mock(CustomPaymentAdminService::class.java)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(AdminCustomPaymentController(adminService))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    private val dto =
        AdminCustomPaymentDto(
            id = 5,
            site = "boat4you",
            createdAt = Instant.parse("2026-10-13T10:00:00Z"),
            completedAt = Instant.parse("2026-10-13T10:03:00Z"),
            reference = "1441012/2026",
            amountMinor = 110_900,
            refundedAmountMinor = 0,
            currency = "EUR",
            status = CustomPaymentStatus.COMPLETED,
            payerEmail = "payer@example.com",
            providerOrderId = "ord-1",
            adminNote = "Paid by the charterer",
            reservationId = 1441,
            reservationNumber = "1441012/2026",
        )

    @Test
    fun `list - filters and paging reach the service, page shape + total received`() {
        `when`(adminService.list(CustomPaymentStatus.COMPLETED, "boat4you", "1441012", 1, 10)).thenReturn(
            CustomPaymentPageDto(listOf(dto), PagedModel.PageMetadata(10, 1, 11, 2), 1_220_000),
        )
        mvc.get("/admin/custom-payments?status=COMPLETED&site=boat4you&q=1441012&page=1&size=10").andExpect {
            status { isOk() }
            jsonPath("$.content[0].id") { value(5) }
            jsonPath("$.content[0].reference") { value("1441012/2026") }
            jsonPath("$.content[0].amountMinor") { value(110_900) }
            jsonPath("$.content[0].status") { value("COMPLETED") }
            jsonPath("$.content[0].reservationNumber") { value("1441012/2026") }
            jsonPath("$.page.totalElements") { value(11) }
            jsonPath("$.page.number") { value(1) }
            jsonPath("$.page.size") { value(10) }
            jsonPath("$.totalReceivedMinor") { value(1_220_000) }
        }

        `when`(adminService.list(null, null, null, 0, 20)).thenReturn(CustomPaymentPageDto(emptyList(), PagedModel.PageMetadata(20, 0, 0, 0), 0))
        mvc.get("/admin/custom-payments").andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(0) }
            jsonPath("$.totalReceivedMinor") { value(0) }
        }

        mvc.get("/admin/custom-payments?status=PAID").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `note - 200 with the row, unknown id 404, too long 400`() {
        `when`(adminService.updateNote(5, "Paid by the charterer")).thenReturn(dto)
        mvc
            .patch("/admin/custom-payments/5/note") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"note":"Paid by the charterer"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.adminNote") { value("Paid by the charterer") }
            }

        `when`(adminService.updateNote(99, "x")).thenThrow(CustomPaymentNotFoundException())
        mvc
            .patch("/admin/custom-payments/99/note") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"note":"x"}"""
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(8002) }
            }

        val long = "n".repeat(2001)
        `when`(adminService.updateNote(5, long)).thenThrow(ParameterValidationException(mapOf("note" to "at most 2000 characters")))
        mvc
            .patch("/admin/custom-payments/5/note") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"note":"$long"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
            }
    }

    @Test
    fun `refresh - 200 with the reconciled row, Revolut error 502`() {
        `when`(adminService.refresh(5)).thenReturn(dto)
        mvc.post("/admin/custom-payments/5/refresh").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
        }
        `when`(adminService.refresh(6)).thenThrow(CustomPaymentProviderException())
        mvc.post("/admin/custom-payments/6/refresh").andExpect {
            status { isBadGateway() }
            jsonPath("$.code") { value(8004) }
        }
    }
}
