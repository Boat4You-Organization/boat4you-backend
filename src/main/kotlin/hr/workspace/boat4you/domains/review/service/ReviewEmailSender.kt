package hr.workspace.boat4you.domains.review.service

import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.review.ReviewKind
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Sends one review request e-mail. An interface so the invitation flow is testable without SMTP / Thymeleaf. */
fun interface ReviewInvitationMailer {
    fun send(
        kind: ReviewKind,
        context: ReviewReservationContext,
        locale: String,
        reviewUrl: String,
    )
}

/**
 * Review request e-mails (templates email/reviewRequestBooking + email/reviewRequestYacht), in the customer's
 * language (users.language; the 9 bundles in messages/email_<lc>.properties). Recipient = "Full Name <email>"
 * (RFC 2822) and the boat as Manufacturer + Model + Name, per the e-mail rules.
 *
 * Rendering happens synchronously inside EmailService.sendEmail, so a template error throws here and rolls back
 * the caller's claim (the request is retried on the next run); the SMTP submit itself runs after the commit.
 */
@Component
class ReviewEmailSender(
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    @Value("\${server.host}") private val serverHost: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) : ReviewInvitationMailer {
    companion object {
        const val BOOKING_TEMPLATE = "email/reviewRequestBooking"
        const val YACHT_TEMPLATE = "email/reviewRequestYacht"
    }

    override fun send(
        kind: ReviewKind,
        context: ReviewReservationContext,
        locale: String,
        reviewUrl: String,
    ) {
        val javaLocale = ReviewLinks.toLocale(locale)
        val variables = variables(kind, context, locale, reviewUrl)
        val fullName = context.customerFullName
        val email = context.flowEmail!!.trim()
        val recipient = if (fullName != null) "$fullName <$email>" else email
        val subject =
            when (kind) {
                ReviewKind.BOOKING ->
                    messageSource.getMessage(
                        "reviewBooking.subject",
                        arrayOf<Any>(context.reservationNumber ?: context.reservationId.toString()),
                        javaLocale,
                    )
                ReviewKind.YACHT ->
                    messageSource.getMessage("reviewYacht.subject", arrayOf<Any>(context.yachtFullLabel), javaLocale)
            }
        emailService.sendEmail(
            recipients = listOf(recipient),
            subject = subject,
            templateName = if (kind == ReviewKind.BOOKING) BOOKING_TEMPLATE else YACHT_TEMPLATE,
            variables = variables,
            locale = javaLocale,
        )
    }

    fun variables(
        kind: ReviewKind,
        context: ReviewReservationContext,
        locale: String,
        reviewUrl: String,
    ): Map<String, Any?> {
        val dateFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", ReviewLinks.toLocale(locale))
        val charterDates =
            listOfNotNull(context.dateFrom, context.dateTo)
                .joinToString(" – ") { it.toLocalDate().format(dateFormatter) }
                .ifBlank { null }
        return mapOf(
            "kind" to kind.name,
            "fullName" to (context.customerFullName ?: "there"),
            "reservationId" to (context.reservationNumber ?: context.reservationId.toString()),
            "yachtFullLabel" to context.yachtFullLabel,
            "yachtImageUrl" to context.yachtMainImageId?.let { "${serverHost.trimEnd('/')}/public/image/$it?width=936" },
            "viewBoatUrl" to context.yachtId?.let { "${serverHostPublic.trimEnd('/')}/boat/$it" },
            "pickupLocation" to context.baseName.orEmpty(),
            "pickupCountry" to context.baseCountry.orEmpty(),
            "charterDates" to charterDates,
            "reviewUrl" to reviewUrl,
            // One link per star: the form opens with that rating preselected (the web page reads ?rating=).
            "starLinks" to (1..5).map { mapOf("rating" to it, "url" to "$reviewUrl?rating=$it") },
            "validDays" to ReviewTokens.VALIDITY.toDays(),
            "currentYear" to LocalDate.now().year.toString(),
        )
    }
}
