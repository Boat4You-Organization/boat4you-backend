package hr.workspace.boat4you.domains.social.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate

interface SocialAccountRepository : JpaRepository<SocialAccount, Long> {
    fun findByPlatformAndExternalId(
        platform: SocialPlatform,
        externalId: String,
    ): SocialAccount?

    fun findAllByDisconnectedAtIsNull(): List<SocialAccount>
}

interface SocialOAuthStateRepository : JpaRepository<SocialOAuthState, String> {
    @Modifying
    @Query("delete from SocialOAuthState s where s.createdAt < :before")
    fun deleteOlderThan(before: Instant): Int
}

interface SocialMediaRepository : JpaRepository<SocialMedia, Long> {
    fun findByToken(token: String): SocialMedia?
}

interface SocialApiKeyRepository : JpaRepository<SocialApiKey, Long> {
    fun findBySecretSha256AndRevokedAtIsNull(secretSha256: String): SocialApiKey?

    fun findAllByRevokedAtIsNullOrderByCreatedAtDesc(): List<SocialApiKey>
}

interface SocialPostRepository :
    JpaRepository<SocialPost, Long>,
    JpaSpecificationExecutor<SocialPost>

interface SocialPostTargetRepository : JpaRepository<SocialPostTarget, Long> {
    fun findAllByPostId(postId: Long): List<SocialPostTarget>

    @Suppress("FunctionNaming")
    fun findAllByAccount_IdAndStatus(
        accountId: Long,
        status: SocialTargetStatus,
    ): List<SocialPostTarget>

    @Query("select count(t) from SocialPostTarget t where t.account.id = :accountId and t.status = 'SCHEDULED'")
    fun countScheduledForAccount(accountId: Long): Long
}

interface SocialAiSettingRepository : JpaRepository<SocialAiSetting, String>

interface SocialAiRunRepository : JpaRepository<SocialAiRun, Long> {
    fun findBySiteAndRunDate(
        site: String,
        runDate: LocalDate,
    ): SocialAiRun?

    fun findTop50ByOrderByCreatedAtDesc(): List<SocialAiRun>

    fun findAllByStatus(status: SocialAiRunStatus): List<SocialAiRun>
}

interface SocialGenerationRepository : JpaRepository<SocialGeneration, Long> {
    fun findAllByStatusIn(statuses: Collection<SocialGenerationStatus>): List<SocialGeneration>

    fun findAllByPostId(postId: Long): List<SocialGeneration>
}
