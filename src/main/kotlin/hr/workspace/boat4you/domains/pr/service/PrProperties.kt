package hr.workspace.boat4you.domains.pr.service

import hr.workspace.boat4you.domains.pr.rules.PrComplianceRules
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * PR module configuration (`pr.*` in application.yml, every setting env-overridable; secrets only from the env). The
 * kill switch `mail.enabled` is off by default: nothing is sent until PR_MAIL_ENABLED=true. The retention cron
 * (`pr.retention.cron`) is read by PrContactService's @Scheduled directly. Every field has a default so tests can build
 * `PrProperties()`; Spring binds through the Kotlin primary constructor (no @ConstructorBinding: with all defaults the
 * JVM class also has a no-args constructor, which that annotation would pick and fail on).
 */
@ConfigurationProperties(prefix = "pr")
data class PrProperties(
    val markets: Markets = Markets(),
    val mail: Mail = Mail(),
    /** Linked from the footer of every pitch. */
    val privacyUrl: String = "https://www.boat4you.com/press/privacy",
    /** Key for the PR public API; blank = that API answers 401 to everyone. */
    val apiKey: String = "",
    /** Open PageRank key (free); blank = domain rank is skipped. */
    val oprApiKey: String = "",
    /** Our sites, for the link checks (www. and subdomains included). */
    val ourDomains: List<String> = OUR_DOMAINS,
) {
    /** ISO-3166 alpha-2 codes; `GB` not `UK` (a stray alias is normalised, anything unknown dropped). */
    data class Markets(
        val cleared: String = "US,GB,AU,NZ,FR,HR,GR",
    )

    data class Mail(
        val enabled: Boolean = false,
        val host: String = "",
        /** 465 = SMTPS, like the app mail on mail.boat4you.com; with `ssl = false` (e.g. 587) STARTTLS is required. */
        val port: Int = 465,
        val ssl: Boolean = true,
        val username: String = "",
        val password: String = "",
        val from: String = "Mario Kuzmanić | Boat4you Group <press@boat4you.com>",
        /** A copy of every pitch lands in the press mailbox. */
        val bcc: String = "press@boat4you.com",
    )

    fun clearedMarkets(): Set<String> = markets.cleared.split(",").mapNotNull { PrComplianceRules.normaliseMarket(it) }.toSet()

    companion object {
        val OUR_DOMAINS =
            listOf(
                "boat4you.com",
                "catamaran-croatia-charter.com",
                "croatia-yachting.com",
                "europe-yachts.com",
                "catamaran-charter-greece.com",
                "catamarancharteritaly.com",
                "catamaran-charter-caribbean.com",
            )
    }
}
