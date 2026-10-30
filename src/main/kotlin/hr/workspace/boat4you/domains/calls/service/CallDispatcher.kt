package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.elevenlabs.CallNotStartedException
import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsApi
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Dials due calls (design §6.2, runs on the scheduler node). Each due row is handled in its own transaction, locked and
 * re-read, so one broken row never stops the others and two runs never take the same row. The rules are checked again right
 * before dialling (skip with a reason); a client outside their window is moved to their next slot; a call waits while
 * another call to the same number is on; the daily/monthly limit pauses dialling. Otherwise the row becomes DIALING and is
 * committed BEFORE the external call, so a crash can never dial twice. When ElevenLabs certainly did not place the call, the
 * row is FAILED and retried once; when that is unclear (timeout, 5xx) it stays DIALING and is never dialled again: the
 * webhook finds it by its call id, or the reconciler closes it.
 */
@Service
class CallDispatcher(
    private val calls: InquiryCallRepository,
    private val inquiries: InquiryRepository,
    private val eligibility: CallEligibility,
    private val outcomes: CallOutcomeService,
    private val elevenLabs: ElevenLabsApi,
    private val settings: CallsSettings,
    private val notifier: CallNotifier,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    private sealed interface Step {
        class Dial(
            val callId: Long,
            val toNumber: String,
            val language: String,
            val variables: Map<String, String>,
        ) : Step

        data object LimitReached : Step

        data object Handled : Step
    }

    /** Dials what is due at [now]; returns how many calls were started. */
    fun runOnce(now: Instant): Int {
        val s = settings.snapshot()
        if (!s.enabled) return 0
        var started = 0
        for (id in tx.execute { calls.claimDue(now, BATCH) }.orEmpty()) {
            val step =
                try {
                    tx.execute { prepare(id, now, s) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    broken(id, e, now)
                    null
                }
            if (step == Step.LimitReached) {
                notifier.limitReached(now.atZone(CallClock.ZAGREB).toLocalDate())
                break
            }
            if (step is Step.Dial && dial(step)) started++
        }
        return started
    }

    private fun prepare(
        id: Long,
        now: Instant,
        s: CallsSettings.Snapshot,
    ): Step {
        val call = calls.lockFresh(id)?.takeIf { it.status == CallStatus.SCHEDULED } ?: return Step.Handled
        val inquiry = inquiries.findById(call.inquiryId!!).orElse(null) ?: return Step.Handled
        eligibility.check(call, inquiry, now, s)?.let { return skip(call, it) }
        val zones = CallClock.clientZones(call.toNumber!!)
        val slot = CallClock.nextSlot(now, zones, s) ?: return skip(call, SkipReason.TIMEZONE_UNCLEAR)
        if (slot != now) return reschedule(call, slot)
        if (eligibility.anotherCallActive(call)) {
            return reschedule(call, CallClock.nextSlot(now.plus(WAIT_FOR_OTHER_CALL), zones, s) ?: return skip(call, SkipReason.TIMEZONE_UNCLEAR))
        }
        if (eligibility.limitReached(now, s)) return Step.LimitReached
        call.status = CallStatus.DIALING
        call.startedAt = now
        calls.saveAndFlush(call)
        val since = LocalDateTime.ofInstant(now.minus(CallEligibility.RECENT), ZoneId.systemDefault())
        val others = inquiries.findOtherRecentByPhone(call.toNumber!!, inquiry.id!!, since)
        return Step.Dial(id, call.toNumber!!, call.language ?: "en", CallContextBuilder.variables(inquiry, call, now, s, others))
    }

    private fun dial(d: Step.Dial): Boolean {
        val result = runCatching { elevenLabs.startOutboundCall(d.toNumber, d.language, d.variables) }
        val error = result.exceptionOrNull()
        if (error is CallNotStartedException) {
            log.warn("Call {} not started: {}", d.callId, error.message)
            calls.findById(d.callId).ifPresent { outcomes.applyNotStarted(it, error.message ?: "not started") }
            return false
        }
        try {
            tx.executeWithoutResult {
                val call = calls.lockFresh(d.callId) ?: return@executeWithoutResult
                result.onSuccess {
                    if (call.elevenlabsConversationId == null) call.elevenlabsConversationId = it.conversationId
                    if (call.twilioCallSid == null) call.twilioCallSid = it.callSid
                }
                if (error != null && call.status in CallOutcomeService.OPEN && call.elevenlabsConversationId == null) {
                    log.warn("Call {}: unclear whether it started ({}); left DIALING, never dialled again", d.callId, error.javaClass.simpleName)
                    call.error = "unclear whether the call started: ${error.message ?: error.javaClass.simpleName}".take(500)
                }
                calls.save(call)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Call {}: ids not saved after the start; the webhook finds it by its call id", d.callId, e)
        }
        return result.isSuccess
    }

    private fun skip(
        call: InquiryCall,
        reason: SkipReason,
    ): Step {
        call.status = CallStatus.SKIPPED
        call.skipReason = reason
        calls.save(call)
        return Step.Handled
    }

    private fun reschedule(
        call: InquiryCall,
        at: Instant,
    ): Step {
        call.scheduledAt = at
        calls.save(call)
        return Step.Handled
    }

    /** A row that cannot even be prepared is closed as FAILED (never dialled, no retry) and reported, so it never blocks the queue. */
    private fun broken(
        id: Long,
        e: Exception,
        now: Instant,
    ) {
        log.error("Call {} could not be prepared; marked FAILED", id, e)
        runCatching {
            tx.executeWithoutResult {
                val call = calls.lockFresh(id)?.takeIf { it.status == CallStatus.SCHEDULED } ?: return@executeWithoutResult
                call.status = CallStatus.FAILED
                call.endedAt = now
                call.error = "internal error: ${e.javaClass.simpleName}"
                calls.save(call)
                inquiries.findById(call.inquiryId!!).ifPresent { notifier.technicalFailure(call, it) }
            }
        }.onFailure { log.error("Call {} could not be marked FAILED", id, it) }
    }

    companion object {
        /** At most this many calls per run (one run a minute). */
        const val BATCH = 5

        /** How long a call waits when another call to the same number is still on. */
        val WAIT_FOR_OTHER_CALL: Duration = Duration.ofMinutes(30)
    }
}
