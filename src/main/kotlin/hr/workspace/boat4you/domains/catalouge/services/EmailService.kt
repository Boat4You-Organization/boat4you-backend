package hr.workspace.boat4you.domains.catalouge.services

import jakarta.mail.internet.MimeMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Service
class EmailService(
    private val mailSender: JavaMailSender,
    private val templateEngine: SpringTemplateEngine,
    @Value("\${application.email.from-address}")
    private val emailFrom: String,
    @Value("\${application.email.enabled}")
    private val emailEnabledInConfig: Boolean,
    @Value("\${application.email.dev-log-to-file:false}")
    private val devLogToFile: Boolean,
    @Value("\${application.email.dev-log-dir:/tmp/b4y-emails}")
    private val devLogDir: String,
    @Value("classpath:data/images/boat4you-logo-full.png")
    private val boat4youLogoFull: Resource,
    @Value("classpath:data/images/phone-icon.png")
    private val phoneIcon: Resource,
    @Value("classpath:data/images/email-icon.png")
    private val emailIcon: Resource,
    @Value("classpath:data/images/booking-vector.png")
    private val bookingVectorIcon: Resource,
    @Value("classpath:data/documents/terms-and-conditions-boat4you.pdf")
    private val termsAndConditionsDocument: Resource,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    private val templatesWithBookingVector = setOf("email/optionExpired", "email/reservationPaymentPending")

    /** Templates that DON'T use the shared customer-flow footer
     *  (boat4youLogoFull header + envelope/phone contact strip). Adding
     *  these inline images unconditionally means Gmail / Outlook surface
     *  them as "attached images" thumbnails on emails that never
     *  reference them — which is exactly what Mario flagged on the
     *  inquiry notification (loose envelope + phone icons in the
     *  bottom-right corner). Skip the inlines for these templates so the
     *  inbox stays clean.
     *
     *  Inquiry notification ships its own brand-specific logo via
     *  `extraInlineImages` from the caller; nothing to attach globally. */
    private val templatesWithoutSharedFooter = setOf("email/inquiryNotification")

    private val templatesWithTermsAndConditions = setOf("email/reservationConfirmed", "email/reservationConfirmedPaymentCard")

    private val devLogFileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    fun sendEmail(
        recipients: List<String>,
        subject: String,
        templateName: String,
        variables: Map<String, Any?>,
        isHtml: Boolean = true,
        /** Optional `Reply-To` header. When set, hitting "Reply" in the
         *  inbox routes the reply to this address instead of [emailFrom].
         *  Used by inquiry notifications so Mario's reply lands directly
         *  in the lead's mailbox without copy-pasting their email. */
        replyTo: String? = null,
        /** Bypass the global `application.email.enabled` flag — used for
         *  one-off real-SMTP tests in dev (e.g. previewing the inquiry
         *  email in an actual inbox) without flipping every other email
         *  flow on at the same time. Defaults to false; production sends
         *  flow through the regular gate. */
        force: Boolean = false,
        /** Per-call extra inline images. Map key = `cid:` content id the
         *  template references (e.g. "brandLogoMark"); value = the
         *  resource to attach. Used by the multi-brand inquiry path so
         *  each tenant's logo lands in the right inbox without baking
         *  every brand into the shared template setup. */
        extraInlineImages: Map<String, Resource> = emptyMap(),
        /** Override for the visible From address. When set, used instead
         *  of the configured [emailFrom]. Gives multi-brand callers a
         *  way to render `Catamaran Croatia <…>` etc. without touching
         *  global config. */
        fromOverride: String? = null,
    ) {
        if (!emailEnabledInConfig && !force) {
            // SMTP disabled (typically dev). The dev-log-to-file escape hatch
            // renders the template to disk so the flow that would normally
            // depend on the email (invite link, password reset, booking
            // confirmation) stays testable locally without real SMTP.
            if (devLogToFile) {
                writeEmailToDevLog(recipients, subject, templateName, variables)
            }
            return
        }

        val message: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true)

        val context =
            Context().apply {
                setVariables(variables)
            }

        val content = templateEngine.process(templateName, context)

        helper.setTo(recipients.toTypedArray())
        helper.setFrom(fromOverride?.takeIf { it.isNotBlank() } ?: emailFrom)
        if (!replyTo.isNullOrBlank()) {
            helper.setReplyTo(replyTo)
        }
        helper.setSubject(subject)
        helper.setText(content, isHtml)

        if (templateName !in templatesWithoutSharedFooter) {
            helper.addInline("boat4youLogoFull", boat4youLogoFull, INLINE_IMAGE_MIMETYPE)
            helper.addInline("emailIcon", emailIcon, INLINE_IMAGE_MIMETYPE)
            helper.addInline("phoneIcon", phoneIcon, INLINE_IMAGE_MIMETYPE)
        }
        if (templateName in templatesWithBookingVector) {
            helper.addInline("bookingVector", bookingVectorIcon, INLINE_IMAGE_MIMETYPE)
        }
        // Per-call inline images, e.g. brand-specific logo for inquiry
        // notifications. Loaded as `cid:{key}` from the template.
        extraInlineImages.forEach { (cid, resource) ->
            helper.addInline(cid, resource, INLINE_IMAGE_MIMETYPE)
        }
        if (templateName in templatesWithTermsAndConditions) {
            helper.addAttachment("terms-and-conditions-boat4you.pdf", termsAndConditionsDocument)
        }

        // Defer the actual SMTP submit until AFTER the surrounding JPA tx
        // commits. Without this, an email queued inside @Transactional that
        // later rolls back still gets delivered — customer receives e.g.
        // "reservation confirmed" while the reservation never persisted.
        // When called outside a tx (cron job, no JPA scope), send immediately.
        val doSend: () -> Unit = {
            executor.submit {
                try {
                    logger.debug("Sending email with subject '{}' to '{}'", subject, recipients)
                    mailSender.send(message)
                    logger.debug("Sent email with subject '{}' to '{}'", subject, recipients)
                } catch (e: MailException) {
                    logger.error("Could not send email to '$recipients' because '${e.message}'")
                }
            }
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                object : org.springframework.transaction.support.TransactionSynchronization {
                    override fun afterCommit() = doSend()
                },
            )
        } else {
            doSend()
        }
    }

    /** Render a Thymeleaf email template to HTML without sending. Used by
     *  preview endpoints so admin can review the layout in a browser before
     *  the real send is wired up. The render uses the same engine + same
     *  variables map as `sendEmail`, so what you preview is exactly what
     *  the recipient will get. Inline `cid:*` images won't render in a
     *  browser preview — that's expected; they resolve only when delivered
     *  through SMTP. */
    fun renderTemplate(
        templateName: String,
        variables: Map<String, Any?>,
    ): String {
        val context = Context().apply { setVariables(variables) }
        return templateEngine.process(templateName, context)
    }

    private fun writeEmailToDevLog(
        recipients: List<String>,
        subject: String,
        templateName: String,
        variables: Map<String, Any?>,
    ) {
        try {
            val dir = Paths.get(devLogDir)
            Files.createDirectories(dir)

            val timestamp = LocalDateTime.now().format(devLogFileNameFormatter)
            val safeTemplate = templateName.replace('/', '_').replace(Regex("[^A-Za-z0-9_-]"), "_")
            val safeRecipient =
                recipients
                    .firstOrNull()
                    .orEmpty()
                    .replace(Regex("[^A-Za-z0-9@._-]"), "_")
                    .ifBlank { "unknown" }
            val file = dir.resolve("$timestamp-$safeTemplate-$safeRecipient.html")

            val context = Context().apply { setVariables(variables) }
            val content = templateEngine.process(templateName, context)

            // Prepend a small metadata header so a human opening the file knows
            // who it was addressed to — inline cid:*  images won't render, but
            // the text/copy is fully reviewable.
            val header =
                buildString {
                    appendLine("<!--")
                    appendLine("  Boat4you DEV email capture")
                    appendLine("  From:    $emailFrom")
                    appendLine("  To:      ${recipients.joinToString(", ")}")
                    appendLine("  Subject: $subject")
                    appendLine("  Template: $templateName")
                    appendLine("  At:      ${LocalDateTime.now()}")
                    appendLine("-->")
                }

            Files.writeString(file, header + content)
            logger.info(
                "[DEV email] captured to '{}' (subject='{}', to={}, template={})",
                file,
                subject,
                recipients,
                templateName,
            )
        } catch (e: Exception) {
            logger.error("Failed to write DEV email capture for subject '$subject'", e)
        }
    }

    companion object {
        private const val INLINE_IMAGE_MIMETYPE = "image/png"
    }
}
