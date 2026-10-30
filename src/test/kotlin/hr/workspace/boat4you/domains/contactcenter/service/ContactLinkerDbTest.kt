package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ContactLinkerDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val linker = ContactLinker(jdbc, ccTx(jdbc))

    private fun session(
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        visitorId: Long? = null,
        deleted: Boolean = false,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO ai_chat_session (token, visitor_name, visitor_email, phone, visitor_id, deleted_at) " +
                "VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END) RETURNING id",
            Long::class.java,
            UUID.randomUUID().toString(),
            name,
            email,
            phone,
            visitorId,
            deleted,
        )!!

    private fun contactOf(sessionId: Long): Long? = jdbc.queryForObject("SELECT contact_id FROM ai_chat_session WHERE id = ?", Long::class.java, sessionId)

    private fun contacts(): Long = jdbc.queryForObject("SELECT count(*) FROM cc_contact", Long::class.java)!!

    @Test
    fun `a session with an email gets a new contact with its name, email and phone`() {
        val s = session(name = "Ana", email = "ana@example.com", phone = "+385 91 111")
        val id = linker.ensureForSession(s).shouldNotBeNull()
        val row = jdbc.queryForMap("SELECT name, email, phone, status, email_verified FROM cc_contact WHERE id = ?", id)
        row["name"] shouldBe "Ana"
        row["email"] shouldBe "ana@example.com"
        row["phone"] shouldBe "+385 91 111"
        row["status"] shouldBe "NEW"
        row["email_verified"] shouldBe false
        contactOf(s) shouldBe id
    }

    @Test
    fun `a second session of the same visitor reuses the visitor's contact`() {
        val (visitorId, _) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", Instant.now())
        val first = linker.ensureForSession(session(name = "Ana", visitorId = visitorId)).shouldNotBeNull()
        val second = linker.ensureForSession(session(email = "ana@example.com", visitorId = visitorId))
        second shouldBe first
        jdbc.queryForObject("SELECT contact_id FROM cc_visitor WHERE id = ?", Long::class.java, visitorId) shouldBe first
        jdbc.queryForObject("SELECT email FROM cc_contact WHERE id = ?", String::class.java, first) shouldBe "ana@example.com"
        contacts() shouldBe 1
    }

    @Test
    fun `the same email from another visitor is NOT merged automatically`() {
        val a = linker.ensureForSession(session(email = "ana@example.com"))
        val b = linker.ensureForSession(session(email = "ana@example.com"))
        a shouldNotBe b
        contacts() shouldBe 2
    }

    @Test
    fun `a session without name, email and phone gets no contact`() {
        val s = session()
        linker.ensureForSession(s).shouldBeNull()
        contacts() shouldBe 0
    }

    @Test
    fun `a refused name alone gives no contact`() {
        linker.ensureForSession(session(name = "test")).shouldBeNull()
        contacts() shouldBe 0
    }

    @Test
    fun `a deleted session is skipped, an existing contact is kept`() {
        linker.ensureForSession(session(email = "x@example.com", deleted = true)).shouldBeNull()
        val s = session(email = "y@example.com")
        val id = linker.ensureForSession(s)
        linker.ensureForSession(s) shouldBe id
        contacts() shouldBe 1
    }

    @Test
    fun `the contact's name is filled from a later session only when empty`() {
        val (visitorId, _) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", Instant.now())
        val id = linker.ensureForSession(session(email = "m@example.com", visitorId = visitorId)).shouldNotBeNull()
        linker.ensureForSession(session(name = "Marko", visitorId = visitorId))
        jdbc.queryForObject("SELECT name FROM cc_contact WHERE id = ?", String::class.java, id) shouldBe "Marko"
        linker.ensureForSession(session(name = "Ivan", visitorId = visitorId))
        jdbc.queryForObject("SELECT name FROM cc_contact WHERE id = ?", String::class.java, id) shouldBe "Marko"
    }

    @Test
    fun `linkPending walks old IRIS sessions in batches and skips the ones it cannot link`() {
        session(email = "a@example.com")
        session(name = "test")
        session(phone = "+385 91 222")
        session(name = "Lea")
        session()
        val first = linker.linkPending(afterId = 0, batch = 2)
        first.scanned shouldBe 2
        first.linked shouldBe 1
        val second = linker.linkPending(afterId = first.lastId, batch = 2)
        second.scanned shouldBe 2
        second.linked shouldBe 2
        val third = linker.linkPending(afterId = second.lastId, batch = 2)
        third.scanned shouldBe 0
        contacts() shouldBe 3
    }
}
