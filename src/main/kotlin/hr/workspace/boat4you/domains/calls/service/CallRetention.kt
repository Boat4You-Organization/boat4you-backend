package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Retention (design §6.3): recordings live `calls.audio.retentionDays` (30) after the call ends, raw webhook events 90 days
 * (without the recording: a processed event drops it at once, an orphan one after 2 days).
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
            val path = call.audioPath!!
            runCatching { audio.delete(path) }.onFailure { log.warn("Audio of call {} not deleted: {}", call.id, it.message) }
            deleted += jdbc.update("UPDATE inquiry_call SET audio_path = NULL, updated_at = now() WHERE id = ? AND audio_path = ?", call.id, path)
        }
        // A recording whose event never found its call (given up after 2 days) must not live on in the table.
        val stripped =
            jdbc.update(
                "UPDATE call_webhook_event SET payload = payload #- '{data,full_audio}' WHERE type = ? AND processed_at IS NULL " +
                    "AND received_at < ? AND payload #> '{data,full_audio}' IS NOT NULL",
                CallWebhookProcessor.AUDIO,
                Timestamp.from(now.minus(CallReconciler.EVENT_GIVE_UP)),
            )
        val events = jdbc.update("DELETE FROM call_webhook_event WHERE received_at < ?", Timestamp.from(now.minus(EVENT_RETENTION)))
        if (deleted > 0 || stripped > 0 || events > 0) {
            log.info("Call retention: {} recording(s) deleted, {} orphan recording(s) stripped, {} webhook event(s) deleted", deleted, stripped, events)
        }
    }

    companion object {
        val EVENT_RETENTION: Duration = Duration.ofDays(90)
    }
}
