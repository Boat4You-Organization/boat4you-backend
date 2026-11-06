package hr.workspace.boat4you.domains.pr.service

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component
import java.util.Properties
import java.util.UUID

/**
 * The only thing in the PR module that puts a message on the wire: one message, one recipient, plain text UTF-8 (no
 * HTML, no images, no tracking), From `pr.mail.from` (press@boat4you.com) with a BCC copy to `pr.mail.bcc`. It has its
 * own SMTP connection from `pr.mail.*`; the app mail (spring.mail / EmailService) is not used. Synchronous: a failure is
 * thrown to the caller. Never logs the body, the password or the message.
 */
@Component
class PrMailSender(
    private val props: PrProperties,
    private val senderFactory: (PrProperties.Mail) -> JavaMailSender = ::defaultSender,
) {
    private val log = LoggerFactory.getLogger(PrMailSender::class.java)

    private val sender: JavaMailSender by lazy { senderFactory(props.mail) }

    /** Message parts only; the transport has its own session (JavaMailSenderImpl). */
    private val session: Session = Session.getInstance(Properties())

    /**
     * Sends one message and returns its Message-ID (`<uuid@boat4you.com>`), set by us and kept through saveChanges.
     * [inReplyTo] (the parent pitch's Message-ID) threads a follow-up: In-Reply-To and References.
     */
    fun send(
        to: String,
        subject: String,
        body: String,
        inReplyTo: String?,
    ): String {
        val messageId = "<${UUID.randomUUID()}@boat4you.com>"
        val message =
            FixedIdMimeMessage(session, messageId).apply {
                val from = InternetAddress.parse(props.mail.from, false).single()
                setFrom(InternetAddress(from.address, from.personal, UTF_8))
                setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
                setRecipient(Message.RecipientType.BCC, InternetAddress(props.mail.bcc, true))
                setSubject(subject, UTF_8)
                setText(body, UTF_8)
                inReplyTo?.let {
                    setHeader("In-Reply-To", it)
                    setHeader("References", it)
                }
            }
        try {
            sender.send(message)
        } catch (e: MailSendException) {
            // JavaMailSenderImpl throws a MailSendException without failed messages only when the server already took
            // the message and closing the connection failed: it was sent, and reporting a failure would invite a resend.
            if (e.failedMessages.isNotEmpty() || e.message?.startsWith(CLOSED_AFTER_SENDING) != true) throw e
            log.warn("PR mail {} was accepted by the server; closing the SMTP connection failed: {}", messageId, e.cause?.javaClass?.simpleName)
        }
        return messageId
    }

    /** saveChanges() (which JavaMailSenderImpl calls) would replace a Message-ID we set; this one keeps ours. */
    private class FixedIdMimeMessage(
        session: Session,
        private val id: String,
    ) : MimeMessage(session) {
        init {
            setHeader("Message-ID", id)
        }

        override fun updateMessageID() = setHeader("Message-ID", id)
    }

    companion object {
        private const val UTF_8 = "UTF-8"
        private const val CLOSED_AFTER_SENDING = "Failed to close server connection after message sending"
        private const val TIMEOUT_MS = "15000"

        /** SMTPS when `ssl` (465), otherwise STARTTLS required (587); 15 s connect / read / write timeouts. */
        fun defaultSender(mail: PrProperties.Mail): JavaMailSender =
            JavaMailSenderImpl().apply {
                host = mail.host
                port = mail.port
                username = mail.username
                password = mail.password
                protocol = "smtp"
                defaultEncoding = UTF_8
                javaMailProperties =
                    Properties().apply {
                        setProperty("mail.smtp.auth", "true")
                        setProperty("mail.smtp.ssl.enable", mail.ssl.toString())
                        setProperty("mail.smtp.starttls.enable", (!mail.ssl).toString())
                        setProperty("mail.smtp.starttls.required", (!mail.ssl).toString())
                        setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS)
                        setProperty("mail.smtp.timeout", TIMEOUT_MS)
                        setProperty("mail.smtp.writetimeout", TIMEOUT_MS)
                    }
            }
    }
}
