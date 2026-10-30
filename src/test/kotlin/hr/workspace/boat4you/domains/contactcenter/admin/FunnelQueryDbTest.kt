package hr.workspace.boat4you.domains.contactcenter.admin

import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccVisit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class FunnelQueryDbTest {
    private val jdbc = ContactCenterTestDb.ready()
    private val query = FunnelQuery(jdbc)
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val to = Instant.parse("2026-11-01T00:00:00Z")
    private val inside = Instant.parse("2026-10-15T10:00:00Z")

    private fun funnel(
        siteId: String? = null,
        device: String? = null,
        channel: String? = null,
    ) = query.funnel(from, to, siteId, device, channel)

    private fun visit(
        at: Instant = inside,
        site: String = "boat4you",
        bot: Boolean = false,
        staff: Boolean = false,
        device: String = "DESKTOP",
        channel: String = "DIRECT",
    ): Long {
        val (visitorId, visitId) = ccVisit(jdbc, UUID.randomUUID().toString(), site, at)
        jdbc.update(
            "UPDATE cc_visit SET started_at = ?, is_bot = ?, device_class = ?, channel = ? WHERE id = ?",
            Timestamp.from(at),
            bot,
            device,
            channel,
            visitId,
        )
        if (staff) jdbc.update("UPDATE cc_visitor SET is_staff = TRUE WHERE id = ?", visitorId)
        return visitId
    }

    private fun page(
        visitId: Long,
        type: String,
        path: String = "/en/${type.lowercase()}",
        at: Instant = inside,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO cc_page_view (visit_id, path, page_type, viewed_at) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java,
            visitId,
            path,
            type,
            Timestamp.from(at),
        )!!

    private fun event(
        visitId: Long,
        type: String,
        pageViewId: Long? = null,
        label: String? = null,
        target: String? = null,
    ) = jdbc.update(
        "INSERT INTO cc_event (visit_id, page_view_id, type, label, target) VALUES (?, ?, ?, ?, ?)",
        visitId,
        pageViewId,
        type,
        label,
        target,
    )

    @Test
    fun `funnel counts each visit once per step in order`() {
        visit().let {
            page(it, "HOME")
            page(it, "BOAT", "/en/a")
            page(it, "BOAT", "/en/b")
        }
        visit().let { page(it, "HOME") }
        visit().let {
            page(it, "BOAT")
            page(it, "BOOKING")
            event(it, "INQUIRY")
            event(it, "INQUIRY")
            event(it, "CHAT_OPEN")
            event(it, "CHAT_OPEN")
        }
        // a sister inquiry is a modal: no form page, and a contact form needs no boat page
        visit().let {
            page(it, "HOME")
            event(it, "INQUIRY")
        }
        funnel().let {
            it.steps shouldContainExactly listOf(Count("VISIT", 4), Count("BOAT", 3), Count("FORM", 2), Count("INQUIRY", 2))
            it.chatOpened shouldBe 1
        }
    }

    @Test
    fun `bots, staff and visits outside the range are not counted`() {
        visit().let { page(it, "HOME") }
        visit(bot = true).let {
            page(it, "BOAT")
            event(it, "INQUIRY")
        }
        visit(staff = true).let { event(it, "INQUIRY") }
        visit(at = Instant.parse("2026-09-30T23:00:00Z")).let { event(it, "INQUIRY") }
        funnel().steps shouldContainExactly listOf(Count("VISIT", 1), Count("BOAT", 0), Count("FORM", 0), Count("INQUIRY", 0))
    }

    @Test
    fun `exit page is the last page view of the visit`() {
        visit().let {
            page(it, "HOME", "/", inside)
            page(it, "BOAT", "/en/a", inside.plusSeconds(60))
        }
        visit().let {
            page(it, "BOAT", "/en/a", inside)
            page(it, "HOME", "/", inside.plusSeconds(30))
        }
        visit().let {
            page(it, "BOAT", "/en/a", inside)
            page(it, "HOME", "/", inside.plusSeconds(30))
        }
        // sent an inquiry: not an exit, but its page views still count as views
        visit().let {
            page(it, "BOAT", "/en/a", inside)
            event(it, "INQUIRY")
        }
        funnel().let {
            it.exitsByType shouldContainExactly listOf(Count("HOME", 2), Count("BOAT", 1))
            it.exits shouldContainExactly
                listOf(ExitPage("boat4you", "/", "HOME", views = 3, exits = 2), ExitPage("boat4you", "/en/a", "BOAT", views = 4, exits = 1))
        }
    }

    @Test
    fun `the same path on two sites is two exit pages`() {
        visit(site = "catamaran-greece").let { page(it, "HOME", "/") }
        visit(site = "catamaran-greece").let { page(it, "HOME", "/") }
        visit().let { page(it, "HOME", "/") }
        funnel().exits shouldContainExactly
            listOf(ExitPage("catamaran-greece", "/", "HOME", views = 2, exits = 2), ExitPage("boat4you", "/", "HOME", views = 1, exits = 1))
    }

    @Test
    fun `top clicks by page type and label`() {
        val v = visit()
        val boat = page(v, "BOAT")
        val search = page(v, "SEARCH")
        event(v, "CLICK", boat, label = "Book now")
        event(v, "CLICK", boat, label = "Book now")
        event(v, "CLICK", search, target = "/en/search?country=hr")
        event(v, "NUDGE_CLICK", boat, label = "Book now")
        event(v, "CLICK", null, label = "Close")
        funnel().clicks shouldContainExactly
            listOf(TopClick("BOAT", "Book now", 2), TopClick("OTHER", "Close", 1), TopClick("SEARCH", "/en/search?country=hr", 1))
    }

    @Test
    fun `site, device and channel filters`() {
        visit(site = "greece", device = "MOBILE", channel = "ADS").let { event(it, "INQUIRY") }
        visit(site = "greece", device = "DESKTOP", channel = "ADS")
        visit(device = "MOBILE", channel = "ORGANIC")
        funnel(siteId = "greece").steps.first() shouldBe Count("VISIT", 2)
        funnel(device = "MOBILE").steps.first() shouldBe Count("VISIT", 2)
        funnel(siteId = "greece", device = "MOBILE", channel = "ADS").steps shouldContainExactly
            listOf(Count("VISIT", 1), Count("BOAT", 1), Count("FORM", 1), Count("INQUIRY", 1))
        funnel(channel = "SOCIAL").steps.first() shouldBe Count("VISIT", 0)
    }

    @Test
    fun `a range with no data gives zero steps and empty lists`() {
        funnel().let {
            it.steps.map { s -> s.value } shouldContainExactly listOf(0L, 0L, 0L, 0L)
            it.chatOpened shouldBe 0
            it.exits.shouldBeEmpty()
            it.exitsByType.shouldBeEmpty()
            it.clicks.shouldBeEmpty()
        }
    }
}
