package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.common.services.resolveEmailLocale
import org.openapitools.model.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.custompayment.service.EmailCustomPaymentMailer
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What the two e-mails of a recorded booking card payment need; read in one transaction by ReservationCardOrderTx. */
data class BookingPaymentNotice(
    val ledgerId: Long,
    val reservationId: Long,
    /** Reservation number as on the ledger row (sanitised), e.g. 1001112/2026. */
    val reference: String,
    val amountMinor: Long,
    val baseAmount: BigDecimal,
    val surchargeAmount: BigDecimal,
    val providerOrderId: String,
    val checkoutToken: UUID,
    val completedAt: Instant,
    val payerEmail: String?,
    val language: LanguageEnum?,
)

/**
 * The e-mails of an online-booking instalment paid by card through Mollie (Mario 10.11.2026: the system marks the
 * instalment paid, the office must be told at once and keeps confirming the booking by hand):
 * - office: "Card payment received" to every admin address (template email/customPaymentAdmin with the booking intro,
 *   English, Reply-To the client);
 * - client: the same "Payment confirmation" as the /pay receipt (email/customPaymentReceipt, the client's language,
 *   Boat4You brand), reference = the reservation number.
 * Called AFTER the completion transaction committed, once per payment (only by the caller that completed the row).
 * Never throws: a mail that cannot be queued is logged, the payment stays recorded.
 */
@Component
class BookingCardPaymentMailer(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    private val messageSource: MessageSource,
    @Value("\${server.host-admin-public}") private val serverHostAdminPublic: String,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun paymentRecorded(
        notice: BookingPaymentNotice,
        cardLabel: String?,
    ) {
        runCatching { office(notice, cardLabel) }
            .onFailure { log.error("Mollie booking ledger {}: office notification could not be queued", notice.ledgerId, it) }
        runCatching { receipt(notice, cardLabel) }
            .onFailure { log.error("Mollie booking ledger {}: client receipt could not be queued", notice.ledgerId, it) }
    }

    private fun office(
        notice: BookingPaymentNotice,
        cardLabel: String?,
    ) {
        val recipients = userRepository.findAllAdminEmailAddresses()
        if (recipients.isEmpty()) {
            log.warn("Mollie booking ledger {}: office notification not sent - no admin e-mail addresses", notice.ledgerId)
            return
        }
        val amount = EmailCustomPaymentMailer.amountLabel(notice.amountMinor, CURRENCY)
        val bookingUrl = "${serverHostAdminPublic.trimEnd('/')}/bookings/${notice.reservationId}"
        emailService.sendEmail(
            recipients = recipients,
            subject = "Card payment received: $amount · booking ${notice.reference} · Boat4You",
            templateName = EmailCustomPaymentMailer.ADMIN_TEMPLATE,
            variables =
                mapOf(
                    "title" to "Card payment received",
                    "bookingIntro" to
                        "A client paid an instalment of online booking ${notice.reference} by card (Mollie). The instalment is " +
                        "marked as paid; confirming the booking with the charter company stays a manual step. A payment " +
                        "confirmation e-mail is sent to the client. Reply goes to the client.",
                    "brandName" to BRAND,
                    "siteKey" to "boat4you",
                    "locale" to (notice.language?.value?.lowercase() ?: "en"),
                    "amountLabel" to amount,
                    "amountNote" to "instalment ${notice.baseAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}€ + card surcharge ${notice.surchargeAmount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}€",
                    "reference" to notice.reference,
                    "payerEmail" to (notice.payerEmail ?: "-"),
                    "paidAtLabel" to EmailCustomPaymentMailer.adminDateLabel(notice.completedAt),
                    "cardLabel" to cardLabel,
                    "providerOrderId" to notice.providerOrderId,
                    "paymentRef" to notice.checkoutToken.toString(),
                    "paymentId" to notice.ledgerId,
                    "reservationUrl" to bookingUrl,
                    "reservationLinkText" to "Open booking ${notice.reference} →",
                    "adminUrl" to bookingUrl,
                    "adminLabel" to "Open the booking in admin →",
                ),
            replyTo = notice.payerEmail,
        )
    }

    private fun receipt(
        notice: BookingPaymentNotice,
        cardLabel: String?,
    ) {
        val to = notice.payerEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (to == null) {
            log.warn("Mollie booking ledger {}: client receipt not sent - the booking has no e-mail address", notice.ledgerId)
            return
        }
        val locale = resolveEmailLocale(notice.language, forceEnglish = notice.language == null)
        val siteUrl = serverHostPublic.trim().trimEnd('/')
        emailService.sendEmail(
            recipients = listOf(to),
            subject =
                messageSource
                    .getMessage("customPaymentReceipt.subject", arrayOf<Any>(BRAND, notice.reference), locale)
                    .replace(LINE_BREAKS, " ")
                    .trim(),
            templateName = EmailCustomPaymentMailer.RECEIPT_TEMPLATE,
            variables =
                mapOf(
                    "lang" to locale.language,
                    "brandName" to BRAND,
                    "showBoat4YouLogo" to true,
                    "siteUrl" to siteUrl,
                    "siteHost" to siteUrl.substringAfter("://"),
                    "amountLabel" to EmailCustomPaymentMailer.amountLabel(notice.amountMinor, CURRENCY),
                    "reference" to notice.reference,
                    "paidAtLabel" to EmailCustomPaymentMailer.receiptDateLabel(notice.completedAt, locale),
                    "cardLabel" to cardLabel,
                    "paymentRef" to notice.checkoutToken.toString(),
                    "currentYear" to LocalDate.now(ZAGREB).year.toString(),
                ),
            extraInlineImages = mapOf(EmailCustomPaymentMailer.BOAT4YOU_LOGO_CID to ClassPathResource(BOAT4YOU_LOGO_CLASSPATH)),
            locale = locale,
        )
    }

    private companion object {
        const val BRAND = "Boat4You"
        const val CURRENCY = "EUR"
        const val BOAT4YOU_LOGO_CLASSPATH = "data/images/boat4you-logo-full.png"
        val ZAGREB: ZoneId = ZoneId.of("Europe/Zagreb")
        val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}
