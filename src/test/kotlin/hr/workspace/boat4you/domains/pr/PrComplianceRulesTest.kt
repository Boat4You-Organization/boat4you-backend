package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.jpa.PrContactStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.rules.PitchCheckInput
import hr.workspace.boat4you.domains.pr.rules.PitchDecision
import hr.workspace.boat4you.domains.pr.rules.PrComplianceRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The per-market send rules as a pure function: every pitch goes through check() before it is sent. */
class PrComplianceRulesTest {
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val defaultCleared = setOf("US", "GB", "AU", "NZ", "FR", "HR", "GR")

    private fun input(
        market: String? = "US",
        status: PrContactStatus = PrContactStatus.ACTIVE,
        suppressed: Boolean = false,
        template: PrPitchTemplate = PrPitchTemplate.DATA_PITCH,
        sourceUrl: String? = "https://paper.example/staff",
        foundOn: LocalDate? = LocalDate.of(2026, 11, 1),
        subject: String = "Charter prices in Croatia rose 12% this summer",
        body: String = "Hi Ed, our booking data shows...",
        cleared: Set<String> = defaultCleared,
        parentSentAt: Instant? = null,
    ) = PitchCheckInput(
        market = market,
        contactStatus = status,
        emailSuppressed = suppressed,
        template = template,
        emailSourceUrl = sourceUrl,
        emailFoundOn = foundOn,
        subject = subject,
        body = body,
        clearedMarkets = cleared,
        parentSentAt = parentSentAt,
        now = now,
    )

    private fun blocked(code: String) = PitchDecision.Blocked(code)

    @Test
    fun cleanUsPitchIsAllowed() {
        PrComplianceRules.check(input()) shouldBe PitchDecision.Allowed
    }

    @Test
    fun tripadvisorAnywhereIsForbidden() {
        PrComplianceRules.check(input(body = "Unlike TripAdvisor reviews, our data...")) shouldBe blocked("TRIPADVISOR_FORBIDDEN")
        PrComplianceRules.check(input(subject = "Beyond TRIPADVISOR")) shouldBe blocked("TRIPADVISOR_FORBIDDEN")
        // Checked first: even a suppressed, opted-out contact reports the TripAdvisor problem.
        PrComplianceRules.check(input(body = "tripadvisor", suppressed = true, status = PrContactStatus.OPTED_OUT)) shouldBe
            blocked("TRIPADVISOR_FORBIDDEN")
    }

    @Test
    fun tripAdvisorSpellingVariantsMatch() {
        listOf("TripAdvisor", "Trip Advisor", "trip-advisor", "TRIP.ADVISOR", "trip_advisor", "Trip -  Advisor").forEach {
            PrComplianceRules.mentionsTripAdvisor("Unlike $it reviews") shouldBe true
            PrComplianceRules.check(input(subject = "Beyond $it")) shouldBe blocked("TRIPADVISOR_FORBIDDEN")
        }
        PrComplianceRules.mentionsTripAdvisor("A trip with an advisor") shouldBe false
        PrComplianceRules.mentionsTripAdvisor("Charter prices in Croatia rose 12%") shouldBe false
    }

    @Test
    fun tripAdvisorWithUnicodeSeparatorsMatches() {
        // NBSP, zero-width space, en dash, em dash, narrow NBSP, soft hyphen, and a line break (pasted from Word or a web page).
        listOf(" ", "​", "–", "—", " – ", " ", "­", "\n").forEach {
            PrComplianceRules.mentionsTripAdvisor("Unlike Trip${it}Advisor reviews") shouldBe true
        }
        PrComplianceRules.mentionsTripAdvisor("A trip with an advisor") shouldBe false
    }

    @Test
    fun suppressionMatchesCaseAndWhitespace() {
        PrComplianceRules.normaliseEmail(" Ed@Paper.COM ") shouldBe "ed@paper.com"
    }

    @Test
    fun suppressedIsBlocked() {
        PrComplianceRules.check(input(suppressed = true)) shouldBe blocked("SUPPRESSED")
    }

    @Test
    fun optedOutIsBlocked() {
        PrComplianceRules.check(input(status = PrContactStatus.OPTED_OUT)) shouldBe blocked("OPTED_OUT")
    }

