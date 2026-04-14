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

    private val templatesWithTermsAndConditions = setOf("email/reservationConfirmed", "email/reservationConfirmedPaymentCard")

    fun sendEmail(
        recipients: List<String>,
        subject: String,
        templateName: String,
        variables: Map<String, Any?>,
        isHtml: Boolean = true,
    ) {
        if (!emailEnabledInConfig) {
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
        helper.setFrom(emailFrom)
        helper.setSubject(subject)
        helper.setText(content, isHtml)

        helper.addInline("boat4youLogoFull", boat4youLogoFull, INLINE_IMAGE_MIMETYPE)
        helper.addInline("emailIcon", emailIcon, INLINE_IMAGE_MIMETYPE)
        helper.addInline("phoneIcon", phoneIcon, INLINE_IMAGE_MIMETYPE)
        if (templateName in templatesWithBookingVector) {
            helper.addInline("bookingVector", bookingVectorIcon, INLINE_IMAGE_MIMETYPE)
        }
        if (templateName in templatesWithTermsAndConditions) {
            helper.addAttachment("terms-and-conditions-boat4you.pdf", termsAndConditionsDocument)
        }

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

    companion object {
        private const val INLINE_IMAGE_MIMETYPE = "image/png"
    }
}
