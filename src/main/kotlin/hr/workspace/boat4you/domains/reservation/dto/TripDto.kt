package hr.workspace.boat4you.domains.reservation.dto

import java.time.LocalDateTime

/**
 * Everything the /trip/{token} PWA hub (Boat4You Trip) needs in one call.
 * Served UNAUTHENTICATED — the unguessable token is the key, and the crew the
 * leader invites opens the same URL. Therefore this DTO carries NO prices,
 * NO payment data and NO owner PII beyond the first name used in the greeting;
 * the owner sees payments through the authenticated my-bookings API instead.
 */
data class TripDto(
    val reservationNumber: String,
    /** OPTION / OPTION_WAITING / RESERVATION / CANCELLED — drives hub state
     *  (pre-charter, locked, memory mode is derived from dateTo on the FE). */
    val status: String,
    val dateFrom: LocalDateTime,
    val dateTo: LocalDateTime,
    val ownerFirstName: String?,
    val yacht: TripYachtDto,
    val marina: TripMarinaDto?,
    val crewListUrl: String?,
    val agencyPhone: String?,
    /** Travel documents only (crew list / boarding pass / preference list) —
     *  contracts and untyped uploads stay owner-only in my-bookings. */
    val documents: List<ReservationDocumentDto>,
    /** VAPID public key for web-push subscribe; null = push not configured,
     *  the hub hides its trip-reminders card. */
    val vapidPublicKey: String?,
)

data class TripYachtDto(
    val name: String,
    /** Manufacturer + model + name — the Mario email/display formula. */
    val fullLabel: String,
    /** boat4you.com/boat/{slug} — boat page link for the whole crew. */
    val slug: String,
    val buildYear: Int?,
    val cabins: Int?,
    val berths: Int?,
    val wc: Int?,
    val lengthMeters: Double?,
    val mainImageId: Long?,
    /** Up to 8 gallery image ids, main image first (served via /public/image/{id}). */
    val imageIds: List<Long>,
)

data class TripMarinaDto(
    val name: String,
    val countryCode: String?,
    val lat: Double?,
    val lon: Double?,
)
