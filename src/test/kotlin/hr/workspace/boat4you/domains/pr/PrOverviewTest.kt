package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.PrPitchDraftTest.TestClock
import hr.workspace.boat4you.domains.pr.dto.PrOverviewDto
import hr.workspace.boat4you.domains.pr.dto.StudyKpi
import hr.workspace.boat4you.domains.pr.service.PrOverviewService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** The "Pregled" KPIs against the real V9_121 schema; rows are written straight into the tables, "now" is a TestClock. */
@Import(PrOverviewService::class, PrOverviewTest.Beans::class)
class PrOverviewTest : PrJpaTest() {
    @TestConfiguration
    class Beans {
        @Bean
        fun clock() = TestClock(Instant.parse("2026-11-05T10:00:00Z"))
    }

    @Autowired private lateinit var service: PrOverviewService

    @Autowired private lateinit var clock: TestClock

    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private var contactSeq = 0

    @BeforeEach
    fun resetClock() {
        clock.now = now
    }

    private fun daysAgo(days: Long) = now - Duration.ofDays(days)

    private fun study(
        number: Int,
        title: String,
    ): Long = jdbc.queryForObject("INSERT INTO pr_study (number, site_id, title) VALUES (?, 'boat4you', ?) RETURNING id", Long::class.java, number, title)!!

    private fun contact(): Long =
        jdbc.queryForObject(
            "INSERT INTO pr_contact (email, market) VALUES (?, 'GB') RETURNING id",
            Long::class.java,
            "writer${++contactSeq}@example.co.uk",
        )!!

    private fun pitch(
        studyId: Long?,
        status: String,
        sentAt: Instant?,
        parentId: Long? = null,
        contactId: Long = contact(),
    ): Long =
        jdbc.queryForObject(
            """
            INSERT INTO pr_pitch (study_id, contact_id, parent_pitch_id, template, subject, body, status, sent_at)
            VALUES (?, ?, ?, ?, 'Charter prices in Croatia rose 12%', 'Hi,', ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java,
            studyId,
            contactId,
            parentId,
            if (parentId == null) "DATA_PITCH" else "FOLLOW_UP",
            status,
            sentAt?.let { Timestamp.from(it) },
        )!!

    private fun followUp(
        parentId: Long,
        status: String,
        sentAt: Instant?,
    ): Long {
        val parent = jdbc.queryForMap("SELECT study_id, contact_id FROM pr_pitch WHERE id = ?", parentId)
        return pitch(parent["study_id"] as Long?, status, sentAt, parentId, parent["contact_id"] as Long)
    }

    private fun placement(
        studyId: Long?,
        linkState: String,
        alert: Boolean = false,
    ) = jdbc.update(
        "INSERT INTO pr_placement (study_id, article_url, link_state, alert) VALUES (?, ?, ?, ?)",
        studyId,
        "https://news.example/${linkState.lowercase()}-${System.nanoTime()}",
        linkState,
        alert,
    )

    private fun mention(
        status: String,
        linksToUs: Boolean?,
    ) = jdbc.update(
        "INSERT INTO pr_mention (url, source, links_to_us, status, found_at) VALUES (?, 'GOOGLE_ALERTS', ?, ?, now())",
        "https://blog.example/${status.lowercase()}-${System.nanoTime()}",
        linksToUs,
        status,
    )

    private fun followUpsDue() = service.overview().followUpsDue

    @Test
    fun overviewCountsMatchFixture() {
        // Inserted out of number order: the list is ordered by number, not by id.
        val second = study(2, "Greek island hopping costs")
        val first = study(1, "Charter prices in Croatia rose 12% this summer")
        val empty = study(3, "Caribbean catamaran fleet report")

        // Study 1: the three main pitches of the brief, plus a draft (not sent) and a follow-up (not a main pitch).
        pitch(first, "SENT", daysAgo(6)) // sent, no reply, no follow-up, 6 days old -> follow-up due
        val replied = pitch(first, "REPLIED", daysAgo(12))
        pitch(first, "PUBLISHED", daysAgo(10))
        pitch(first, "DRAFT", null)
        followUp(replied, "SENT", daysAgo(6))
        // Study 2: a declined pitch counts as a reply; a recent SENT is not due yet.
        pitch(second, "DECLINED", daysAgo(9))
        pitch(second, "SENT", daysAgo(2))

        placement(first, "OK_FOLLOW")
        placement(first, "OK_NOFOLLOW", alert = true)
        placement(second, "NOT_CHECKED")
        placement(null, "LINK_MISSING")

        mention("NEW", linksToUs = false) // the brief's mention without a link: new and unlinked
        mention("NEW", linksToUs = null) // page refused us: new, but not known to be unlinked
        mention("ASKED", linksToUs = false) // unlinked, already asked
        mention("IGNORED", linksToUs = false) // ignored: not counted as unlinked
        mention("LINKED", linksToUs = true)

        service.overview() shouldBe
            PrOverviewDto(
                perStudy =
                    listOf(
                        StudyKpi(first, 1, "Charter prices in Croatia rose 12% this summer", sent = 3, replied = 2, published = 1, placements = 2),
                        StudyKpi(second, 2, "Greek island hopping costs", sent = 2, replied = 1, published = 0, placements = 1),
                        StudyKpi(empty, 3, "Caribbean catamaran fleet report", sent = 0, replied = 0, published = 0, placements = 0),
                    ),
                liveFollow = 1,
                liveNofollow = 1,
                alerts = 1,
                mentionsNew = 2,
                mentionsUnlinked = 2,
                followUpsDue = 1,
            )
    }

    @Test
    fun emptyOverviewIsAllZero() {
        service.overview() shouldBe PrOverviewDto(emptyList(), 0, 0, 0, 0, 0, 0)
    }

    @Test
    fun followUpIsDueFromExactlyFiveDays() {
        val s = study(1, "Charter prices in Croatia rose 12% this summer")
        pitch(s, "SENT", daysAgo(5))
        pitch(s, "SENT", daysAgo(4))

        followUpsDue() shouldBe 1

        clock.now = now + Duration.ofDays(1)
        followUpsDue() shouldBe 2
    }

    @Test
    fun pitchThatAlreadyHasFollowUpIsNotDue() {
        val s = study(1, "Charter prices in Croatia rose 12% this summer")
        val withFollowUp = pitch(s, "SENT", daysAgo(8))
        pitch(s, "SENT", daysAgo(8))
        followUpsDue() shouldBe 2

        followUp(withFollowUp, "DRAFT", null) // even an unsent follow-up draft means the follow-up is taken care of
        followUpsDue() shouldBe 1
    }
}
