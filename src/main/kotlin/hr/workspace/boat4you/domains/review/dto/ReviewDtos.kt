package hr.workspace.boat4you.domains.review.dto

import hr.workspace.boat4you.domains.review.ReviewKind
import hr.workspace.boat4you.domains.review.ReviewStatus
import java.time.Instant
import java.time.LocalDate

/**
 * POST /public/reviews/request/{token}. `rating` is the only required field. `scores` holds the optional 1-5
 * sub-scores of the link's kind (BOOKING: easeOfBooking, communication, valueTransparency; YACHT: boatCondition,
 * cleanliness, checkInOut, charterCompany, value); a key of the other kind is a 400. `publishConsent` = the customer
 * allows publishing with first name + country (absent = false). `locale` defaults to the link's language.
 */
data class ReviewSubmitRequest(
    val rating: Int? = null,
    val scores: Map<String, Int?>? = null,
    val title: String? = null,
    val text: String? = null,
    val publishConsent: Boolean? = null,
    val locale: String? = null,
)

/** What the customer submitted, as stored. */
data class ReviewValuesDto(
    val rating: Int,
    val scores: Map<String, Int>,
    val title: String?,
    val text: String?,
    val publishConsent: Boolean,
    val locale: String,
)

/** GET /public/reviews/request/{token}: everything the form page needs. */
data class ReviewFormDto(
    val kind: ReviewKind,
    val scoreKeys: List<String>,
    val reservationNumber: String?,
    val yachtId: Long?,
    val yachtFullLabel: String,
    val yachtMainImageId: Long?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val baseName: String?,
    val baseCountry: String?,
    val customerFirstName: String?,
    val locale: String,
    val linkExpiresAt: Instant,
    /** A review for this reservation + kind already exists. */
    val submitted: Boolean,
    /** The existing review can still be changed (24 h after the first submit); false when nothing is submitted yet. */
    val editable: Boolean,
    val editableUntil: Instant?,
    /** Present only while the review is editable. */
    val review: ReviewValuesDto?,
)

/** Response of the submit: the stored review. */
data class ReviewSubmitResultDto(
    val id: Long,
    val kind: ReviewKind,
    val status: ReviewStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val editableUntil: Instant,
    val review: ReviewValuesDto,
)

data class AdminReviewDto(
    val id: Long,
    val kind: ReviewKind,
    val status: ReviewStatus,
    val rating: Int,
    val scores: Map<String, Int>,
    val title: String?,
    val text: String?,
    val locale: String,
    val guestCountry: String?,
    /** "yyyy-MM" of the charter start. */
    val charterMonth: String?,
    val publishConsent: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val statusChangedAt: Instant?,
    val reservationId: Long,
    val reservationNumber: String?,
    val yachtId: Long?,
    val yachtFullLabel: String?,
    val customerName: String?,
    val customerEmail: String?,
    val bookingReviewId: Long?,
)

/** PATCH /admin/reviews/{id}. */
data class AdminReviewStatusRequest(
    val status: String? = null,
)
