package hr.workspace.boat4you.domains.review

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.review.controllers.AdminReviewController
import hr.workspace.boat4you.domains.review.controllers.PublicReviewController
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitRequest
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitResultDto
import hr.workspace.boat4you.domains.review.dto.ReviewValuesDto
import hr.workspace.boat4you.domains.review.exceptions.ReviewEditWindowClosedException
import hr.workspace.boat4you.domains.review.exceptions.ReviewLinkInvalidException
import hr.workspace.boat4you.domains.review.exceptions.ReviewNotFoundException
import hr.workspace.boat4you.domains.review.service.ReviewInvitationService
import hr.workspace.boat4you.domains.review.service.ReviewResendOutcome
import hr.workspace.boat4you.domains.review.service.ReviewService
import hr.workspace.boat4you.domains.review.service.ReviewSubmitOutcome
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/** HTTP contract of the review endpoints: status codes and error bodies go through ApiErrorHandler. */
class ReviewControllersTests {
    private val service: ReviewService = mock(ReviewService::class.java)
    private val invitations: ReviewInvitationService = mock(ReviewInvitationService::class.java)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(PublicReviewController(service), AdminReviewController(service, invitations))
            .setControllerAdvice(ApiErrorHandler())
            .build()

    private val token = "A".repeat(43)
    private val values = ReviewValuesDto(5, mapOf("easeOfBooking" to 5), "Great", "Smooth booking", true, "en")
    private val result =
        ReviewSubmitResultDto(
            id = 7,
            kind = ReviewKind.BOOKING,
            status = ReviewStatus.NEW,
            createdAt = Instant.parse("2026-09-25T10:00:00Z"),
            updatedAt = Instant.parse("2026-09-25T10:00:00Z"),
            editableUntil = Instant.parse("2026-09-26T10:00:00Z"),
            review = values,
        )
    private val body =
        """{"rating":5,"scores":{"easeOfBooking":5},"title":"Great","text":"Smooth booking","publishConsent":true}"""
    private val request =
        ReviewSubmitRequest(rating = 5, scores = mapOf("easeOfBooking" to 5), title = "Great", text = "Smooth booking", publishConsent = true)

    @Test
    fun `new review - 201, edit - 200, both never cached`() {
        `when`(service.submit(token, request)).thenReturn(ReviewSubmitOutcome(true, result))
        mvc
            .post("/public/reviews/request/$token") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isCreated() }
                header { string("Cache-Control", "no-store") }
                jsonPath("$.id") { value(7) }
                jsonPath("$.status") { value("NEW") }
                jsonPath("$.review.scores.easeOfBooking") { value(5) }
            }

        `when`(service.submit(token, request)).thenReturn(ReviewSubmitOutcome(false, result))
        mvc
            .post("/public/reviews/request/$token") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }
    }

    @Test
    fun `unknown or expired link - 404, edit window over - 409, invalid fields - 400`() {
        `when`(service.getForm(token)).thenThrow(ReviewLinkInvalidException())
        mvc.get("/public/reviews/request/$token").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value(7001) }
        }

        `when`(service.submit(token, request)).thenThrow(ReviewEditWindowClosedException())
        mvc
            .post("/public/reviews/request/$token") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isConflict() }
                jsonPath("$.code") { value(7002) }
            }

        val bad = ReviewSubmitRequest(rating = 9)
        `when`(service.submit(token, bad)).thenThrow(ParameterValidationException(mapOf("rating" to "must be between 1 and 5")))
        mvc
            .post("/public/reviews/request/$token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rating":9}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value(1102) }
            }

        mvc
            .post("/public/reviews/request/$token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"rating":"five"}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `admin list - filters and paging parameters reach the service, unknown kind is a 400`() {
        `when`(service.listForAdmin(ReviewKind.YACHT, ReviewStatus.NEW, 1, 10)).thenReturn(PageImpl(emptyList(), PageRequest.of(1, 10), 12))
        mvc.get("/admin/reviews?kind=YACHT&status=NEW&page=1&size=10").andExpect {
            status { isOk() }
            jsonPath("$.page.totalElements") { value(12) }
            jsonPath("$.page.number") { value(1) }
        }
        mvc.get("/admin/reviews?kind=BOAT").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `admin status change - unknown review 404, bad status 400`() {
        `when`(service.setStatus(99, "PUBLISHED", null)).thenThrow(ReviewNotFoundException())
        mvc
            .patch("/admin/reviews/99") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"PUBLISHED"}"""
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value(7003) }
            }

        `when`(service.setStatus(5, "LIVE", null)).thenThrow(ParameterValidationException(mapOf("status" to "must be one of [NEW, PUBLISHED, HIDDEN]")))
        mvc
            .patch("/admin/reviews/5") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"status":"LIVE"}"""
            }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `admin re-send - 200 SENT, 409 DISABLED or ALREADY_REVIEWED, 404 NOT_ELIGIBLE, kind required`() {
        val cases =
            mapOf(
                ReviewResendOutcome.SENT to 200,
                ReviewResendOutcome.DISABLED to 409,
                ReviewResendOutcome.ALREADY_REVIEWED to 409,
                ReviewResendOutcome.NOT_ELIGIBLE to 404,
            )
        cases.forEach { (outcome, code) ->
            `when`(invitations.resend(42, ReviewKind.YACHT)).thenReturn(outcome)
            mvc.post("/admin/reviews/requests/42/resend?kind=YACHT").andExpect {
                status { isEqualTo(code) }
                jsonPath("$.outcome") { value(outcome.name) }
            }
        }
        mvc.post("/admin/reviews/requests/42/resend").andExpect { status { isBadRequest() } }
        mvc.post("/admin/reviews/requests/42/resend?kind=BOAT").andExpect { status { isBadRequest() } }
    }
}
