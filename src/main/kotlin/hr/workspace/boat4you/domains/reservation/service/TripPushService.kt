package hr.workspace.boat4you.domains.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripEvent
import hr.workspace.boat4you.domains.reservation.jpa.TripEventRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipantRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipantRole
import hr.workspace.boat4you.domains.reservation.jpa.TripPushSubscription
import hr.workspace.boat4you.domains.reservation.jpa.TripPushSubscriptionRepository
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import jakarta.annotation.PreDestroy
import java.security.Security
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Web-push for the Boat4You Trip hub (phase 2). Subscriptions are stored per
 * trip token (= per reservation) and pushed to by [hr.workspace.boat4you.domains.reservation.job.TripPushJob]
 * on cusma3. When the VAPID keys are not configured the whole feature is off:
 * the hub receives no public key and hides its reminders card.
 *
 * Also the write-side of the day-1 trip analytics (trip_event) because both
 * share the token→reservation resolution.
 */
@Service
class TripPushService(
    private val subscriptionRepository: TripPushSubscriptionRepository,
    private val tripEventRepository: TripEventRepository,
    private val reservationRepository: ReservationRepository,
    private val participantRepository: TripParticipantRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${application.trip-push.public-key}") val vapidPublicKey: String,
    @Value("\${application.trip-push.private-key}") private val vapidPrivateKey: String,
    @Value("\${application.trip-push.subject}") private val vapidSubject: String,
    @Value("\${application.trip-push.web-base-url}") private val webBaseUrl: String,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    val enabled: Boolean
        get() = vapidPublicKey.isNotBlank() && vapidPrivateKey.isNotBlank()

    /** Event types the public endpoint accepts — anything else is dropped. */
    private val allowedEventTypes = setOf("HUB_VIEW", "SITE_CLICK", "PUSH_SUBSCRIBE", "PUSH_OPEN", "DOC_OPEN")

    /**
     * Dedicated, isolated pool for REQUEST-path pushes (admin doc upload,
     * crew-list link, concierge chat post). The web-push HTTP is blocking and
     * un-timed; running it here means it never holds the request's DB
     * connection on cusma2 (the single no-swap API node). Best-effort: bounded
     * queue + DiscardPolicy so a burst / hung push endpoint can never grow
     * threads or pin the DB pool. NOT the sync/scheduling pool — keeps it clear
     * of the Hikari-burst hazard (2.7.2026). TripPushJob on cusma3 stays sync.
     */
    private val pushExecutor = ThreadPoolExecutor(
        1, 3, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(500),
        { r -> Thread(r, "trip-push").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    @PreDestroy
    fun shutdown() {
        pushExecutor.shutdown()
    }

    private val pushService: PushService? by lazy {
        if (!enabled) {
            null
        } else {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            PushService(vapidPublicKey, vapidPrivateKey, vapidSubject)
        }
    }

    /**
     * Upsert a device subscription for the reservation behind [token].
     * The push endpoint is globally unique, so a device re-subscribing (or
     * switching to another trip) updates its existing row. `isOwner` only
     * ever escalates for the same reservation — the owner's standalone PWA
     * has no web session, and a later anonymous re-subscribe must not strip
     * him of payment reminders.
     */
    @Transactional
    fun subscribe(
        token: String,
        endpoint: String,
        p256dh: String,
        auth: String,
        ownerKey: String?,
        userAgent: String?,
    ): Boolean {
        val reservationId = reservationRepository.findByTripToken(token)?.id ?: return false
        // Owner status is VERIFIED, never client-claimed: the key must match
        // this reservation's active OWNER participant.
        val isOwner = ownerKey?.let { key ->
            participantRepository.findByParticipantKey(key)
                ?.takeIf { it.reservationId == reservationId && !it.removed && it.role == TripParticipantRole.OWNER }
        } != null
        val subscription = subscriptionRepository.findByEndpoint(endpoint) ?: TripPushSubscription()
        val sameReservation = subscription.reservationId == reservationId
        subscription.reservationId = reservationId
        subscription.endpoint = endpoint
        subscription.p256dh = p256dh
        subscription.auth = auth
        subscription.isOwner = if (sameReservation) subscription.isOwner || isOwner else isOwner
        subscription.userAgent = userAgent?.take(MAX_META_LENGTH)
        subscriptionRepository.save(subscription)
        return true
    }

    /**
     * Notify the whole crew that something new landed on their trip (a travel
     * document, the crew-list link, …). Resolves the token, then sends AFTER
     * the caller's transaction commits — the admin who triggered it must not
     * wait on the push HTTP inside their write tx, and the crew must never be
     * pushed towards a change that could still roll back. No-op when push is
     * off or the reservation has no token yet.
     */
    fun notifyCrew(reservationId: Long, title: String, body: String, tag: String) {
        if (!enabled) return
        val token = reservationRepository.findById(reservationId).orElse(null)?.tripToken ?: return
        val url = "$webBaseUrl/trip/$token?push=$tag"
        afterCommit { sendToReservationAsync(reservationId, title, body, url, tag) }
    }

    /**
     * Fire-and-forget push for the REQUEST path: hands the blocking web-push
     * HTTP to [pushExecutor] so the caller's thread (and its DB connection)
     * is freed immediately. On the pool thread [sendToReservation]'s repo
     * calls open+close their own short transactions, so NO connection is held
     * across the HTTP sends. Best-effort — dropped if the pool is saturated.
     */
    fun sendToReservationAsync(
        reservationId: Long,
        title: String,
        body: String,
        url: String,
        tag: String,
        ownerOnly: Boolean = false,
    ) {
        if (!enabled) return
        pushExecutor.execute {
            runCatching { sendToReservation(reservationId, title, body, url, tag, ownerOnly) }
                .onFailure { log.warn("Async trip push failed for reservation $reservationId", it) }
        }
    }

    private fun afterCommit(block: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = block()
                },
            )
        } else {
            block()
        }
    }

    /** Append a trip analytics event; silently drops unknown types. */
    @Transactional
    fun recordEvent(token: String, type: String, meta: String?): Boolean {
        val reservationId = reservationRepository.findByTripToken(token)?.id ?: return false
        if (type !in allowedEventTypes) return true
        tripEventRepository.save(
            TripEvent().apply {
                this.reservationId = reservationId
                this.eventType = type
                this.meta = meta?.take(MAX_META_LENGTH)
            },
        )
        return true
    }

    /**
     * Push [title]/[body] to every device subscribed to the reservation
     * (owner devices only when [ownerOnly] — payment reminders never reach
     * the crew). Dead subscriptions (HTTP 404/410) are deleted. Returns the
     * number of successful deliveries.
     */
    @Transactional
    fun sendToReservation(
        reservationId: Long,
        title: String,
        body: String,
        url: String,
        tag: String,
        ownerOnly: Boolean = false,
    ): Int {
        val service = pushService ?: return 0
        val subscriptions = if (ownerOnly) {
            subscriptionRepository.findAllByReservationIdAndIsOwnerTrue(reservationId)
        } else {
            subscriptionRepository.findAllByReservationId(reservationId)
        }
        if (subscriptions.isEmpty()) return 0

        val payload = objectMapper.writeValueAsBytes(
            mapOf("title" to title, "body" to body, "url" to url, "tag" to tag),
        )
        var sent = 0
        subscriptions.forEach { subscription ->
            runCatching {
                val response = service.send(Notification(subscription.endpoint, subscription.p256dh, subscription.auth, payload))
                val status = response.statusLine.statusCode
                when {
                    status in SUCCESS_RANGE -> sent++
                    status == GONE || status == NOT_FOUND -> {
                        // The browser unsubscribed / the endpoint expired.
                        subscriptionRepository.delete(subscription)
                        log.info("Trip push subscription ${subscription.id} is gone (HTTP $status) — removed")
                    }
                    else -> log.warn("Trip push to subscription ${subscription.id} failed with HTTP $status")
                }
            }.onFailure { log.warn("Trip push to subscription ${subscription.id} failed", it) }
        }
        return sent
    }

    companion object {
        private const val MAX_META_LENGTH = 255
        private const val NOT_FOUND = 404
        private const val GONE = 410
        private val SUCCESS_RANGE = 200..299
    }
}
