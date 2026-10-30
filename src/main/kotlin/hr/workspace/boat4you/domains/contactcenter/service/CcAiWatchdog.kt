package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * The AI chat's own clock on the API node (cusma2), where the breaker counters, the "AI paused" notices and the
 * single-flight lock of [AiChatService] live in memory; hence `!data-sync`, the reverse of the Contact Center's
 * scheduler jobs, and no ShedLock (one API node; every write here is a conditional UPDATE or goes through single-flight).
 * After a restart it restores a manual breaker open and answers the visitor messages a restart cut off (BACKEND-18/19).
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

    companion object {
        val RECOVERY_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
