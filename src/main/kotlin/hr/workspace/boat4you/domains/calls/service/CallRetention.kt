package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Retention (Mario 18.11.2026: "everything from Nika is deleted after 20 days"): `calls.audio.retentionDays` (20) after a
 * call ends its recording, transcript, summary, collected data and quality notes are deleted; the raw webhook events and
 * the offer e-mail text Nika read go after the same number of days. The call row itself stays with its inquiry (when, how
 * it ended, "do not call"): that is the proof of what was done, not the content of the conversation.
 * A recording never outlives its event: a processed event drops it at once, an orphan one after 2 days.
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
        val cutoff = now.minus(Duration.ofDays(settings.snapshot().audioRetentionDays.toLong()))
        var deleted = 0
        calls.findWithAudioEndedBefore(cutoff).forEach { call ->
            val path = call.audioPath!!
            runCatching { audio.delete(path) }.onFailure { log.warn("Audio of call {} not deleted: {}", call.id, it.message) }
            deleted += jdbc.update("UPDATE inquiry_call SET audio_path = NULL, updated_at = now() WHERE id = ? AND audio_path = ?", call.id, path)
        }
        val contents =
            jdbc.update(
                "UPDATE inquiry_call SET transcript = NULL, summary = NULL, data = NULL, quality = NULL, updated_at = now() " +
                    "WHERE ended_at < ? AND (transcript IS NOT NULL OR summary IS NOT NULL OR data IS NOT NULL OR quality IS NOT NULL)",
                Timestamp.from(cutoff),
            )
        // A recording whose event never found its call (given up after 2 days) must not live on in the table.
        val stripped =
            jdbc.update(
                "UPDATE call_webhook_event SET payload = payload #- '{data,full_audio}' WHERE type = ? AND processed_at IS NULL " +
                    "AND received_at < ? AND payload #> '{data,full_audio}' IS NOT NULL",
                CallWebhookProcessor.AUDIO,
                Timestamp.from(now.minus(CallReconciler.EVENT_GIVE_UP)),
            )
        val events = jdbc.update("DELETE FROM call_webhook_event WHERE received_at < ?", Timestamp.from(cutoff))
        // The offer e-mail Nika read is needed for the follow-up call only (48 h after the offer is marked as sent, which may
        // be later than the e-mail's own date: the later of the two counts).
        val offerCutoff = Timestamp.from(cutoff)
        val offers =
            jdbc.update(
                "UPDATE inquiry SET offer_email_text = NULL WHERE offer_email_text IS NOT NULL AND GREATEST(offer_email_sent_at, offer_sent_at) < ?",
                offerCutoff,
            )
        if (deleted > 0 || contents > 0 || stripped > 0 || events > 0 || offers > 0) {
            log.info(
                "Call retention: {} recording(s) deleted, {} call(s) emptied, {} orphan recording(s) stripped, {} webhook event(s) deleted, {} offer e-mail(s) cleared",
                deleted,
                contents,
                stripped,
                events,
                offers,
            )
        }
    }
}
