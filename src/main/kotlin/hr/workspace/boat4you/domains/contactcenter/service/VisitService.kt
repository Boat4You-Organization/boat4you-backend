package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.service.GeoIpService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCallback
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Starts or resumes the visit a `/visit` call belongs to (design 2.2, 2.6). A visit resumes when the same visitor was seen on the same
 * site within [RESUME_WINDOW] and no handoff token is presented; otherwise the visitor's open visits on the site are closed and a new one
 * starts. A valid handoff token makes the visitor adopt the source site's visitor key and starts a SISTER visit. The visit then goes into
 * the [LiveVisitorRegistry], flagged as a bot or as staff where it is one.
 */
@Service
class VisitService(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val registry: LiveVisitorRegistry,
    private val handoffs: HandoffTokenStore,
    private val geoIp: GeoIpService,
    private val channels: ChannelClassifier,
    private val datacenter: DatacenterIpFilter,
) : ChatVisitLinker {
    internal var clock: Clock = Clock.systemUTC()

    private val tx = TransactionTemplate(transactionManager)

    /** [visitKey] and [visitorKey] are the public credentials; [visitorKey] differs from the one sent when a handoff token was adopted. */
    data class Started(
        val visitKey: String,
        val visitorKey: String,
        val resumed: Boolean,
    )

    /** What is worked out before the transaction opens: the request's clock reading and everything that needs no database. */
    private class Arrival(
        val now: Instant,
        val visitorKey: String,
        val handedOff: Boolean,
        val ua: UserAgentParser.ParsedUa,
        val botReason: String?,
        val countryCode: String?,
        val attribution: ChannelClassifier.Attribution,
    )

    /** The `cc_visit` columns the registry mirrors. */
    private class StoredVisit(
        val id: Long,
        val key: String,
        val startedAt: Instant,
        val channel: String,
        val fromSiteId: String?,
        val ip: String?,
        val countryCode: String?,
        val deviceClass: String?,
        val browser: String?,
        val os: String?,
        val isBot: Boolean,
    )

    private class Visitor(
        val id: Long,
        val staff: Boolean,
        val visitCount: Int,
    )

    private class Opened(
        val visit: StoredVisit,
        val visitor: Visitor,
        val returning: Boolean,
        val resumed: Boolean,
    )

    fun startOrResume(cmd: VisitCommand): Started {
        val now = clock.instant()
        val grant = handoffs.consume(cmd.handoffToken, cmd.siteId)
        val visitorKey = grant?.visitorKey ?: cmd.visitorKey
        val ua = UserAgentParser.parse(cmd.userAgent)
        val botReason = BotFilter.atVisit(cmd.userAgent, cmd.webdriver, cmd.viewportW, cmd.viewportH) ?: datacenter.reason(cmd.ip)
        val geo = geoIp.countryOf(cmd.ip.takeIf(TrackerClientIp::isIpLiteral))
        val attribution =
            channels.classify(cmd.siteId, cmd.referrer?.host, cmd.utm, cmd.gclid, cmd.fbclid, cmd.landingParamNames, grant?.fromSiteId, cmd.ccSrc)
        val arrival = Arrival(now, visitorKey, grant != null, ua, botReason, geo?.code, attribution)

        val opened = tx.execute { open(cmd, arrival) }!!

        val v = opened.visit
        registry.register(
            LiveEntry(
                visitId = v.id,
                visitKey = v.key,
                visitorId = opened.visitor.id,
                visitorKey = visitorKey,
                siteId = cmd.siteId,
                startedAt = v.startedAt,
                returning = opened.returning,
                countryCode = v.countryCode,
                ip = v.ip,
                locale = cmd.locale,
                deviceClass = v.deviceClass ?: DEFAULT_DEVICE,
                browser = v.browser,
                os = v.os,
                channel = v.channel,
                fromSiteId = v.fromSiteId,
                staff = opened.visitor.staff,
                bot = v.isBot || botReason != null,
                lastBeatAt = now,
            ),
        )
        if (!opened.resumed && botReason != null) registry.noteBot(cmd.siteId)
        return Started(v.key, visitorKey, opened.resumed)
    }

    /**
     * The live entry of the visit [visitKey], only if it belongs to [visitorKey] on [siteId]. A visit the registry no longer holds (evicted
     * after 60 s without a beat, or lost in a restart) is read back from the database while it is open and seen within [RESUME_WINDOW];
     * an ended visit never resolves.
     */
    fun resolveForBeat(
        visitKey: String,
        visitorKey: String,
        siteId: String,
    ): LiveEntry? {
        val live = registry.get(visitKey)
        if (live != null) return live.takeIf { it.visitorKey == visitorKey && it.siteId == siteId }
        val now = clock.instant()
        val stored =
            jdbc
                .query(BEAT_VISIT_SQL, RowMapper { rs, _ -> liveEntryOf(rs, now) }, visitKey, Timestamp.from(now.minus(RESUME_WINDOW)))
                .firstOrNull()
                ?.takeIf { it.visitorKey == visitorKey && it.siteId == siteId }
        if (stored != null) registry.register(stored) // a refusal (registry full of live people) is fine: the beat is still answered
        return stored?.let { registry.get(visitKey) ?: it } // a concurrent beat may have registered the visit first: share its entry
    }

    /** Points the chat session at the visitor and visit once. The caller owns the chat token and the visit (BACKEND-29). */
    override fun linkChatSession(
        chatSessionId: Long,
        siteId: String,
        visitorKey: String,
        visitKey: String,
    ): Boolean {
        val e = resolveForBeat(visitKey, visitorKey, siteId) ?: return false
        return jdbc.update(
            "UPDATE ai_chat_session SET visitor_id = ?, visit_id = ? WHERE id = ? AND site_id = ? AND visitor_id IS NULL AND deleted_at IS NULL",
            e.visitorId,
            e.visitId,
            chatSessionId,
            siteId,
        ) == 1
    }

    private fun open(
        cmd: VisitCommand,
        a: Arrival,
    ): Opened {
        // one visitor on one site at a time: a second tab waits here and then sees the first tab's visit
        jdbc.execute(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            PreparedStatementCallback { ps ->
                ps.setString(1, "cc:${a.visitorKey}:${cmd.siteId}")
                ps.execute()
            },
        )
        val visitor = upsertVisitor(cmd, a)
        val resumable = if (a.handedOff) null else findResumable(visitor.id, cmd.siteId, a.now)
        if (resumable != null) {
            jdbc.update(
                "UPDATE cc_visit SET last_seen_at = ?, is_bot = is_bot OR ?, bot_reason = COALESCE(bot_reason, ?) WHERE id = ?",
                arrayOf<Any?>(Timestamp.from(a.now), a.botReason != null, a.botReason, resumable.id),
                intArrayOf(Types.TIMESTAMP, Types.BOOLEAN, Types.VARCHAR, Types.BIGINT),
            )
            return Opened(resumable, visitor, returning = visitor.visitCount > 1, resumed = true)
        }
        jdbc.update("UPDATE cc_visit SET ended_at = last_seen_at WHERE visitor_id = ? AND site_id = ? AND ended_at IS NULL", visitor.id, cmd.siteId)
        val stored = insertVisit(cmd, visitor.id, a)
        val count =
            jdbc.queryForObject("UPDATE cc_visitor SET visit_count = visit_count + 1 WHERE id = ? RETURNING visit_count", Int::class.java, visitor.id)!!
        return Opened(stored, visitor, returning = count > 1, resumed = false)
    }

    /** Staff is sticky: once a visitor was seen with the staff cookie, every later visit of it is staff too. */
    private fun upsertVisitor(
        cmd: VisitCommand,
        a: Arrival,
    ): Visitor =
        jdbc.queryForObject(
            "INSERT INTO cc_visitor (visitor_key, first_site_id, is_staff, visit_count, created_at, last_seen_at) VALUES (?, ?, ?, 0, ?, ?) " +
                "ON CONFLICT (visitor_key) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at, is_staff = cc_visitor.is_staff OR EXCLUDED.is_staff " +
                "RETURNING id, is_staff, visit_count",
            RowMapper { rs, _ -> Visitor(rs.getLong("id"), rs.getBoolean("is_staff"), rs.getInt("visit_count")) },
            a.visitorKey,
            cmd.siteId,
            cmd.staff,
            Timestamp.from(a.now),
            Timestamp.from(a.now),
        )!!

    private fun findResumable(
        visitorId: Long,
        siteId: String,
        now: Instant,
    ): StoredVisit? =
        jdbc
            .query(
                "SELECT id, visit_key, started_at, channel, from_site_id, ip, country_code, locale, device_class, browser, os, is_bot FROM cc_visit " +
                    "WHERE visitor_id = ? AND site_id = ? AND ended_at IS NULL AND visit_key IS NOT NULL AND last_seen_at > ? " +
                    "ORDER BY last_seen_at DESC LIMIT 1",
                RowMapper { rs, _ -> storedVisitOf(rs) },
                visitorId,
                siteId,
                Timestamp.from(now.minus(RESUME_WINDOW)),
            ).firstOrNull()

    private fun insertVisit(
        cmd: VisitCommand,
        visitorId: Long,
        a: Arrival,
    ): StoredVisit {
        val visitKey = UUID.randomUUID().toString()
        val ip = cmd.ip.take(MAX_IP)
        val at = Timestamp.from(a.now)
        val id =
            jdbc.queryForObject(
                "INSERT INTO cc_visit (visitor_id, site_id, started_at, last_seen_at, landing_url, referrer, utm_source, utm_medium, utm_campaign, " +
                    "utm_term, utm_content, gclid, fbclid, channel, from_site_id, ip, country_code, locale, device_class, browser, os, user_agent, " +
                    "is_bot, bot_reason, page_count, visit_key) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?) RETURNING id",
                arrayOf<Any?>(
                    visitorId,
                    cmd.siteId,
                    at,
                    at,
                    cmd.landing?.url,
                    cmd.referrer?.url,
                    cmd.utm.source,
                    cmd.utm.medium,
                    cmd.utm.campaign,
                    cmd.utm.term,
                    cmd.utm.content,
                    cmd.gclid,
                    cmd.fbclid,
                    a.attribution.channel,
                    a.attribution.fromSiteId,
                    ip,
                    a.countryCode,
                    cmd.locale,
                    a.ua.deviceClass,
                    a.ua.browser,
                    a.ua.os,
                    cmd.userAgent,
                    a.botReason != null,
                    a.botReason,
                    visitKey,
                ),
                INSERT_VISIT_TYPES,
                Long::class.java,
            )!!
        return StoredVisit(
            id = id,
            key = visitKey,
            startedAt = a.now,
            channel = a.attribution.channel,
            fromSiteId = a.attribution.fromSiteId,
            ip = ip,
            countryCode = a.countryCode,
            deviceClass = a.ua.deviceClass,
            browser = a.ua.browser,
            os = a.ua.os,
            isBot = a.botReason != null,
        )
    }

    private fun storedVisitOf(rs: ResultSet) =
        StoredVisit(
            id = rs.getLong("id"),
            key = rs.getString("visit_key"),
            startedAt = rs.getTimestamp("started_at").toInstant(),
            channel = rs.getString("channel"),
            fromSiteId = rs.getString("from_site_id"),
            ip = rs.getString("ip"),
            countryCode = rs.getString("country_code"),
            deviceClass = rs.getString("device_class"),
            browser = rs.getString("browser"),
            os = rs.getString("os"),
            isBot = rs.getBoolean("is_bot"),
        )

    private fun liveEntryOf(
        rs: ResultSet,
        now: Instant,
    ) = LiveEntry(
        visitId = rs.getLong("id"),
        visitKey = rs.getString("visit_key"),
        visitorId = rs.getLong("visitor_id"),
        visitorKey = rs.getString("visitor_key"),
        siteId = rs.getString("site_id"),
        startedAt = rs.getTimestamp("started_at").toInstant(),
        returning = rs.getInt("visit_count") > 1,
        countryCode = rs.getString("country_code"),
        ip = rs.getString("ip"),
        locale = rs.getString("locale"),
        deviceClass = rs.getString("device_class") ?: DEFAULT_DEVICE,
        browser = rs.getString("browser"),
        os = rs.getString("os"),
        channel = rs.getString("channel"),
        fromSiteId = rs.getString("from_site_id"),
        staff = rs.getBoolean("is_staff"),
        bot = rs.getBoolean("is_bot"),
        lastBeatAt = now,
    )

    companion object {
        /** A visit resumes when its visitor was seen on the site within this window; the end sweep closes visits idle for longer. */
        val RESUME_WINDOW: Duration = Duration.ofMinutes(30)

        /** The widget's beat interval, and the shortest gap it may keep between two beats. */
        const val BEAT_SECONDS = 15
        const val MIN_BEAT_GAP_SECONDS = 5

        private const val MAX_IP = 45
        private const val DEFAULT_DEVICE = "DESKTOP"

        private const val BEAT_VISIT_SQL =
            "SELECT v.id, v.visit_key, v.visitor_id, vr.visitor_key, v.site_id, v.started_at, v.channel, v.from_site_id, v.ip, v.country_code, " +
                "v.locale, v.device_class, v.browser, v.os, v.is_bot, vr.is_staff, vr.visit_count " +
                "FROM cc_visit v JOIN cc_visitor vr ON vr.id = v.visitor_id " +
                "WHERE v.visit_key = ? AND v.ended_at IS NULL AND v.last_seen_at > ?"

        /** One type per bind of the INSERT in [insertVisit], in order: a null bind needs its type. */
        private val INSERT_VISIT_TYPES =
            intArrayOf(
                Types.BIGINT,
                Types.VARCHAR,
                Types.TIMESTAMP,
                Types.TIMESTAMP,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.CHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.VARCHAR,
                Types.BOOLEAN,
                Types.VARCHAR,
                Types.VARCHAR,
            )
    }
}
