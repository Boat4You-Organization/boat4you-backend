package hr.workspace.boat4you.domains.pr.service

import hr.workspace.boat4you.domains.pr.dto.PrOverviewDto
import hr.workspace.boat4you.domains.pr.dto.StudyKpi
import hr.workspace.boat4you.domains.pr.rules.PrComplianceRules
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock

/**
 * The "Pregled" KPIs (PrOverviewDto has the definitions), counted in the database with two aggregate queries: one row
 * per study, one row of totals. Only main pitches (parent_pitch_id IS NULL) count; a follow-up is due by the same
 * 5-day rule PrComplianceRules applies when sending one, measured from the injected Clock.
 */
@Service
class PrOverviewService(
    private val jdbc: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun overview(): PrOverviewDto {
        val perStudy =
            jdbc.query(PER_STUDY) { rs, _ ->
                StudyKpi(
                    studyId = rs.getLong("id"),
                    number = rs.getInt("number"),
                    title = rs.getString("title"),
                    sent = rs.getInt("sent"),
                    replied = rs.getInt("replied"),
                    published = rs.getInt("published"),
                    placements = rs.getInt("placements"),
                )
            }
        val dueBefore = Timestamp.from(clock.instant() - PrComplianceRules.FOLLOW_UP_DELAY)
        return jdbc.queryForObject(TOTALS, { rs, _ ->
            PrOverviewDto(
                perStudy = perStudy,
                liveFollow = rs.getInt("live_follow"),
                liveNofollow = rs.getInt("live_nofollow"),
                alerts = rs.getInt("alerts"),
                mentionsNew = rs.getInt("mentions_new"),
                mentionsUnlinked = rs.getInt("mentions_unlinked"),
                followUpsDue = rs.getInt("follow_ups_due"),
            )
        }, dueBefore)!!
    }

    private companion object {
        val PER_STUDY =
            """
            SELECT s.id, s.number, s.title,
                   coalesce(p.sent, 0) AS sent, coalesce(p.replied, 0) AS replied, coalesce(p.published, 0) AS published,
                   coalesce(pl.placements, 0) AS placements
            FROM pr_study s
            LEFT JOIN (
                SELECT study_id,
                       count(*) FILTER (WHERE sent_at IS NOT NULL) AS sent,
                       count(*) FILTER (WHERE status IN ('REPLIED', 'DECLINED', 'PUBLISHED')) AS replied,
                       count(*) FILTER (WHERE status = 'PUBLISHED') AS published
                FROM pr_pitch
                WHERE parent_pitch_id IS NULL AND study_id IS NOT NULL
                GROUP BY study_id
            ) p ON p.study_id = s.id
            LEFT JOIN (
                SELECT study_id, count(*) AS placements FROM pr_placement WHERE study_id IS NOT NULL GROUP BY study_id
            ) pl ON pl.study_id = s.id
            ORDER BY s.number
            """.trimIndent()

        val TOTALS =
            """
            SELECT
                (SELECT count(*) FROM pr_placement WHERE link_state = 'OK_FOLLOW') AS live_follow,
                (SELECT count(*) FROM pr_placement WHERE link_state = 'OK_NOFOLLOW') AS live_nofollow,
                (SELECT count(*) FROM pr_placement WHERE alert) AS alerts,
                (SELECT count(*) FROM pr_mention WHERE status = 'NEW') AS mentions_new,
                (SELECT count(*) FROM pr_mention WHERE links_to_us = false AND status IN ('NEW', 'ASK_FOR_LINK', 'ASKED')) AS mentions_unlinked,
                (SELECT count(*) FROM pr_pitch p
                 WHERE p.parent_pitch_id IS NULL AND p.status = 'SENT' AND p.sent_at <= ?
                   AND NOT EXISTS (SELECT 1 FROM pr_pitch f WHERE f.parent_pitch_id = p.id)) AS follow_ups_due
            """.trimIndent()
    }
}
