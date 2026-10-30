package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class ContactServiceDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val service = ContactService(jdbc, ccTx(jdbc))

    private fun contact(
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        status: String = "NEW",
        note: String? = null,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO cc_contact (name, email, phone, status, note, email_verified, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long::class.java,
            name,
            email,
            phone,
            status,
            note,
            email != null,
            Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
        )!!

    private fun row(id: Long) = jdbc.queryForMap("SELECT * FROM cc_contact WHERE id = ?", id)

    private fun tags(id: Long) = jdbc.queryForList("SELECT tag FROM cc_contact_tag WHERE contact_id = ? ORDER BY tag", String::class.java, id)

    @Test
    fun `status accepts only ContactStatus names`() {
        val id = contact()
        shouldThrow<IllegalArgumentException> { service.update(id, null, null, null, "VIP", null) }
        service.update(id, null, null, null, "OFFER_SENT", null) shouldBe true
        row(id)["status"] shouldBe "OFFER_SENT"
        service.update(404, null, null, null, null, null) shouldBe false
    }

    @Test
    fun `update changes given fields, clears blank ones, and moves updated_at`() {
        val id = contact(name = "Ana", email = "a@x.com", phone = "+385", note = "stara")
        service.update(id, " Ana Horvat ", "b@x.com", "", null, null) shouldBe true
        row(id).let {
            it["name"] shouldBe "Ana Horvat"
            it["email"] shouldBe "b@x.com"
            it["email_verified"] shouldBe false
            it["phone"] shouldBe null
            it["note"] shouldBe "stara"
            it["updated_at"] shouldNotBe Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
        }
    }

    @Test
    fun `email or phone longer than its column is refused`() {
        val id = contact()
        shouldThrow<IllegalArgumentException> { service.update(id, null, "a".repeat(250) + "@x.com", null, null, null) }
        shouldThrow<IllegalArgumentException> { service.update(id, null, null, "1".repeat(41), null, null) }
    }

    @Test
    fun `tag spam sets SPAM, removing it does not change the status back`() {
        val id = contact(status = "REPLIED")
        service.setTags(id, listOf(" VIP ", "spam", "vip", "")) shouldContainExactly listOf("spam", "vip")
        row(id)["status"] shouldBe "SPAM"
        service.setTags(id, listOf("vip")) shouldContainExactly listOf("vip")
        row(id)["status"] shouldBe "SPAM"
        tags(id) shouldContainExactly listOf("vip")
        shouldThrow<IllegalArgumentException> { service.setTags(id, (1..21).map { "t$it" }) }
        shouldThrow<IllegalArgumentException> { service.setTags(id, listOf("x".repeat(41))) }
        service.setTags(404, listOf("vip")) shouldBe null
    }

    @Test
    fun `merge moves sessions, visitors and tags, keeps the target's non-empty fields, deletes the source`() {
        val target = contact(name = "Ana", note = "prva")
        val source = contact(name = "Ana H", email = "ana@x.com", phone = "+385 91", status = "OFFER_SENT", note = "druga")
        service.setTags(target, listOf("vip"))
        service.setTags(source, listOf("vip", "greece"))
        val session =
            jdbc.queryForObject(
                "INSERT INTO ai_chat_session (token, contact_id) VALUES (?, ?) RETURNING id",
                Long::class.java,
                UUID.randomUUID().toString(),
                source,
            )!!
        val (visitorId, _) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", Instant.now())
        jdbc.update("UPDATE cc_visitor SET contact_id = ? WHERE id = ?", source, visitorId)
        service.merge(target, source) shouldBe true
        jdbc.queryForObject("SELECT contact_id FROM ai_chat_session WHERE id = ?", Long::class.java, session) shouldBe target
        jdbc.queryForObject("SELECT contact_id FROM cc_visitor WHERE id = ?", Long::class.java, visitorId) shouldBe target
        tags(target) shouldContainExactly listOf("greece", "vip")
        row(target).let {
            it["name"] shouldBe "Ana"
            it["email"] shouldBe "ana@x.com"
            it["email_verified"] shouldBe true
            it["phone"] shouldBe "+385 91"
            it["status"] shouldBe "OFFER_SENT"
            it["note"] shouldBe "prva\n\ndruga"
        }
        jdbc.queryForObject("SELECT count(*) FROM cc_contact WHERE id = ?", Long::class.java, source) shouldBe 0
    }

    @Test
    fun `a spam tag that comes with the source marks the target SPAM`() {
        val target = contact(name = "Ana", status = "REPLIED")
        val source = contact(name = "Bot")
        service.setTags(source, listOf("spam"))
        service.merge(target, source) shouldBe true
        row(target)["status"] shouldBe "SPAM"
    }

    @Test
    fun `merging a contact into itself or a missing one is refused`() {
        val id = contact(name = "Ana")
        service.merge(id, id) shouldBe false
        service.merge(id, 404) shouldBe false
        jdbc.queryForObject("SELECT count(*) FROM cc_contact", Long::class.java) shouldBe 1
    }
}
