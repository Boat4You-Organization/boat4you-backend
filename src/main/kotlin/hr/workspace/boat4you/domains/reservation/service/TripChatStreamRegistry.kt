package hr.workspace.boat4you.domains.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.reservation.dto.TripChatMessageDto
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory SSE fan-out for trip chat (PG + SSE by locked decision — no
 * WebSocket, protects cusma2). Emitters are per-node state: chat writes go
 * through the API node that also serves the streams, so a single registry
 * suffices. Browsers auto-reconnect (EventSource) when an emitter times out;
 * a 25 s heartbeat keeps nginx/proxies from buffering-closing idle streams.
 */
@Service
class TripChatStreamRegistry(
    private val objectMapper: ObjectMapper,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(reservationId: Long): SseEmitter {
        val emitter = SseEmitter(EMITTER_TIMEOUT_MS)
        val list = emitters.computeIfAbsent(reservationId) { CopyOnWriteArrayList() }
        list.add(emitter)
        val cleanup = {
            list.remove(emitter)
            if (list.isEmpty()) emitters.remove(reservationId, list)
            Unit
        }
        emitter.onCompletion(cleanup)
        emitter.onTimeout(cleanup)
        emitter.onError { cleanup() }
        // Initial comment so the client sees bytes immediately (defeats
        // proxy buffering and confirms the stream is up).
        runCatching { emitter.send(SseEmitter.event().comment("connected")) }
        return emitter
    }

    fun publish(reservationId: Long, message: TripChatMessageDto) {
        val list = emitters[reservationId] ?: return
        val payload = objectMapper.writeValueAsString(message)
        list.forEach { emitter ->
            runCatching { emitter.send(SseEmitter.event().name("chat").data(payload)) }
                .onFailure {
                    list.remove(emitter)
                    runCatching { emitter.complete() }
                }
        }
    }

    /** Keep-alive for proxies; also prunes dead connections. */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    fun heartbeat() {
        emitters.values.forEach { list ->
            list.forEach { emitter ->
                runCatching { emitter.send(SseEmitter.event().comment("ping")) }
                    .onFailure {
                        list.remove(emitter)
                        runCatching { emitter.complete() }
                    }
            }
        }
    }

    fun openStreams(): Int = emitters.values.sumOf { it.size }

    companion object {
        private const val EMITTER_TIMEOUT_MS = 30L * 60 * 1000
        private const val HEARTBEAT_MS = 25_000L
    }
}
