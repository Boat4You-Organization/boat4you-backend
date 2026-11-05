package hr.workspace.boat4you.domains.pr.rules

import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Everything check() needs about one pitch and its contact; the caller loads it, the rules stay pure. */
data class PitchCheckInput(
    val market: String?,
    val contactStatus: PrContactStatus,
    val emailSuppressed: Boolean,
    val template: PrPitchTemplate,
    val emailSourceUrl: String?,
    val emailFoundOn: LocalDate?,
    val subject: String,
    val body: String,
    val clearedMarkets: Set<String>,
    val parentSentAt: Instant?,
    val now: Instant,
)

sealed interface PitchDecision {
    data object Allowed : PitchDecision

    data class Blocked(
        val code: String,
    ) : PitchDecision
}

/**
 * The single source of truth for whether a pitch may be sent. Rules run in a fixed order and the first failing one wins:
 * TRIPADVISOR_FORBIDDEN, SUPPRESSED, OPTED_OUT, DO_NOT_CONTACT, NO_EMAIL_SOURCE, MARKET_UNKNOWN, MARKET_DE_NO_COLD_EMAIL,
 * ZA_CONSENT_REQUIRED, MARKET_NOT_CLEARED, FOLLOWUP_TOO_EARLY.
 */
object PrComplianceRules {
    private val FOLLOW_UP_DELAY: Duration = Duration.ofDays(5)
    private val ISO_ALPHA2 = Regex("[A-Z]{2}")
    private val TRIPADVISOR = Regex("trip[\\s\\-_.]*advisor", RegexOption.IGNORE_CASE)
    private val MARKET_ALIASES =
        mapOf(
            "UK" to "GB",
            "UNITED KINGDOM" to "GB",
            "GREAT BRITAIN" to "GB",
            "USA" to "US",
            "UNITED STATES" to "US",
            "UNITED STATES OF AMERICA" to "US",
            "AUSTRALIA" to "AU",
            "NEW ZEALAND" to "NZ",
            "FRANCE" to "FR",
            "CROATIA" to "HR",
            "GREECE" to "GR",
            "GERMANY" to "DE",
            "SOUTH AFRICA" to "ZA",
            "IRELAND" to "IE",
            "ITALY" to "IT",
            "SPAIN" to "ES",
            "NETHERLANDS" to "NL",
            "AUSTRIA" to "AT",
            "SWITZERLAND" to "CH",
        )

    fun check(input: PitchCheckInput): PitchDecision {
        val blocked = firstViolation(input)
        return if (blocked == null) PitchDecision.Allowed else PitchDecision.Blocked(blocked)
    }

    /**
     * TripAdvisor is never named in anything we send or publish (pitch subject and body, study title): any case, with or
     * without a space, hyphen, underscore or dot between the words ("Trip Advisor", "trip-advisor", "TRIP.ADVISOR").
     */
    fun mentionsTripAdvisor(text: String): Boolean = TRIPADVISOR.containsMatchIn(text)

    /** Suppression and contact lookups compare addresses in this form, so one opt-out covers every contact with that address. */
    fun normaliseEmail(raw: String): String = raw.trim().lowercase()

    /** ISO-3166 alpha-2 (GB, not UK); common English names map to their code; anything else is null (= MARKET_UNKNOWN). */
    fun normaliseMarket(raw: String?): String? {
        val upper = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        val code = MARKET_ALIASES[upper] ?: upper
        return code.takeIf { ISO_ALPHA2.matches(it) }
    }

    private fun firstViolation(input: PitchCheckInput): String? {
        if (mentionsTripAdvisor(input.subject) || mentionsTripAdvisor(input.body)) return "TRIPADVISOR_FORBIDDEN"
        if (input.emailSuppressed) return "SUPPRESSED"
        if (input.contactStatus == PrContactStatus.OPTED_OUT) return "OPTED_OUT"
        if (input.contactStatus == PrContactStatus.DO_NOT_CONTACT) return "DO_NOT_CONTACT"
        if (input.emailSourceUrl.isNullOrBlank() || input.emailFoundOn == null) return "NO_EMAIL_SOURCE"
        val market = normaliseMarket(input.market) ?: return "MARKET_UNKNOWN"
        val consented = input.contactStatus == PrContactStatus.CONSENTED
        when (market) {
            // Germany: never a cold email; only a contact who has consented.
            "DE" -> if (!consented) return "MARKET_DE_NO_COLD_EMAIL"
            // South Africa (POPIA): one consent request to a fresh contact, everything else only after consent.
            "ZA" -> {
                val firstConsentRequest = input.template == PrPitchTemplate.CONSENT_REQUEST && input.contactStatus == PrContactStatus.ACTIVE
                if (!consented && !firstConsentRequest) return "ZA_CONSENT_REQUIRED"
            }
            else -> if (input.clearedMarkets.none { normaliseMarket(it) == market }) return "MARKET_NOT_CLEARED"
        }
        if (input.template == PrPitchTemplate.FOLLOW_UP) {
            val parentSentAt = input.parentSentAt ?: return "FOLLOWUP_TOO_EARLY"
            if (Duration.between(parentSentAt, input.now) < FOLLOW_UP_DELAY) return "FOLLOWUP_TOO_EARLY"
        }
        return null
    }
}
