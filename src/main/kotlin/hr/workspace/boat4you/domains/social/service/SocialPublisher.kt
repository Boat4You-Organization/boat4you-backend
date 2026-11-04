package hr.workspace.boat4you.domains.social.service

import hr.workspace.boat4you.domains.social.connectors.PublishException
import hr.workspace.boat4you.domains.social.connectors.PublishJob
import hr.workspace.boat4you.domains.social.connectors.PublishMedia
import hr.workspace.boat4you.domains.social.connectors.Tokens
import hr.workspace.boat4you.domains.social.jpa.SocialAccountRepository
import hr.workspace.boat4you.domains.social.jpa.SocialAccountStatus
import hr.workspace.boat4you.domains.social.jpa.SocialPostRepository
import hr.workspace.boat4you.domains.social.jpa.SocialPostTargetRepository
import hr.workspace.boat4you.domains.social.jpa.SocialTargetStatus
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.security.GeneralSecurityException
import java.time.Duration
import java.time.Instant

/**
 * Sends due channels to their networks. Every 20 s one node (ShedLock) claims one target whose time has come
 * (`FOR UPDATE SKIP LOCKED`, status SCHEDULED → PUBLISHING, claimed_at, attempts + 1 and version + 1 in the same
 * statement, so a target is never taken twice and an admin edit loaded before the claim fails with 409), publishes it
 * outside any transaction (uploads can take minutes), then claims the next, until nothing is due or the tick has run
 * [TICK_BUDGET] (no target waits claimed while others upload). Outcome per target: PUBLISHED with the network's id and
 * link; a passing failure before the post was sent (5xx, 429, still processing) → SCHEDULED again after 1 / 5 / 15
 * minutes, at most 4 attempts; anything else → FAILED with the network's words (a failure after the send is never
 * retried, see pointOfNoReturn). A refused token marks the channel EXPIRED. A target left PUBLISHING for 45 minutes
 * (node died mid-upload; longer than the longest single upload) becomes FAILED, never retried automatically: the
 * network may have the post already, so a person decides.
 */
