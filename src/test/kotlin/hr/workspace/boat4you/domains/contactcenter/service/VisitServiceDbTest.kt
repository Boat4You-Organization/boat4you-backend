package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.chat.service.GeoIpService
import hr.workspace.boat4you.domains.contactcenter.CHROME_UA
import hr.workspace.boat4you.domains.contactcenter.CcTestClock
import hr.workspace.boat4you.domains.contactcenter.ContactCenterTestDb
import hr.workspace.boat4you.domains.contactcenter.ccTx
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class VisitServiceDbTest {
    // a new database, clock, registry and store per test method (JUnit's default lifecycle): no reset between tests
    private val jdbc = ContactCenterTestDb.ready()
    private val clock = CcTestClock()
    private val registry = LiveVisitorRegistry().also { it.clock = clock }
    private val handoffs = HandoffTokenStore().also { it.clock = clock }
    private val urls = TrackerUrlPolicy(BrandRegistry(), "")
    private val dc = DatacenterIpFilter("/nonexistent.mmdb")
    private val service by lazy {
        VisitService(jdbc, ccTx(jdbc), registry, handoffs, GeoIpService("/nonexistent.mmdb"), ChannelClassifier(urls), dc).also { it.clock = clock }
    }
    private val a = "a0000000-0000-4000-8000-00000000000a"
    private val b = "b0000000-0000-4000-8000-00000000000b"

    private fun cmd(
        key: String = a,
        site: String = "boat4you",
        ua: String? = CHROME_UA,
        staff: Boolean = false,
        handoff: String? = null,
    ) = VisitCommand(site, key, "203.0.113.7", ua, referrer = urls.referrer("https://www.google.hr/"), staff = staff, handoffToken = handoff)

    private fun visitRow(visitKey: String) = jdbc.queryForMap("SELECT * FROM cc_visit WHERE visit_key = ?", visitKey)

    @Test
    fun `new, resumed within 30 min, new after 30 min`() {
        val first = service.startOrResume(cmd())
        first.resumed shouldBe false
        first.visitorKey shouldBe a
        visitRow(first.visitKey).let {
            it["channel"] shouldBe "ORGANIC"
            it["device_class"] shouldBe "DESKTOP"
            it["browser"] shouldBe "Chrome 131"
            it["ip"] shouldBe "203.0.113.7"
            it["is_bot"] shouldBe false
        }
        registry.get(first.visitKey)!!.returning shouldBe false
        clock.advance(Duration.ofMinutes(29))
        service.startOrResume(cmd()).let {
            it.resumed shouldBe true
            it.visitKey shouldBe first.visitKey
        }
        service.startOrResume(cmd(site = "catamaran-greece")).visitKey shouldNotBe first.visitKey
        clock.advance(Duration.ofMinutes(31))
        val third = service.startOrResume(cmd())
        third.visitKey shouldNotBe first.visitKey
        (visitRow(first.visitKey)["ended_at"] as Timestamp).toInstant() shouldBe Instant.parse("2026-10-26T10:29:00Z")
        jdbc.queryForObject("SELECT visit_count FROM cc_visitor WHERE visitor_key = ?", Int::class.java, a) shouldBe 3
        registry.get(third.visitKey)!!.returning shouldBe true
    }

    @Test
    fun `handoff adopts the source visitor and starts a SISTER visit`() {
        val open = service.startOrResume(cmd(key = a)) // a already has an open boat4you visit
        val token = handoffs.issue(a, "catamaran-greece", "boat4you")!!
        val s = service.startOrResume(cmd(key = b, handoff = token))
        s.visitorKey shouldBe a
        s.visitKey shouldNotBe open.visitKey
        visitRow(s.visitKey).let {
            it["channel"] shouldBe "SISTER"
            it["from_site_id"] shouldBe "catamaran-greece"
        }
        jdbc.queryForObject("SELECT count(*) FROM cc_visitor WHERE visitor_key = ?", Long::class.java, b) shouldBe 0L
    }

    @Test
    fun `staff is sticky, bots are flagged, beats only resolve their own visit`() {
        service.startOrResume(cmd(staff = true))
        val s = service.startOrResume(cmd(staff = false))
        jdbc.queryForObject("SELECT is_staff FROM cc_visitor WHERE visitor_key = ?", Boolean::class.java, a) shouldBe true
        registry.get(s.visitKey)!!.staff shouldBe true
        val bot = service.startOrResume(cmd(key = b, ua = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"))
        visitRow(bot.visitKey).let {
            it["is_bot"] shouldBe true
            it["bot_reason"] shouldBe "ua:googlebot"
        }
        dc.asnOf = { if (it == "198.51.100.20") 16509L else null }
        val cloud = service.startOrResume(cmd(key = "c0000000-0000-4000-8000-00000000000c").copy(ip = "198.51.100.20"))
        visitRow(cloud.visitKey)["bot_reason"] shouldBe "dc_ip"
        registry.drainBotCounts() shouldBe mapOf("boat4you" to 2)
        service.resolveForBeat(s.visitKey, b, "boat4you") shouldBe null
        service.resolveForBeat(s.visitKey, a, "catamaran-greece") shouldBe null
        registry.remove(s.visitKey)
        service.resolveForBeat(s.visitKey, a, "boat4you")!!.visitorKey shouldBe a // DB fallback re-registers
        registry.get(s.visitKey) shouldNotBe null
    }

    @Test
    fun `the sweeper closes visits idle for more than 30 minutes`() {
        val s = service.startOrResume(cmd())
        val sweeper = CcVisitEndSweeper(jdbc).also { it.clock = clock }
        sweeper.sweepOnce() shouldBe 0
        clock.advance(Duration.ofMinutes(31))
        sweeper.sweepOnce() shouldBe 1
        registry.remove(s.visitKey)
        service.resolveForBeat(s.visitKey, a, "boat4you") shouldBe null // ended visits never resolve from the DB
    }

    @Test
    fun `a chat session is linked once, only to a live visit of the same visitor and site`() {
        val s = service.startOrResume(cmd())
        val chat =
            jdbc.queryForObject(
                "INSERT INTO ai_chat_session (token, site_id) VALUES (?, 'boat4you') RETURNING id",
                Long::class.java,
                UUID.randomUUID().toString(),
            )!!
        service.linkChatSession(chat, "boat4you", b, s.visitKey) shouldBe false // not this visitor's visit
        service.linkChatSession(chat, "catamaran-greece", a, s.visitKey) shouldBe false // another site
        service.linkChatSession(chat, "boat4you", a, s.visitKey) shouldBe true
        service.linkChatSession(chat, "boat4you", a, s.visitKey) shouldBe false // already linked, never re-pointed
        val e = registry.get(s.visitKey)!!
        jdbc.queryForMap("SELECT visitor_id, visit_id FROM ai_chat_session WHERE id = ?", chat) shouldBe
            mapOf("visitor_id" to e.visitorId, "visit_id" to e.visitId)
    }

    // Tests below go beyond the brief's five: they pin mandated behaviour that the five reach only trivially or not at all.

    @Test
    fun `staff comes from the stored visitor whenever the registry entry is rebuilt`() { // ledger: Task 8 Ruling, handoff to Task 10
        val first = service.startOrResume(cmd(staff = true))
        registry.remove(first.visitKey)
        val again = service.startOrResume(cmd(staff = false)) // resumed from the database, no staff cookie on this call
        again.visitKey shouldBe first.visitKey
        registry.get(again.visitKey)!!.staff shouldBe true
        registry.remove(again.visitKey)
        service.resolveForBeat(again.visitKey, a, "boat4you")!!.staff shouldBe true
    }

    @Test
    fun `a resumed visit gains a bot flag and keeps the first reason, without counting a new bot visit`() {
        val first = service.startOrResume(cmd())
        clock.advance(Duration.ofMinutes(1))
        service.startOrResume(cmd().copy(webdriver = true)).visitKey shouldBe first.visitKey
        visitRow(first.visitKey).let {
            it["is_bot"] shouldBe true
            it["bot_reason"] shouldBe "webdriver"
            (it["last_seen_at"] as Timestamp).toInstant() shouldBe Instant.parse("2026-10-26T10:01:00Z")
        }
        registry.get(first.visitKey)!!.bot shouldBe true
        service.startOrResume(cmd(ua = null)).visitKey shouldBe first.visitKey // empty_ua is a later reason: the first one stays
        visitRow(first.visitKey)["bot_reason"] shouldBe "webdriver"
        registry.drainBotCounts() shouldBe emptyMap()
    }

    @Test
    fun `every stored value lands in its own column and the ip is clipped to 45`() {
        val landing = CleanUrl("https://www.boat4you.com/boat/x-1?startDate=2027-07-17", "/boat/x-1?startDate=2027-07-17", mapOf("startDate" to "2027-07-17"))
        val s =
            service.startOrResume(
                cmd().copy(
                    landing = landing,
                    utm = Utm("src", "cpc", "camp", "term", "content"),
                    gclid = "gc1",
                    fbclid = "fb1",
                    locale = "hr-HR",
                    viewportW = 1280,
                    viewportH = 800,
                ),
            )
        visitRow(s.visitKey).let {
            it["visitor_id"] shouldBe registry.get(s.visitKey)!!.visitorId
            it["site_id"] shouldBe "boat4you"
            it["landing_url"] shouldBe landing.url
            it["referrer"] shouldBe urls.referrer("https://www.google.hr/")!!.url
            it["utm_source"] shouldBe "src"
            it["utm_medium"] shouldBe "cpc"
            it["utm_campaign"] shouldBe "camp"
            it["utm_term"] shouldBe "term"
            it["utm_content"] shouldBe "content"
            it["gclid"] shouldBe "gc1"
            it["fbclid"] shouldBe "fb1"
            it["channel"] shouldBe "ADS"
            it["from_site_id"] shouldBe null
            it["country_code"] shouldBe null
            it["locale"] shouldBe "hr-HR"
            it["device_class"] shouldBe "DESKTOP"
            it["os"] shouldBe "Windows"
            it["user_agent"] shouldBe CHROME_UA
            it["is_bot"] shouldBe false
            it["bot_reason"] shouldBe null
            it["page_count"] shouldBe 0
            it["ended_at"] shouldBe null
            (it["started_at"] as Timestamp).toInstant() shouldBe clock.now
            (it["last_seen_at"] as Timestamp).toInstant() shouldBe clock.now
        }
        registry.get(s.visitKey)!!.locale shouldBe "hr-HR"
        val long = service.startOrResume(cmd(key = b).copy(ip = "9".repeat(50)))
        (visitRow(long.visitKey)["ip"] as String).length shouldBe 45
    }

    @Test
    fun `the per-visitor lock is taken first, so a second tab waits for the first`() { // pool of 2: this test holds one connection, the call the other
        val holder = jdbc.dataSource!!.connection
        val executor = Executors.newSingleThreadExecutor()
        try {
            holder.autoCommit = false
            holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                it.setString(1, "cc:$a:boat4you")
                it.execute()
            }
            val call = executor.submit<VisitService.Started> { service.startOrResume(cmd()) }
            shouldThrow<TimeoutException> { call.get(500, TimeUnit.MILLISECONDS) }
            holder.commit() // the other tab is done: the lock is free
            call.get(5, TimeUnit.SECONDS).resumed shouldBe false
        } finally {
            holder.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `an idle visit that nobody swept yet does not resolve from the database after 30 minutes`() {
        val s = service.startOrResume(cmd())
        registry.remove(s.visitKey)
        clock.advance(Duration.ofMinutes(29))
        service.resolveForBeat(s.visitKey, a, "boat4you") shouldNotBe null
        registry.remove(s.visitKey)
        clock.advance(Duration.ofMinutes(2)) // 31 minutes after the last stored sighting, and no sweep ran
        service.resolveForBeat(s.visitKey, a, "boat4you") shouldBe null
    }
}
