package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.common.services.resolveEmailLocale
import hr.workspace.boat4you.common.services.toLanguageEnum
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.custompayment.CustomPaymentSite
import hr.workspace.boat4you.domains.custompayment.CustomPaymentSites
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.reservation.service.BookingFailureAlertService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** One e-mail as [EmailCustomPaymentMailer] hands it to EmailService (built separately so it can be tested). */
data class CustomPaymentMail(
    val recipients: List<String>,
    val subject: String,
    val templateName: String,
    val variables: Map<String, Any?>,
    val locale: Locale? = null,
    val replyTo: String? = null,
    /** Attach the Boat4You logo as `cid:boat4youLogoFull` (boat4you receipts only). */
    val inlineBoat4YouLogo: Boolean = false,
    val fromOverride: String? = null,
)

/**
 * The custom payment e-mails, through [EmailService]:
 * - [receipt]: "Payment confirmation" to the payer (template email/customPaymentReceipt, the payer's language from
 *   `custom_payment.locale`). Brand-neutral: the site's own brand + Cusmanich d.o.o.; the Boat4You logo only on the
 *   boat4you site, a text brand line elsewhere; a sister site's receipt also shows its brand as the From display name
 *   (same sender address). Never called an invoice.
 * - [adminNotification]: "Card payment received" to every SYSTEM_ADMIN (email/customPaymentAdmin, English), Reply-To
 *   the payer, link to the admin list filtered by the reference.
 * - [alert]: something a person must look at, as the plain report mail of BookingFailureAlertService
 *   (email/adminBookingFailed), without its throttle (each alert is a one-off state change of one row). No Reply-To:
 *   the body carries internal diagnostics.
 *
 * Called by CustomPaymentTx INSIDE its transaction, each call in `runCatching`. EmailService renders synchronously
 * (a template error throws here and is logged by the caller) and itself defers the SMTP submit to afterCommit, so a
 * rolled-back transition never mails — no afterCommit of our own.
 */
