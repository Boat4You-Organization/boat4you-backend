package hr.workspace.boat4you.domains.catalouge.services

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.core.io.ByteArrayResource
import org.springframework.mail.javamail.JavaMailSender
import org.thymeleaf.context.IContext
import org.thymeleaf.spring6.SpringTemplateEngine
import java.util.Properties

/** 19.11.2026: the inquiry acknowledgement arrived in Gmail with "2 attachments" — the unreferenced brandLogoMark and
 *  a second boat4youLogoFull part with the same Content-ID. Only referenced images, each once. */
class EmailServiceInlineImagesTest {

    private fun png() = ByteArrayResource(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

    private fun send(html: String, template: String, extras: Map<String, ByteArrayResource>): MimeMessage {
        val mailSender = mock(JavaMailSender::class.java)
        `when`(mailSender.createMimeMessage()).thenReturn(MimeMessage(Session.getInstance(Properties())))
        val engine = mock(SpringTemplateEngine::class.java)
        `when`(engine.process(anyString(), any(IContext::class.java))).thenReturn(html)
        val service = EmailService(
            mailSender, engine, "no-reply@boat4you.com", "", true, false, "/tmp",
            png(), png(), png(), png(), png(),
        )
        service.sendEmail(
            recipients = listOf("client@example.com"),
            subject = "s",
            templateName = template,
            variables = emptyMap(),
            extraInlineImages = extras,
            syncSend = true,
        )
        val captor = ArgumentCaptor.forClass(MimeMessage::class.java)
        verify(mailSender).send(captor.capture())
        return captor.value.also { it.saveChanges() }
    }

    private fun parts(part: Part): List<Part> =
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as MimeMultipart
            (0 until mp.count).flatMap { parts(mp.getBodyPart(it)) }
        } else {
            listOf(part)
        }

    private fun imageCids(message: MimeMessage) =
        parts(message).filter { it.isMimeType("image/*") }.map {
            it.disposition shouldBe Part.INLINE
            (it as jakarta.mail.internet.MimeBodyPart).contentID
        }

    @Test
    fun `inquiry acknowledgement carries only the referenced footer logo, once`() {
        val html = """<html><body><img src="https://cdn/yacht.jpg"/><img src="cid:boat4youLogoFull"/></body></html>"""
        val message = send(
            html,
            "email/inquiryClientAck",
            mapOf("brandLogoMark" to png(), "boat4youLogoFull" to png()),
        )
        imageCids(message) shouldContainExactlyInAnyOrder listOf("<boat4youLogoFull>")
    }

    @Test
    fun `referenced per-call image is still attached inline`() {
        val html = """<html><body><img src="cid:brandLogoMark"/><img src="cid:boat4youLogoFull"/></body></html>"""
        val message = send(
            html,
            "email/inquiryNotification",
            mapOf("brandLogoMark" to png(), "boat4youLogoFull" to png()),
        )
        imageCids(message) shouldContainExactlyInAnyOrder listOf("<brandLogoMark>", "<boat4youLogoFull>")
    }
}
