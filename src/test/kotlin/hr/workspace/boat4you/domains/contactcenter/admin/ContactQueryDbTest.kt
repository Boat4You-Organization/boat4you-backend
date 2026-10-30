package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class ContactQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = ContactQuery(jdbc, ConversationQuery(jdbc))
    private val base = Instant.parse("2026-10-30T08:00:00Z")

    private fun contact(
        name: String? = null,
        email: String? = null,
        phone: String? = null,
        status: String = "NEW",
        tags: List<String> = emptyList(),
    ): Long {
        val id =
            jdbc.queryForObject(
                "INSERT INTO cc_contact (name, email, phone, status) VALUES (?, ?, ?, ?) RETURNING id",
                Long::class.java,
                name,
                email,
                phone,
                status,
            )!!
        tags.forEach { jdbc.update("INSERT INTO cc_contact_tag (contact_id, tag) VALUES (?, ?)", id, it) }
        return id
    }

    private fun conv(
        contactId: Long,
        minutes: Long,
        visitorId: Long? = null,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO ai_chat_session (token, contact_id, visitor_id, last_activity_at) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java,
            UUID.randomUUID().toString(),
            contactId,
            visitorId,
            Timestamp.from(base.plusSeconds(minutes * 60)),
        )!!

    private fun visitAt(
        visitorKey: String,
        site: String,
        minutes: Long,
        channel: String,
        fromSite: String? = null,
    ): Pair<Long, Long> {
        val ids = ccVisit(jdbc, visitorKey, site, base.plusSeconds(minutes * 60))
        jdbc.update(
            "UPDATE cc_visit SET started_at = ?, channel = ?, from_site_id = ? WHERE id = ?",
            Timestamp.from(base.plusSeconds(minutes * 60)),
            channel,
            fromSite,
            ids.second,
        )
        return ids
    }

    @Test
    fun `search name, email and phone with escaped LIKE`() {
        val ana = contact(name = "Ana Horvat")
        val mail = contact(email = "x_y@mail.com")
        val tel = contact(phone = "+385 91 123")
        contact(email = "xzy@mail.com")
        query.list("horvat", null, null, 0, 50).map { it.id } shouldContainExactly listOf(ana)
        query.list("x_y", null, null, 0, 50).map { it.id } shouldContainExactly listOf(mail)
        query.list("91 1", null, null, 0, 50).map { it.id } shouldContainExactly listOf(tel)
        query.list("%", null, null, 0, 50) shouldHaveSize 0
    }

    @Test
    fun `status and tag filters, newest activity first, paging`() {
        val won = contact(name = "A", status = "WON")
        val vip = contact(name = "B", tags = listOf("vip"))
        val other = contact(name = "C")
        conv(won, 10)
        conv(vip, 20)
        conv(vip, 5)
        query.list(null, "WON", null, 0, 50).map { it.id } shouldContainExactly listOf(won)
        query.list(null, null, "vip", 0, 50).single().let {
            it.id shouldBe vip
            it.conversations shouldBe 2
            it.tags shouldContainExactly listOf("vip")
            it.lastActivityAt shouldBe base.plusSeconds(1200)
        }
        val all = query.list(null, null, null, 0, 50).map { it.id }
        all.take(2) shouldContainExactly listOf(other, vip) // created now, after the 2026-10-30 08:20 activity
        query.list(null, null, null, 1, 2).map { it.id } shouldContainExactly listOf(won)
    }

    @Test
    fun `first and last source are the first and last visit of the contact's visitors`() {
        val id = contact(name = "Ana")
        val key = UUID.randomUUID().toString()
        val (visitorId, _) = visitAt(key, "greece", 1, "ADS")
        visitAt(key, "boat4you", 30, "SISTER", fromSite = "greece")
        visitAt(key, "boat4you", 10, "ORGANIC")
        conv(id, 40, visitorId)
        val card = query.card(id).shouldNotBeNull()
        card.firstSource.shouldNotBeNull().let {
            it.channel shouldBe "ADS"
            it.siteId shouldBe "greece"
        }
        card.lastSource.shouldNotBeNull().let {
            it.channel shouldBe "SISTER"
            it.fromSiteId shouldBe "greece"
        }
        card.visits.map { it.channel } shouldContainExactly listOf("SISTER", "ORGANIC", "ADS")
    }

    @Test
    fun `visits and conversations of the contact, conversations newest first`() {
        val id = contact(name = "Ana")
        val key = UUID.randomUUID().toString()
        val (visitorId, _) = ccVisit(jdbc, key, "boat4you", base)
        jdbc.update("UPDATE cc_visitor SET contact_id = ? WHERE id = ?", id, visitorId)
        val old = conv(id, 1)
        val new = conv(id, 9)
        conv(contact(name = "Other"), 20)
        val card = query.card(id).shouldNotBeNull()
        card.conversations.map { it.id } shouldContainExactly listOf(new, old)
        card.visits shouldHaveSize 1
        card.contact.conversations shouldBe 2
    }

    @Test
    fun `boats viewed are distinct boat slugs of BOAT page views, newest first, max 30`() {
        val id = contact(name = "Ana")
        val (visitorId, visitId) = ccVisit(jdbc, UUID.randomUUID().toString(), "boat4you", base)
        jdbc.update("UPDATE cc_visitor SET contact_id = ? WHERE id = ?", id, visitorId)

        fun view(
            slug: String,
            seconds: Long,
            type: String = "BOAT",
        ) = jdbc.update(
            "INSERT INTO cc_page_view (visit_id, path, page_type, boat_slug, viewed_at) VALUES (?, '/x', ?, ?, ?)",
            visitId,
            type,
            slug,
            Timestamp.from(base.plusSeconds(seconds)),
        )
        view("lagoon-40", 1)
        view("bali-45", 2)
        view("lagoon-40", 3)
        view("not-a-boat", 4, type = "SEARCH")
        (1..40).forEach { view("boat-$it", 100L + it) }
        val boats = query.card(id).shouldNotBeNull().boatsViewed
        boats shouldHaveSize 30
        boats.first() shouldBe "boat-40"
        query.card(id)!!.boatsViewed.contains("not-a-boat") shouldBe false
    }

    @Test
    fun `possible duplicates share email or phone, case-insensitive, and are never the contact itself`() {
        val id = contact(name = "Ana", email = "Ana@Mail.com", phone = "+385 91 111 222")
        val sameMail = contact(email = "ana@mail.com")
        val samePhone = contact(phone = "+38591111222")
        contact(email = "other@mail.com", phone = "+385 98 000 000")
        query.card(id).shouldNotBeNull().possibleDuplicates.map { it.id } shouldContainExactly listOf(sameMail, samePhone)
        query.card(contact(name = "Bez podataka")).shouldNotBeNull().possibleDuplicates shouldHaveSize 0
    }

    @Test
    fun `a missing contact has no card`() {
        query.card(404).shouldBeNull()
    }
}