    @Test
    fun doNotContactIsBlocked() {
        PrComplianceRules.check(input(status = PrContactStatus.DO_NOT_CONTACT)) shouldBe blocked("DO_NOT_CONTACT")
    }

    @Test
    fun missingSourceIsBlocked() {
        PrComplianceRules.check(input(sourceUrl = null)) shouldBe blocked("NO_EMAIL_SOURCE")
        PrComplianceRules.check(input(sourceUrl = "  ")) shouldBe blocked("NO_EMAIL_SOURCE")
        PrComplianceRules.check(input(foundOn = null)) shouldBe blocked("NO_EMAIL_SOURCE")
    }

    @Test
    fun missingMarketIsBlocked() {
        PrComplianceRules.check(input(market = null)) shouldBe blocked("MARKET_UNKNOWN")
        PrComplianceRules.check(input(market = "XX1")) shouldBe blocked("MARKET_UNKNOWN")
    }

    @Test
    fun marketInInputIsNormalised() {
        PrComplianceRules.check(input(market = "uk")) shouldBe PitchDecision.Allowed
        PrComplianceRules.check(input(market = "USA")) shouldBe PitchDecision.Allowed
        PrComplianceRules.check(input(market = "US", cleared = setOf(" usa ", "uk"))) shouldBe PitchDecision.Allowed
    }

    @Test
    fun germanyColdEmailBlockedButConsentedAllowed() {
        PrComplianceRules.check(input(market = "DE")) shouldBe blocked("MARKET_DE_NO_COLD_EMAIL")
        PrComplianceRules.check(input(market = "DE", status = PrContactStatus.CONSENTED)) shouldBe PitchDecision.Allowed
    }

    @Test
    fun southAfricaOnlyConsentRequestUntilConsented() {
        PrComplianceRules.check(input(market = "ZA")) shouldBe blocked("ZA_CONSENT_REQUIRED")
        PrComplianceRules.check(input(market = "ZA", template = PrPitchTemplate.CONSENT_REQUEST)) shouldBe PitchDecision.Allowed
        PrComplianceRules.check(input(market = "ZA", status = PrContactStatus.CONSENTED)) shouldBe PitchDecision.Allowed
    }

    @Test
    fun southAfricaConsentRequestOnlyOnce() {
        PrComplianceRules.check(input(market = "ZA", status = PrContactStatus.CONSENT_REQUESTED, template = PrPitchTemplate.CONSENT_REQUEST)) shouldBe
            blocked("ZA_CONSENT_REQUIRED")
    }

    @Test
    fun unclearedMarketBlocked() {
        PrComplianceRules.check(input(market = "IE")) shouldBe blocked("MARKET_NOT_CLEARED")
    }

    @Test
    fun followUpBefore5DaysBlocked() {
        val followUp = PrPitchTemplate.FOLLOW_UP
        PrComplianceRules.check(input(template = followUp, parentSentAt = now.minus(Duration.ofDays(4)))) shouldBe blocked("FOLLOWUP_TOO_EARLY")
        PrComplianceRules.check(input(template = followUp, parentSentAt = now.minus(Duration.ofDays(5)))) shouldBe PitchDecision.Allowed
    }

    @Test
    fun followUpWithoutSentParentBlocked() {
        PrComplianceRules.check(input(template = PrPitchTemplate.FOLLOW_UP, parentSentAt = null)) shouldBe blocked("FOLLOWUP_TOO_EARLY")
    }

    @Test
    fun ukIsNormalisedToGb() {
        PrComplianceRules.normaliseMarket("uk") shouldBe "GB"
    }

    @Test
    fun countryNamesAreNormalised() {
        PrComplianceRules.normaliseMarket("USA") shouldBe "US"
        PrComplianceRules.normaliseMarket(" New Zealand ") shouldBe "NZ"
        PrComplianceRules.normaliseMarket("United Kingdom") shouldBe "GB"
        PrComplianceRules.normaliseMarket("fr") shouldBe "FR"
        PrComplianceRules.normaliseMarket("Croatia/Greece (EN)") shouldBe null
        PrComplianceRules.normaliseMarket("  ") shouldBe null
        PrComplianceRules.normaliseMarket(null) shouldBe null
    }
}
