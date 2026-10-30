package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * The AI chat's own clock on the API node (cusma2), where the breaker counters, the "AI paused" notices and the
 * single-flight lock of [AiChatService] live in memory; hence `!data-sync`, the reverse of the Contact Center's
 * scheduler jobs, and no ShedLock (one API node; every write here is a conditional UPDATE or goes through single-flight).
 * After a restart it restores a manual breaker open and answers the visitor messages a restart cut off (BACKEND-18/19);
 * every 15 s it closes a breaker whose cooldown has passed, posts the holding message of a handoff that has waited
 * `cc.handoff.minutes`, and gives a taken-over conversation back to the AI when its return is set and due.
 */
@Component
@Profile("!data-sync")
class CcAiWatchdog(
    private val chat: AiChatService,
    private val sessions: AiChatSessionRepository,
    private val messages: AiChatMessageRepository,
    private val breaker: AiCircuitBreaker,
    private val settings: CcAiSettings,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        breaker.restoreManualState()
        Thread {
            val answered = recoverAfterRestart()
            log.info("CC_AI recovery answered {} sessions", answered)
        }.apply {
            name = "cc-ai-recovery"
            isDaemon = true
            start()
        }
    }

    /**
     * Answers every AI session whose last line is a visitor message of the last [RECOVERY_WINDOW] that nothing answered:
     * only fresh, unflagged, undeleted AI sessions, each through [AiChatService.answerPending] (single-flight, guard,
     * breaker). A failure of one session is logged and the next one is tried.
     */
    internal fun recoverAfterRestart(now: Instant = Instant.now()): Int =
        sessions
            .findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndLastActivityAtAfter(AiChatSession.STATUS_AI, now.minus(RECOVERY_WINDOW))
            .count { s ->
                runCatching { chat.answerPending(s, RECOVERY_WINDOW) != null }
                    .getOrElse {
                        log.warn("CC_AI recovery of session {} failed: {}", s.id, it.message)
                        false
                    }
            }

    /** One watchdog round; a failing step is logged and the next one still runs. */
    @Scheduled(fixedDelayString = "\${cc.ai.watchdog-ms:15000}", initialDelayString = "\${cc.ai.watchdog-ms:15000}")
    fun tick() {
        runCatching { breaker.tick() }.onFailure { log.warn("CC_AI watchdog: breaker check failed: {}", it.message) }
        runCatching { postDueHoldings() }.onFailure { log.warn("CC_AI watchdog: holding messages failed: {}", it.message) }
        runCatching { returnDueTakeovers() }.onFailure { log.warn("CC_AI watchdog: AI return failed: {}", it.message) }
    }

    /** Holding messages for handoffs requested between [HOLDING_MAX_AGE] and `cc.handoff.minutes` ago that have none yet. */
    internal fun postDueHoldings(now: Instant = Instant.now()): Int {
        val minutes = settings.current().handoffMinutes.toLong()
        return sessions
            .findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndHandoffHoldingAtIsNullAndHandoffRequestedAtBetween(
                AiChatSession.STATUS_HUMAN_REQUESTED,
                now.minus(HOLDING_MAX_AGE),
                now.minus(Duration.ofMinutes(minutes)),
            ).count { chat.postHandoffHolding(it) }
    }

    /**
     * Gives a taken-over (HUMAN) conversation back to the AI when its return is set, on the conversation or as
     * `cc.takeover.return.minutes` (none = today's rule: the AI never returns by itself), and its last line is a visitor
     * message at least that many minutes old that the team has not answered. Never while the breaker is open for its site.
     */
    internal fun returnDueTakeovers(now: Instant = Instant.now()): Int {
        val global = settings.current().takeoverReturnMinutes
        return sessions
            .findAllByStatusAndFlaggedFalseAndDeletedAtIsNullAndLastActivityAtAfter(AiChatSession.STATUS_HUMAN, now.minus(TAKEOVER_LOOKBACK))
            .count { s ->
                val minutes = s.aiReturnAfterMinutes ?: global ?: return@count false
                val last = messages.findFirstBySessionIdOrderByIdDesc(s.id!!)
                val due =
                    last != null &&
                        last.role == AiChatMessage.ROLE_USER &&
                        !last.createdAt.isAfter(now.minus(Duration.ofMinutes(minutes.toLong()))) &&
                        !breaker.isOpen(s.siteId)
                due && chat.returnToAi(s)
            }
    }

    companion object {
        val RECOVERY_WINDOW: Duration = Duration.ofMinutes(5)
        val HOLDING_MAX_AGE: Duration = Duration.ofHours(24)
        val TAKEOVER_LOOKBACK: Duration = Duration.ofHours(24)
    }
}
