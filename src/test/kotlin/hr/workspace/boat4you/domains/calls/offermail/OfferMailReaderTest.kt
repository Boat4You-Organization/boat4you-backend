package hr.workspace.boat4you.domains.calls.offermail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Properties

/** The reader against a real IMAP server (GreenMail, plain IMAP on a local port): Sent folder, newest mail, parts, failures. */
class OfferMailReaderTest {
    private val mail = GreenMail(ServerSetupTest.IMAP)

    @BeforeEach
    fun start() {
        mail.start()
        mail.setUser("offers@boat4you.com", "offers@boat4you.com", "pw,with:colon")
        mail.setUser("info@europe-yachts.com", "info@europe-yachts.com", "pw2")
    }

    @AfterEach
    fun stop() = mail.stop()

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun reader(extra: String = "") =
        OfferMailReader(
            OfferMailboxes("offers@boat4you.com:${b64("pw,with:colon")},info@europe-yachts.com:${b64("pw2")}$extra", "127.0.0.1"),
        ).apply {
            protocol = "imap"
            port = ServerSetupTest.IMAP.port
        }

    private fun putSent(
        user: String,
        password: String,
        to: String,
        sent: Instant,
        build: (MimeMessage) -> Unit,
    ) {
        val session = Session.getInstance(Properties())
        val store = session.getStore("imap")
        store.connect("127.0.0.1", ServerSetupTest.IMAP.port, user, password)
        val folder = store.getFolder("INBOX.Sent")
        if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
        val msg =
            MimeMessage(session).apply {
                setFrom(InternetAddress(user))
                setRecipient(Message.RecipientType.TO, InternetAddress(to))
                sentDate = Date.from(sent)
                subject = "Your offer"
                build(this)
                saveChanges()
            }
        folder.appendMessages(arrayOf(msg))
        store.close()
    }

    @Test
    fun `finds the newest offer to the client over all mailboxes, text parts in order, attachments by name`() {
        val since = Instant.parse("2026-11-10T00:00:00Z")
        putSent("offers@boat4you.com", "pw,with:colon", "anna@example.com", Instant.parse("2026-11-11T09:00:00Z")) { it.setText("older offer") }
        putSent("info@europe-yachts.com", "pw2", "Anna@Example.com", Instant.parse("2026-11-12T09:00:00Z")) { m ->
            val pdf =
                MimeBodyPart().apply {
                    setContent(byteArrayOf(1, 2), "application/pdf")
                    fileName = "offer.pdf"
                }
            m.setContent(MimeMultipart("mixed", MimeBodyPart().apply { setText("Lagoon 42 7.850 EUR") }, pdf, MimeBodyPart().apply { setText("See you!") }))
        }
        putSent("offers@boat4you.com", "pw,with:colon", "other@example.com", Instant.parse("2026-11-13T09:00:00Z")) { it.setText("not Anna") }

        val search = reader().latestTo("anna@example.com", since)
        val sent = search.sent.shouldNotBeNull()
        sent.mailbox shouldBe "info@europe-yachts.com"
        sent.body shouldContain "Lagoon 42 7.850 EUR"
        sent.body shouldContain "See you!"
        sent.attachments shouldContainExactly listOf("offer.pdf")
        search.failed shouldBe emptyList()
        search.searched shouldBe 2
    }

    @Test
    fun `mails before the inquiry window and lookalike addresses do not count`() {
        putSent("offers@boat4you.com", "pw,with:colon", "anna@example.com", Instant.parse("2026-11-01T09:00:00Z")) { it.setText("too old") }
        putSent("offers@boat4you.com", "pw,with:colon", "joanna@example.com", Instant.parse("2026-11-12T09:00:00Z")) { it.setText("lookalike") }
        reader().latestTo("anna@example.com", Instant.parse("2026-11-10T00:00:00Z")).sent.shouldBeNull()
    }

    @Test
    fun `a mailbox with a wrong password is reported, the others are still read`() {
        putSent("offers@boat4you.com", "pw,with:colon", "anna@example.com", Instant.parse("2026-11-12T09:00:00Z")) { it.setText("offer") }
        mail.setUser("bad@boat4you.com", "bad@boat4you.com", "right")
        val search = reader(",bad@boat4you.com:${b64("wrong")}").latestTo("anna@example.com", Instant.parse("2026-11-10T00:00:00Z"))
        search.sent.shouldNotBeNull().body shouldContain "offer"
        search.failed shouldBe listOf("bad@boat4you.com")
    }

    @Test
    fun `mailbox list parsing`() {
        val boxes = OfferMailboxes.parse("A@Boat4You.com:${b64("x:y,z")}, broken, b@x.com:, c@cy.com:${b64("p")}", "")
        boxes.map { it.address } shouldBe listOf("a@boat4you.com", "c@cy.com")
        boxes.first().password shouldBe "x:y,z"
        boxes.first().host shouldBe "mail.boat4you.com"
        boxes.first().toString().contains("x:y") shouldBe false
        OfferMailboxes.parse("a@b.com:${b64("p")}", "server.example").single().host shouldBe "server.example"
        OfferMailboxes("", "").all shouldBe emptyList()
    }
}