@Component
class SocialPublisher(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val targetRepo: SocialPostTargetRepository,
    private val accountRepo: SocialAccountRepository,
    private val postRepo: SocialPostRepository,
    private val accountService: SocialAccountService,
    private val store: SocialMediaStore,
    private val crypto: SocialCrypto,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${social.publisher.delay-ms:20000}", initialDelayString = "\${social.publisher.initial-delay-ms:30000}")
    @SchedulerLock(name = "social_publisher", lockAtMostFor = "PT40M")
    fun tick() {
        failStuck()
        val until = Instant.now().plus(TICK_BUDGET)
        while (Instant.now().isBefore(until)) {
            val id = claimDue(1).firstOrNull() ?: return
            runCatching { publishOne(id) }.onFailure { log.error("Social publish of target {} crashed", id, it) }
        }
    }

    /** SCHEDULED → PUBLISHING for up to [limit] due targets; their ids. */
    fun claimDue(limit: Int): List<Long> =
        jdbc.queryForList(
            CLAIM_SQL,
            Long::class.java,
            limit,
        )

    fun failStuck() {
        val stuck =
            jdbc.queryForList(
                "SELECT id FROM social_post_target WHERE status = 'PUBLISHING' AND claimed_at < now() - interval '45 minutes'",
                Long::class.java,
            )
        stuck.forEach { id ->
            finish(id) { t ->
                t.status = SocialTargetStatus.FAILED
                t.error = "Interrupted while publishing; check the network before retrying"
            }
        }
    }

    /** One claimed target: load, refresh the token if needed, publish, record. */
    @Suppress("TooGenericExceptionCaught") // a connector bug must not leave the target PUBLISHING forever
    fun publishOne(targetId: Long) {
        val job =
            try {
                prepare(targetId) ?: return
            } catch (e: Exception) {
                // Nothing was sent. A crypto failure means SOCIAL_TOKEN_KEY is missing or differs between the nodes.
                log.error("Social target {} cannot be prepared: {}", targetId, e.javaClass.simpleName)
                finish(targetId) { t ->
                    t.status = SocialTargetStatus.FAILED
                    t.error =
                        when (e) {
                            is GeneralSecurityException -> "Token cannot be decrypted (check SOCIAL_TOKEN_KEY is identical on both nodes)"
                            is SocialConfigException -> e.message
                            else -> "Could not be prepared (${e.javaClass.simpleName}); nothing was sent"
                        }
                    t.dueAt = null
                }
                return
            }
        val connector = accountService.connectorFor(job.job.platform)
        try {
            if (connector == null) throw PublishException("${job.job.platform} is not available yet")
            val refreshed = connector.refresh(job.job.tokens)
            if (refreshed != null) saveTokens(job.platformAccountId, refreshed)
            val result = connector.publish(job.job.copy(tokens = refreshed ?: job.job.tokens, accepted = { id -> saveAccepted(targetId, id) }))
            finish(targetId) { t ->
                t.status = SocialTargetStatus.PUBLISHED
                t.externalId = result.externalId.take(EXTERNAL_ID_MAX)
                t.externalUrl = result.url?.take(URL_MAX)
                t.error = null
                t.publishedAt = Instant.now()
            }
        } catch (e: PublishException) {
            onFailure(targetId, job.platformAccountId, e)
        } catch (e: Exception) {
            // Unknown where it broke: the network may have the post, so a person decides (never retried by itself).
            log.error("Social publish of target {} failed", targetId, e)
            onFailure(targetId, job.platformAccountId, PublishException("Unexpected error: ${e.javaClass.simpleName}; check the channel before retrying"))
        }
    }

    /** The network's id for a post it accepted but has not finished (TikTok publish_id), stored before anything else can fail. */
    private fun saveAccepted(
        targetId: Long,
        externalId: String,
    ) {
        jdbc.update("UPDATE social_post_target SET external_id = ? WHERE id = ?", externalId.take(EXTERNAL_ID_MAX), targetId)
    }

    /** The target's job with the account's decrypted tokens; null when it is gone, not PUBLISHING, or its channel is not connected (then FAILED). */
    private fun prepare(targetId: Long): PreparedJob? =
        tx.execute {
            val t = targetRepo.findById(targetId).orElse(null) ?: return@execute null
            if (t.status != SocialTargetStatus.PUBLISHING) return@execute null
            val a = t.account!!
            val p = t.post!!
            if (p.media.isNotEmpty()) store.requireShared()
            if (a.status != SocialAccountStatus.CONNECTED || a.disconnectedAt != null || a.accessTokenEnc == null) {
                t.status = SocialTargetStatus.FAILED
                t.error = "${a.name} is not connected; connect it again and retry"
                t.dueAt = null
                targetRepo.save(t)
                p.status = SocialPostService.deriveStatus(p.targets.map { it.status }, p.status)
                postRepo.save(p)
                return@execute null
            }
            PreparedJob(
                platformAccountId = a.id!!,
                job =
                    PublishJob(
                        platform = a.platform!!,
                        externalId = a.externalId!!,
                        tokens = Tokens(crypto.decrypt(a.accessTokenEnc!!), crypto.decryptOrNull(a.refreshTokenEnc), a.tokenExpiresAt),
                        meta = a.meta.toMap(),
                        privateOnly = a.privateOnly,
                        text = t.text ?: p.text,
                        title = t.title,
                        link = p.link,
                        resumeId = t.externalId,
                        media =
                            p.media.map { m ->
                                PublishMedia(
                                    kind = m.kind,
                                    url = store.publicUrl(m),
                                    file = store.file(m),
                                    mimeType = m.mimeType,
                                    sizeBytes = m.sizeBytes,
                                    width = m.width,
                                    height = m.height,
                                    jpegAtMost = { max -> store.jpegAtMost(m, max) },
                                )
                            },
                    ),
            )
        }

    private data class PreparedJob(
        val platformAccountId: Long,
        val job: PublishJob,
    )

    private fun onFailure(
        targetId: Long,
        accountId: Long,
        e: PublishException,
    ) {
        if (e.tokenInvalid) {
            tx.executeWithoutResult {
                accountRepo.findById(accountId).ifPresent { a ->
                    a.status = SocialAccountStatus.EXPIRED
                    a.lastError = e.message?.take(ERROR_MAX)
                    accountRepo.save(a)
                }
            }
        }
        finish(targetId) { t ->
            t.error = e.message?.take(ERROR_MAX)
            if (e.retryable && !e.tokenInvalid && t.attempts < MAX_ATTEMPTS) {
                t.status = SocialTargetStatus.SCHEDULED
                t.dueAt = Instant.now().plus(BACKOFF[(t.attempts - 1).coerceIn(0, BACKOFF.lastIndex)])
            } else {
                t.status = SocialTargetStatus.FAILED
                t.dueAt = null
            }
        }
    }

    private fun saveTokens(
        accountId: Long,
        tokens: Tokens,
    ) = tx.executeWithoutResult {
        accountRepo.findById(accountId).ifPresent { a ->
            a.accessTokenEnc = crypto.encrypt(tokens.accessToken)
            tokens.refreshToken?.let { a.refreshTokenEnc = crypto.encrypt(it) }
            a.tokenExpiresAt = tokens.expiresAt
            accountRepo.save(a)
        }
    }

    /** Change one target and re-derive its post's status and publish time, in one transaction. */
    private fun finish(
        targetId: Long,
        change: (hr.workspace.boat4you.domains.social.jpa.SocialPostTarget) -> Unit,
    ) = tx.executeWithoutResult {
        val t = targetRepo.findById(targetId).orElse(null) ?: return@executeWithoutResult
        change(t)
        targetRepo.save(t)
        val p = t.post!!
        p.status = SocialPostService.deriveStatus(p.targets.map { it.status }, p.status)
        if (p.publishedAt == null && t.status == SocialTargetStatus.PUBLISHED) p.publishedAt = t.publishedAt
        postRepo.save(p)
    }

    companion object {
        /** Due SCHEDULED targets → PUBLISHING (attempts + 1), skipping rows another transaction holds; their ids. */
        const val CLAIM_SQL =
            "UPDATE social_post_target SET status = 'PUBLISHING', claimed_at = now(), attempts = attempts + 1, version = version + 1 " +
                "WHERE id IN (SELECT id FROM social_post_target WHERE status = 'SCHEDULED' AND due_at <= now() " +
                "ORDER BY due_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING id"

        /**
         * A tick stops taking new targets after this. Should the last upload outlive lockAtMostFor (40 min), the other
         * node may tick meanwhile: claims are atomic, and failStuck only takes targets claimed more than 45 min ago.
         */
        private val TICK_BUDGET: Duration = Duration.ofMinutes(25)

        /** The first try and three retries, after 1, 5 and 15 minutes. */
        private const val MAX_ATTEMPTS = 4
        private const val ERROR_MAX = 1000
        private const val EXTERNAL_ID_MAX = 200
        private const val URL_MAX = 1000
        private val BACKOFF: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15))
    }
}