@Component
class EmailCustomPaymentMailer(
    private val emailService: EmailService,
    private val userRepository: UserRepository,
    private val messageSource: MessageSource,
    @Value("\${server.host-admin-public}") private val serverHostAdminPublic: String,
    @Value("\${application.email.from-address}") private val emailFrom: String,
) : CustomPaymentMailer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun receipt(payment: CustomPayment) = send(receiptMail(payment))

    override fun adminNotification(payment: CustomPayment) {
        val recipients = adminRecipients(payment, "office notification") ?: return
        send(adminMail(payment, recipients))
    }

    override fun alert(
        payment: CustomPayment,
        kind: CustomPaymentAlertKind,
        reason: String,
    ) {
        val recipients = adminRecipients(payment, "$kind alert") ?: return
        send(alertMail(payment, kind, reason, recipients))
    }

    fun receiptMail(payment: CustomPayment): CustomPaymentMail {
        val site = siteOf(payment)
        val locale = receiptLocale(payment)
        val amount = amountLabel(payment.amountMinor, payment.currency)
        return CustomPaymentMail(
            // Bare address: the /pay form has no name field.
            recipients = listOf(payment.payerEmail),
            subject =
                messageSource
                    .getMessage("customPaymentReceipt.subject", arrayOf<Any>(site.brandName, payment.reference), locale)
                    .singleLine(),
            templateName = RECEIPT_TEMPLATE,
            variables =
                mapOf(
                    "lang" to locale.language,
                    "brandName" to site.brandName,
                    "showBoat4YouLogo" to (site.key == BOAT4YOU_SITE),
                    "siteUrl" to site.baseUrl,
                    "siteHost" to site.baseUrl.substringAfter("://"),
                    "amountLabel" to amount,
                    "reference" to payment.reference,
                    "paidAtLabel" to receiptDateLabel(payment.completedAt ?: Instant.now(), locale),
                    "cardLabel" to cardLabel(payment),
                    "paymentRef" to payment.publicToken.toString(),
                    "currentYear" to LocalDate.now(ZAGREB).year.toString(),
                ),
            locale = locale,
            inlineBoat4YouLogo = site.key == BOAT4YOU_SITE,
            fromOverride = if (site.key == BOAT4YOU_SITE) null else brandedFrom(site.brandName),
        )
    }

    fun adminMail(
        payment: CustomPayment,
        recipients: List<String>,
    ): CustomPaymentMail {
        val site = siteOf(payment)
        val amount = amountLabel(payment.amountMinor, payment.currency)
        return CustomPaymentMail(
            recipients = recipients,
            subject = "${methodLabel(payment)} payment received: $amount · ${payment.reference} · ${site.brandName}".singleLine(),
            templateName = ADMIN_TEMPLATE,
            variables =
                mapOf(
                    "title" to "${methodLabel(payment)} payment received",
                    "brandName" to site.brandName,
                    "siteKey" to site.key,
                    "locale" to payment.locale,
                    "amountLabel" to amount,
                    "reference" to payment.reference,
                    "payerEmail" to payment.payerEmail,
                    "paidAtLabel" to adminDateLabel(payment.completedAt ?: Instant.now()),
                    "cardLabel" to cardLabel(payment),
                    "providerOrderId" to (payment.providerOrderId ?: "-"),
                    "providerPaymentId" to payment.providerPaymentId,
                    "paymentRef" to payment.publicToken.toString(),
                    "paymentId" to payment.id,
                    "reservationUrl" to payment.reservationId?.let { "${adminBase()}/bookings/$it" },
                    "adminUrl" to adminListUrl(payment),
                ),
            replyTo = payment.payerEmail,
        )
    }

    fun alertMail(
        payment: CustomPayment,
        kind: CustomPaymentAlertKind,
        reason: String,
        recipients: List<String>,
    ): CustomPaymentMail {
        val site = siteOf(payment)
        val amount = amountLabel(payment.amountMinor, payment.currency)
        val title = ALERT_TITLES.getValue(kind)
        val body =
            buildString {
                appendLine(ALERT_EXPLANATIONS.getValue(kind))
                appendLine()
                appendLine("Reason:     ${reason.singleLine()}")
                appendLine()
                appendLine("Site:       ${site.brandName} (${site.key})")
                appendLine("Reference:  ${payment.reference}")
                appendLine("Amount:     $amount (refunded ${amountLabel(payment.refundedAmountMinor, payment.currency)})")
                appendLine("Status:     ${payment.status}")
                appendLine("Payer:      ${payment.payerEmail}")
                appendLine("Created:    ${adminDateLabel(payment.createdAt)}")
                appendLine("Completed:  ${payment.completedAt?.let { adminDateLabel(it) } ?: "-"}")
                appendLine("Mollie:    payment ${payment.providerOrderId ?: "-"}")
                appendLine("Ours:       id ${payment.id}, payment reference ${payment.publicToken}")
                appendLine()
                appendLine("Admin:      ${adminListUrl(payment)}")
                append("Do not answer with Reply (this mail carries internal data) - write to the client in a new e-mail.")
            }
        return CustomPaymentMail(
            recipients = recipients,
            subject = "⚠️ $title: ${payment.reference}, $amount (${site.brandName})".singleLine().take(MAX_SUBJECT_CHARS),
            templateName = BookingFailureAlertService.TEMPLATE,
            variables = mapOf("title" to title, "reportBody" to body),
        )
    }

    private fun send(mail: CustomPaymentMail) {
        emailService.sendEmail(
            recipients = mail.recipients,
            subject = mail.subject,
            templateName = mail.templateName,
            variables = mail.variables,
            replyTo = mail.replyTo,
            extraInlineImages = if (mail.inlineBoat4YouLogo) mapOf(BOAT4YOU_LOGO_CID to boat4youLogo()) else emptyMap(),
            fromOverride = mail.fromOverride,
            locale = mail.locale,
        )
    }

    private fun adminRecipients(
        payment: CustomPayment,
        what: String,
    ): List<String>? {
        val recipients = userRepository.findAllAdminEmailAddresses()
        if (recipients.isEmpty()) {
            log.warn("Custom payment {}: {} not sent - no admin e-mail addresses", payment.id, what)
            return null
        }
        return recipients
    }

    /** row.locale is one of the site's locales (validated on create); anything else mails in English, never in the request's locale. */
    private fun receiptLocale(payment: CustomPayment): Locale {
        val language = Locale.forLanguageTag(payment.locale).toLanguageEnum()
        return resolveEmailLocale(language, forceEnglish = language == null)
    }

    /** The configured sender address with the site's brand as display name; null (= default From) if it cannot be parsed. */
    private fun brandedFrom(brandName: String): String? =
        runCatching { InternetAddress(InternetAddress(emailFrom).address, brandName, "UTF-8").toString() }
            .onFailure { log.warn("Custom payment receipt: application.email.from-address not parseable, default From used") }
            .getOrNull()

    private fun methodLabel(payment: CustomPayment): String = if (payment.method == CustomPayment.METHOD_KLARNA) "Klarna" else "Card"

    /** The card of the successful payment: the one Mollie reported as paid, else the last successful one; null when unknown. */
    private fun cardLabel(payment: CustomPayment): String? {
        if (payment.method == CustomPayment.METHOD_KLARNA) return "Klarna"
        val payments = OrderSnapshot.parseOrNull(payment.orderSnapshot)?.payments ?: return null
        val card =
            payment.providerPaymentId?.let { id -> payments.firstOrNull { it.id == id } }
                ?: payments.lastOrNull { it.state.lowercase() in SUCCESSFUL_PAYMENT_STATES }
                ?: return null
        val brand =
            card.cardBrand
                ?.split('_')
                ?.filter { it.isNotBlank() }
                ?.joinToString(" ") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
        val lastFour = card.cardLastFour?.let { "•••• $it" }
        return listOfNotNull(brand, lastFour).joinToString(" ").ifBlank { null }
    }

    private fun adminListUrl(payment: CustomPayment): String = "${adminBase()}/custom-payments?q=${URLEncoder.encode(payment.reference, StandardCharsets.UTF_8)}"

    private fun adminBase(): String = serverHostAdminPublic.trimEnd('/')

    private fun siteOf(payment: CustomPayment): CustomPaymentSite =
        checkNotNull(CustomPaymentSites.byKey(payment.site)) { "Custom payment ${payment.id}: unknown site ${payment.site}" }

    private fun boat4youLogo(): Resource = ClassPathResource(BOAT4YOU_LOGO_CLASSPATH)

    companion object {
        const val RECEIPT_TEMPLATE = "email/customPaymentReceipt"
        const val ADMIN_TEMPLATE = "email/customPaymentAdmin"
        const val BOAT4YOU_LOGO_CID = "boat4youLogoFull"
        private const val BOAT4YOU_LOGO_CLASSPATH = "data/images/boat4you-logo-full.png"
        private const val BOAT4YOU_SITE = "boat4you"
        private const val MAX_SUBJECT_CHARS = 140
        private val ZAGREB: ZoneId = ZoneId.of("Europe/Zagreb")
        private val SUCCESSFUL_PAYMENT_STATES = setOf("paid")
        private val LINE_BREAKS = Regex("[\\r\\n]+")
        private val ADMIN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private val ALERT_TITLES =
            mapOf(
                CustomPaymentAlertKind.NEEDS_REVIEW to "Custom payment needs review",
                CustomPaymentAlertKind.LATE_COMPLETION to "Custom payment completed late",
                CustomPaymentAlertKind.REFUND to "Custom payment refunded",
                CustomPaymentAlertKind.CHARGEBACK to "Custom payment CHARGEBACK",
                CustomPaymentAlertKind.STALE to "Custom payment still pending after 48 h",
            )

        private val ALERT_EXPLANATIONS =
            mapOf(
                CustomPaymentAlertKind.NEEDS_REVIEW to
                    "Mollie reports a completed payment that does not match this attempt. The client got NO payment " +
                    "confirmation. Check the order in the Mollie dashboard before telling the client anything.",
                CustomPaymentAlertKind.LATE_COMPLETION to
                    "The payment completed after the attempt had already been closed. The client has now received the " +
                    "payment confirmation and the office the usual notification - check that it is not a second " +
                    "payment for the same contract.",
                CustomPaymentAlertKind.REFUND to
                    "Money was refunded for this payment (Mollie dashboard). The client is not e-mailed by us.",
                CustomPaymentAlertKind.CHARGEBACK to
                    "The card holder disputed this payment and Mollie took the money back (chargeback). Answer the " +
                    "dispute in the Mollie dashboard with the signed contract before its deadline.",
                CustomPaymentAlertKind.STALE to
                    "This attempt is still pending 48 hours after it was created; Mollie neither completed nor failed " +
                    "it. It is now NEEDS_REVIEW - check the order in the Mollie dashboard.",
            )

        /** Like the reservation e-mails: two decimals (HALF_UP), then the currency symbol ("2500.00€"). */
        fun amountLabel(
            minor: Long,
            currency: String,
        ): String {
            val symbol = runCatching { Currency.getInstance(currency).getSymbol(Locale.ENGLISH) }.getOrDefault(currency)
            return "${BigDecimal.valueOf(minor, 2).toPlainString()}$symbol"
        }

        /** "October 13, 2026 · 15:20 (GMT+2)" in the payer's language, Europe/Zagreb wall clock. */
        fun receiptDateLabel(
            at: Instant,
            locale: Locale,
        ): String {
            val zoned = at.atZone(ZAGREB)
            val date = zoned.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            return "$date · ${zoned.format(TIME)} ${gmtOffset(at)}"
        }

        /** "Oct 13, 2026 · 15:20 (GMT+2)" as in the other office e-mails. */
        fun adminDateLabel(at: Instant): String = "${at.atZone(ZAGREB).format(ADMIN_DATE)} ${gmtOffset(at)}"

        private fun gmtOffset(at: Instant): String {
            val hours = at.atZone(ZAGREB).offset.totalSeconds / 3600
            return "(GMT${if (hours >= 0) "+" else "-"}${abs(hours)})"
        }

        private fun String.singleLine(): String = replace(LINE_BREAKS, " ").trim()
    }
}
