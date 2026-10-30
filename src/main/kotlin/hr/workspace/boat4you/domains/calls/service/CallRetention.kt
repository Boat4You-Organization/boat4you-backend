package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Retention (design §6.3): recordings live `calls.audio.retentionDays` (30) after the call ends, raw webhook events 90 days.
 * Call rows and transcripts live as long as their inquiry (24-month inquiry retention, FK cascade).
 */
@Service
class CallRetention(
    private val calls: InquiryCallRepository,
    private val audio: CallAudioStore,
    private val settings: CallsSettings,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOnce(now: Instant) {
        val days = settings.snapshot().audioRetentionDays.toLong()
        var deleted = 0
        calls.findWithAudioEndedBefore(now.minus(Duration.ofDays(days))).forEach { call ->
            runCatching { audio.delete(call.audioPath!!) }.onFailure { log.warn("Audio of call {} not deleted: {}", call.id, it.message) }
            call.audioPath = null
            calls.save(call)
            deleted++
        }
        val events = jdbc.update("DELETE FROM call_webhook_event WHERE received_at < ?", Timestamp.from(now.minus(EVENT_RETENTION)))
        if (deleted > 0 || events > 0) log.info("Call retention: {} recording(s), {} webhook event(s) deleted", deleted, events)
    }

    companion object {
        val EVENT_RETENTION: Duration = Duration.ofDays(90)
    }
}
