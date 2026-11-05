package hr.workspace.boat4you.domains.pr.dto

import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import java.time.Instant
import java.time.LocalDate

// The admin's PR types, field for field. Markets are ISO-3166 alpha-2 (GB, not UK); emails are stored normalised.

data class PrOutletDto(
    val id: Long,
    val name: String,
    val market: String,
    val tier: Int,
    val sectionUrl: String?,
    val deskContact: String?,
    val notes: String?,
    val createdAt: Instant,
)

/** POST needs name, market and tier; PATCH changes only the fields sent (a blank optional text clears it). */
data class PrOutletRequest(
    val name: String? = null,
    val market: String? = null,
    val tier: Int? = null,
    val sectionUrl: String? = null,
    val deskContact: String? = null,
    val notes: String? = null,
)

data class PrContactDto(
    val id: Long,
    val outletId: Long?,
    val outletName: String?,
    val name: String?,
    val role: String?,
    val email: String,
    val market: String?,
    val emailSourceUrl: String?,
    val emailFoundOn: LocalDate?,
    val status: PrContactStatus,
    val recentArticleUrl: String?,
    val notes: String?,
    val lastContactedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * POST needs email (market defaults to the outlet's); PATCH changes only the fields sent (a blank optional text clears
 * it). status may be ACTIVE, DO_NOT_CONTACT, CONSENT_REQUESTED or CONSENTED; OPTED_OUT comes only from opt-out / bounce.
 */
data class PrContactRequest(
    val outletId: Long? = null,
    val name: String? = null,
    val role: String? = null,
    val email: String? = null,
    val market: String? = null,
    val emailSourceUrl: String? = null,
    val emailFoundOn: LocalDate? = null,
    val status: PrContactStatus? = null,
    val recentArticleUrl: String? = null,
    val notes: String? = null,
)

data class PrContactFilter(
    val market: String? = null,
    val status: PrContactStatus? = null,
    val q: String? = null,
    val page: Int = 0,
    val size: Int = 100,
)

/** created / updated count contacts (an unchanged row counts as neither); skipped = "row N: CODE: detail", one per row. */
data class ImportResult(
    val created: Int,
    val updated: Int,
    val skipped: List<String>,
)
