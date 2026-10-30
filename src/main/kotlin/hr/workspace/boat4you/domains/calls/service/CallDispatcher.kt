package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.elevenlabs.ElevenLabsApi
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Dials due calls (design §6.2, runs on the scheduler node). For each due row, locked so two runs never take the same one:
 * the rules are checked again right before dialling (skip with a reason), a client outside their window is moved to their
 * next slot, the daily/monthly limit pauses dialling; otherwise the row becomes DIALING and is committed BEFORE the external
 * call, so a crash can never dial twice. An ElevenLabs error marks the row FAILED and plans one retry; a second technical
 * failure of the same call tells Mario.
 */
@Service
class CallDispatcher(
    private val calls: InquiryCallRepository,
    private val inquiries: InquiryRepository,
    private val eligibility: CallEligibility,
    private val planner: CallPlanner,
    private val elevenLabs: ElevenLabsApi,
    private val settings: CallsSettings,
    private val notifier: CallNotifier,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    private class Dial(
        val callId: Long,
        val toNumber: String,
        val language: String,
        val variables: Map<String, String>,
    )

    /** Dials what is due at [now]; returns how many calls were started. */
    fun runOnce(now: Instant): Int {
        val s = settings.snapshot()
        if (!s.enabled) return 0
        var limitHit = false
        val dials =
            tx
                .execute {
                    calls.claimDue(now, BATCH).mapNotNull { id ->
                        val call = calls.findById(id).orElse(null) ?: return@mapNotNull null
                        val inquiry = inquiries.findById(call.inquiryId!!).orElse(null) ?: return@mapNotNull null
                        val reason = eligibility.check(call, inquiry, now, s)
                        val slot = CallClock.nextSlot(now, CallClock.clientZone(call.toNumber!!), s)
                        when {
                            reason != null -> {
                                call.status = CallStatus.SKIPPED
                                call.skipReason = reason
                                calls.save(call)
                                null
                            }
                            slot != now -> {
                                call.scheduledAt = slot
                                calls.save(call)
                                null
                            }
                            eligibility.limitReached(now, s) -> {
                                limitHit = true
                                null
                            }
                            else -> {
                                call.status = CallStatus.DIALING
                                call.startedAt = now
                                calls.saveAndFlush(call)
                                Dial(id, call.toNumber!!, call.language ?: "en", CallContextBuilder.variables(inquiry, call, now, s))
                            }
                        }
                    }
                }.orEmpty()
        if (limitHit) notifier.limitReached(now.atZone(CallClock.ZAGREB).toLocalDate())
        return dials.count { dial(it, now) }
    }

    private fun dial(
        d: Dial,
        now: Instant,
    ): Boolean {
        val result = runCatching { elevenLabs.startOutboundCall(d.toNumber, d.language, d.variables) }
        tx.executeWithoutResult {
            val call = calls.findById(d.callId).orElseThrow()
            result
                .onSuccess {
                    call.elevenlabsConversationId = it.conversationId
                    call.twilioCallSid = it.callSid
                    calls.save(call)
                }.onFailure { e -> failed(call, e, now) }
        }
        return result.isSuccess
    }

    private fun failed(
        call: InquiryCall,
        e: Throwable,
        now: Instant,
    ) {
        log.warn("Call {} could not be started: {}", call.id, e.message)
        val failedBefore =
            calls
                .findByInquiryIdOrderByCreatedAtDesc(call.inquiryId!!)
                .any { it.id != call.id && it.kind == call.kind && it.status == CallStatus.FAILED }
        call.status = CallStatus.FAILED
        call.endedAt = now
        call.error = (e.message ?: e.javaClass.simpleName).take(500)
        calls.save(call)
        planner.scheduleRetry(call, CallStatus.FAILED, now)
        if (failedBefore) inquiries.findById(call.inquiryId!!).ifPresent { notifier.technicalFailure(call, it) }
    }

    companion object {
        /** At most this many calls per run (one run a minute). */
        const val BATCH = 5
    }
}
