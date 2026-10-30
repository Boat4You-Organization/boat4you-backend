package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class StatsQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = StatsQuery(jdbc)
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val to = Instant.parse("2026-11-01T00:00:00Z")
    private val inside = Instant.parse("2026-10-15T10:00:00Z")

    private fun stats(siteId: String? = null) = query.stats(from, to, siteId)

    private fun contact(
        status: String = "NEW",
        email: String? = null,
    ): Long = jdbc.queryForObject("INSERT INTO cc_contact (status, email) VALUES (?, ?) RETURNING id", Long::class.java, status, email)!!

    private fun conv(
        roles: List<String> = listOf("USER", "ASSISTANT"),
        at: Instant = inside,
        site: String = "boat4you",
        flagged: Boolean = false,
        deleted: Boolean = false,
        contactId: Long? = null,
        takenOver: Boolean = false,
        missed: Boolean = false,
        firstResponseSeconds: Long? = null,
    ): Long {
        val id =
            jdbc.queryForObject(
                "INSERT INTO ai_chat_session (token, site_id, created_at, flagged, deleted_at, contact_id, taken_over_at, missed, first_response_at) " +
                    "VALUES (?, ?, ?, ?, CASE WHEN ? THEN now() END, ?, ?, ?, ?) RETURNING id",
                Long::class.java,
                UUID.randomUUID().toString(),
                site,
                Timestamp.from(at),
                flagged,
                deleted,
                contactId,
                if (takenOver) Timestamp.from(at) else null,
                missed,
                firstResponseSeconds?.let { Timestamp.from(at.plusSeconds(it)) },
            )!!
        roles.forEach { jdbc.update("INSERT INTO ai_chat_message (session_id, role, content) VALUES (?, ?, 'x')", id, it) }
        return id
    }

    private fun visit(
        key: String,
        at: Instant,
        site: String = "boat4you",
        bot: Boolean = false,
        channel: String = "DIRECT",
        fromSite: String? = null,
        country: String? = null,
    ): Long {
        val (visitorId, visitId) = ccVisit(jdbc, key, site, at)
        jdbc.update(
            "UPDATE cc_visit SET started_at = ?, is_bot = ?, channel = ?, from_site_id = ?, country_code = ? WHERE id = ?",
            Timestamp.from(at),
            bot,
            channel,
            fromSite,
            country,
            visitId,
        )
        return visitorId
    }

    private fun search(
        location: String? = "Split",
        persons: Int? = null,
        start: String? = null,
        estimated: Boolean = false,
        flagged: Boolean = false,
        sessionId: Long? = null,
        site: String = "boat4you",
    ) = jdbc.update(
        "INSERT INTO cc_search (source, site_id, chat_session_id, location, country_code, min_persons, start_date, estimated, flagged, created_at) " +
            "VALUES ('AI_CHAT', ?, ?, ?, 'HR', ?, CAST(? AS DATE), ?, ?, ?)",
        site,
        sessionId,
        location,
        persons,
        start,
        estimated,
        flagged,
        Timestamp.from(inside),
    )

    @Test
    fun `spam contacts, flagged and deleted conversations are not counted`() {
        conv()
        conv(flagged = true)
        conv(deleted = true)
        conv(contactId = contact("SPAM"))
        conv(roles = emptyList())
        conv(at = Instant.parse("2026-09-30T23:00:00Z"))
        stats().chat.started shouldBe 1
    }

    @Test
    fun `started by team is a conversation whose first message is ADMIN`() {
        conv(roles = listOf("ADMIN", "USER"))
        conv(roles = listOf("USER", "ADMIN"))
        stats().chat.startedByTeam shouldBe 1
    }

    @Test
    fun `ai only vs taken over`() {
        conv()
        conv(takenOver = true)
        conv(roles = listOf("USER", "ADMIN"))
        conv(missed = true)
        stats().chat.let {
            it.aiOnly shouldBe 2
            it.takenOver shouldBe 2
            it.missed shouldBe 1
        }
    }

    @Test
    fun `median first response and contacts collected`() {
        conv(firstResponseSeconds = 10)
        conv(firstResponseSeconds = 20)
        conv(firstResponseSeconds = 300)
        conv(contactId = contact(email = "a@x.com"))
        conv(contactId = contact())
        stats().chat.let {
            it.medianFirstResponseSeconds shouldBe 20
            it.contactsCollected shouldBe 1
        }
    }

    @Test
    fun `visits exclude bots and staff`() {
        val a = UUID.randomUUID().toString()
        visit(a, inside)
        visit(a, inside.plusSeconds(3600))
        visit(UUID.randomUUID().toString(), inside, bot = true)
        val staff = visit(UUID.randomUUID().toString(), inside)
        jdbc.update("UPDATE cc_visitor SET is_staff = TRUE WHERE id = ?", staff)
        stats().visitors.let {
            it.visits shouldBe 2
            it.visitors shouldBe 1
        }
    }

    @Test
    fun `new vs returning by the visitor's first visit inside or before the range`() {
        val old = UUID.randomUUID().toString()
        visit(old, Instant.parse("2026-09-01T10:00:00Z"))
        visit(old, inside)
        visit(UUID.randomUUID().toString(), inside)
        stats().visitors.let {
            it.newVisitors shouldBe 1
            it.returningVisitors shouldBe 1
        }
    }

    @Test
    fun `sister to boat4you counts boat4you visits with a from site, by from site`() {
        visit(UUID.randomUUID().toString(), inside, channel = "SISTER", fromSite = "greece")
        visit(UUID.randomUUID().toString(), inside, channel = "SISTER", fromSite = "greece")
        visit(UUID.randomUUID().toString(), inside, channel = "SISTER", fromSite = "croatia")
        visit(UUID.randomUUID().toString(), inside, site = "greece", channel = "SISTER", fromSite = "boat4you")
        stats().visitors.sisterToBoat4you shouldContainExactly listOf(Count("greece", 2), Count("croatia", 1))
        stats("greece").visitors.sisterToBoat4you shouldContainExactly listOf(Count("greece", 2))
    }

    @Test
    fun `searches exclude estimated and flagged rows and searches of excluded conversations`() {
        search()
        search(estimated = true)
        search(flagged = true)
        search(sessionId = conv(flagged = true))
        search(sessionId = conv(contactId = contact("SPAM")))
        stats().searches.total shouldBe 1
    }

    @Test
    fun `persons buckets, months and top locations`() {
        search(persons = 2, start = "2027-07-04")
        search(persons = 8, start = "2027-07-11", location = "Trogir")
        search(persons = 9, start = "2027-06-01", location = "Trogir")
        search(persons = 14)
        search(persons = null)
        stats().searches.let {
            it.personsBuckets shouldContainExactly listOf(Count("1-4", 1), Count("5-8", 1), Count("9-12", 1), Count("13+", 1))
            it.byMonth shouldContainExactly listOf(Count("2027-06", 1), Count("2027-07", 2))
            it.topLocations shouldContainExactly listOf(Count("Split", 3), Count("Trogir", 2))
            it.topCountries shouldContainExactly listOf(Count("HR", 5))
        }
    }

    @Test
    fun `site filter`() {
        conv(site = "greece")
        conv()
        visit(UUID.randomUUID().toString(), inside, site = "greece")
        search(site = "greece")
        stats("greece").let {
            it.chat.started shouldBe 1
            it.visitors.visits shouldBe 1
            it.visitors.bySite shouldContainExactly listOf(Count("greece", 1))
            it.searches.total shouldBe 1
        }
    }

    @Test
    fun `a range with no data gives zeros, not an error`() {
        stats().let {
            it.chat.started shouldBe 0
            it.chat.medianFirstResponseSeconds.shouldBeNull()
            it.visitors.visits shouldBe 0
            it.visitors.bySite.shouldBeEmpty()
            it.searches.total shouldBe 0
            it.searches.personsBuckets.map { b -> b.value } shouldContainExactly listOf(0L, 0L, 0L, 0L)
        }
    }
}
