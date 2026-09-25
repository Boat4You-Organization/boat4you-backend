package hr.workspace.boat4you.domains.review.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.review.ReviewKind
import hr.workspace.boat4you.domains.review.ReviewStatus
import hr.workspace.boat4you.domains.review.dto.AdminReviewDto
import hr.workspace.boat4you.domains.review.dto.ReviewFormDto
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitRequest
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitResultDto
import hr.workspace.boat4you.domains.review.exceptions.ReviewEditWindowClosedException
import hr.workspace.boat4you.domains.review.exceptions.ReviewLinkInvalidException
import hr.workspace.boat4you.domains.review.exceptions.ReviewNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

data class ReviewSubmitOutcome(
    val created: Boolean,
    val result: ReviewSubmitResultDto,
)

/**
 * Guest review form behind the magic link + admin moderation. Everything is keyed by the request row the token
 * resolves to; an unknown, malformed or expired token is one and the same 404.
 */
@Service
class ReviewService(
    private val dao: ReviewDao,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val MAX_ADMIN_PAGE_SIZE = 100
    }

    fun getForm(token: String): ReviewFormDto {
        val request = resolve(token)
        val context = dao.loadReservationContext(request.reservationId) ?: throw ReviewLinkInvalidException()
        val existing = dao.findReview(request.reservationId, request.kind)
        val editable = existing != null && ReviewTokens.isEditable(existing.createdAt, existing.dbNow)
        return ReviewFormDto(
            kind = request.kind,
            scoreKeys = request.kind.scoreKeys,
            reservationNumber = context.reservationNumber,
            yachtId = context.yachtId,
            yachtFullLabel = context.yachtFullLabel,
            yachtMainImageId = context.yachtMainImageId,
            dateFrom = context.dateFrom?.toLocalDate(),
            dateTo = context.dateTo?.toLocalDate(),
            baseName = context.baseName,
            baseCountry = context.baseCountry,
            customerFirstName = context.customerFirstName,
            locale = request.locale,
            linkExpiresAt = request.expiresAt,
            submitted = existing != null,
            editable = editable,
            editableUntil = existing?.let { ReviewTokens.editableUntil(it.createdAt) },
            review = existing?.values?.takeIf { editable },
        )
    }

    /** New review -> created = true (201); edit within 24 h -> created = false (200); later -> 409. */
    fun submit(
        token: String,
        body: ReviewSubmitRequest,
    ): ReviewSubmitOutcome {
        val request = resolve(token)
        val values = ReviewValidation.validate(request.kind, body, request.locale)
        val existing = dao.findReview(request.reservationId, request.kind)
        if (existing == null) {
            val context = dao.loadReservationContext(request.reservationId) ?: throw ReviewLinkInvalidException()
            val created =
                try {
                    dao.insertReview(request, context, values)
                } catch (e: DuplicateKeyException) {
                    // A parallel submit (double click) inserted first: this one becomes an edit of it.
                    null
                }
            if (created != null) {
                log.info("{} review {} received for reservation {} (rating {})", request.kind, created, request.reservationId, values.rating)
                return ReviewSubmitOutcome(true, load(created))
            }
        }
        val current = existing ?: dao.findReview(request.reservationId, request.kind) ?: throw ReviewLinkInvalidException()
        if (!dao.updateReviewWithinEditWindow(current.id, values, ReviewTokens.EDIT_WINDOW.toHours())) {
            throw ReviewEditWindowClosedException()
        }
        return ReviewSubmitOutcome(false, load(current.id))
    }

    fun listForAdmin(
        kind: ReviewKind?,
        status: ReviewStatus?,
        page: Int,
        size: Int,
    ): Page<AdminReviewDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_ADMIN_PAGE_SIZE))
        val total = dao.countForAdmin(kind, status)
        val rows = if (total == 0L) emptyList() else dao.listForAdmin(kind, status, pageable.pageSize, pageable.offset)
        return PageImpl(rows.map { it.toDto() }, pageable, total)
    }

    fun setStatus(
        id: Long,
        rawStatus: String?,
        adminUserId: Long?,
    ): AdminReviewDto {
        val status =
            ReviewStatus.entries.firstOrNull { it.name == rawStatus?.trim()?.uppercase() }
                ?: throw ParameterValidationException(mapOf("status" to "must be one of ${ReviewStatus.entries.map { it.name }}"))
        if (!dao.updateStatus(id, status, adminUserId)) throw ReviewNotFoundException()
        log.info("Review {} set to {} by admin user {}", id, status, adminUserId)
        return findAdminRow(id)
    }

    private fun findAdminRow(id: Long): AdminReviewDto = dao.findAdminRowById(id)?.toDto() ?: throw ReviewNotFoundException()

    private fun resolve(token: String): ReviewRequestRow {
        if (!ReviewTokens.isWellFormed(token)) throw ReviewLinkInvalidException()
        val request = dao.findRequestByTokenHash(ReviewTokens.hash(token)) ?: throw ReviewLinkInvalidException()
        if (ReviewTokens.isExpired(request.expiresAt, request.dbNow)) throw ReviewLinkInvalidException()
        return request
    }

    private fun load(id: Long): ReviewSubmitResultDto {
        val row = dao.findReviewById(id) ?: throw ReviewNotFoundException()
        return ReviewSubmitResultDto(
            id = row.id,
            kind = row.kind,
            status = row.status,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            editableUntil = ReviewTokens.editableUntil(row.createdAt),
            review = row.values,
        )
    }

    private fun AdminReviewRow.toDto() =
        AdminReviewDto(
            id = review.id,
            kind = review.kind,
            status = review.status,
            rating = review.values.rating,
            scores = review.values.scores,
            title = review.values.title,
            text = review.values.text,
            locale = review.values.locale,
            guestCountry = guestCountry,
            charterMonth = charterMonth?.toString()?.substring(0, 7),
            publishConsent = review.values.publishConsent,
            createdAt = review.createdAt,
            updatedAt = review.updatedAt,
            statusChangedAt = statusChangedAt,
            reservationId = review.reservationId,
            reservationNumber = reservationNumber,
            yachtId = yachtId,
            yachtFullLabel = yachtFullLabel,
            customerName = customerName,
            customerEmail = customerEmail,
            bookingReviewId = bookingReviewId,
        )
}
