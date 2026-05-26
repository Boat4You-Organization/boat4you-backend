package hr.workspace.boat4you.domains.users.job

import hr.workspace.boat4you.common.services.toLocale
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.Locale

/**
 * Annual birthday-greeting cron. Fires once a day at 09:00 local time and
 * fans out a personalized "Happy birthday from Boat4You" email to every
 * user whose birthday matches today's month+day.
 *
 * No marketing-consent toggle gating because we have no marketing-vs-
 * transactional split (Mario decision 1.5.2026: "newsletter smo makli").
 * The birthday wish is a transactional courtesy from a sole controller,
 * comparable to a hotel sending a "happy stay anniversary" — falls under
 * legitimate interest, customer can opt out only by clearing the birthday
 * field on /my-profile or deleting their account.
 *
 * Skipped for soft-deleted users (deletedAt IS NOT NULL) — the anonymized
 * tombstone email is undeliverable and we don't want noise in the
 * `gdpr_audit_log` from cron-side deliverability failures.
 */
@Profile("data-sync")
@Component
class BirthdayEmailJob(
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(cron = "0 0 9 * * *")
    @SchedulerLock(name = "birthdayEmail", lockAtMostFor = "PT30M")
    @Transactional(readOnly = true)
    fun sendBirthdayWishes() {
        val today = LocalDate.now()
        val recipients = userRepository.findAllByBirthdayMonthDay(today.monthValue, today.dayOfMonth)
        if (recipients.isEmpty()) {
            log.info("Birthday cron: no birthdays today ({}/{})", today.monthValue, today.dayOfMonth)
            return
        }
        log.info("Birthday cron: sending {} greetings", recipients.size)
        var sent = 0
        var skipped = 0
        recipients.forEach { user ->
            try {
                if (user.email.isBlank() || user.deletedAt != null) {
                    skipped++
                    return@forEach
                }
                // Cron-driven email — no HTTP request scope, so we can't
                // fall back on LocaleContextHolder. Resolve directly off
                // user.language (captured at first contact) → English baseline.
                val locale: Locale = user.language?.toLocale() ?: Locale.ENGLISH
                val fullName = user.getFullName().trim().takeIf { it.isNotBlank() } ?: "there"
                // Inbox-friendly recipient format — see Mario rule
                // (feedback_email_client_name): clients see their name on
                // the To: row, not a bare address.
                val recipientAddress =
                    if (fullName != "there") "$fullName <${user.email}>" else user.email
                val subject = messageSource.getMessage("birthday.subject", null, locale)
                val variables = mapOf(
                    "fullName" to fullName,
                    "userName" to user.name, // legacy var kept for any caller still referencing it
                    "publicUrl" to serverHostPublic,
                    "searchUrl" to "$serverHostPublic/search",
                    "currentYear" to LocalDate.now().year.toString(),
                )
                emailService.sendEmail(
                    recipients = listOf(recipientAddress),
                    subject = subject,
                    templateName = "email/birthdayWish",
                    variables = variables,
                    locale = locale,
                )
                sent++
            } catch (e: Exception) {
                log.error("Birthday email failed for user id={}", user.id, e)
                skipped++
            }
        }
        log.info("Birthday cron complete: sent={}, skipped={}", sent, skipped)
    }
}
