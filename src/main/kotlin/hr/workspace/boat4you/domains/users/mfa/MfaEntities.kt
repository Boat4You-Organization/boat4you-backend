package hr.workspace.boat4you.domains.users.mfa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

// Two-step sign-in for the admin (V9_128, Mario 16.11.2026). Plain tables keyed by user_id, no AbstractEntity audit
// columns: rows are written only by MfaService.

@Entity
@Table(name = "user_passkey")
class UserPasskey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "user_id", columnDefinition = "BIGINT", nullable = false)
    var userId: Long = 0

    /** base64url, as the browser reports it. */
    @Column(name = "credential_id", columnDefinition = "VARCHAR(512)", nullable = false, unique = true)
    var credentialId: String = ""

    /** webauthn4j AttestedCredentialDataConverter bytes: AAGUID, credential id and the COSE public key. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "attested_credential_data", nullable = false)
    var attestedCredentialData: ByteArray = ByteArray(0)

    @Column(name = "sign_count", columnDefinition = "BIGINT", nullable = false)
    var signCount: Long = 0

    @Column(name = "label", columnDefinition = "VARCHAR(100)")
    var label: String? = null

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "last_used_at", columnDefinition = "TIMESTAMPTZ")
    var lastUsedAt: Instant? = null
}

@Entity
@Table(name = "user_totp")
class UserTotp {
    @Id
    @Column(name = "user_id", columnDefinition = "BIGINT")
    var userId: Long = 0

    /** base32; set once the first code confirmed it. */
    @Column(name = "secret", columnDefinition = "VARCHAR(64)")
    var secret: String? = null

    /** base32; shown during set-up, until confirmed. */
    @Column(name = "pending_secret", columnDefinition = "VARCHAR(64)")
    var pendingSecret: String? = null

    @Column(name = "enabled_at", columnDefinition = "TIMESTAMPTZ")
    var enabledAt: Instant? = null

    /** The 30-second step of the last accepted code: a code is never accepted twice. */
    @Column(name = "last_step", columnDefinition = "BIGINT")
    var lastStep: Long? = null
}

@Entity
@Table(name = "user_recovery_code")
class UserRecoveryCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "user_id", columnDefinition = "BIGINT", nullable = false)
    var userId: Long = 0

    /** SHA-256 hex of the normalised code. */
    @Column(name = "code_hash", columnDefinition = "VARCHAR(64)", nullable = false)
    var codeHash: String = ""

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "used_at", columnDefinition = "TIMESTAMPTZ")
    var usedAt: Instant? = null
}

@Entity
@Table(name = "user_trusted_device")
class UserTrustedDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "user_id", columnDefinition = "BIGINT", nullable = false)
    var userId: Long = 0

    /** SHA-256 hex of the device token the browser keeps. */
    @Column(name = "token_hash", columnDefinition = "VARCHAR(64)", nullable = false, unique = true)
    var tokenHash: String = ""

    @Column(name = "user_agent", columnDefinition = "VARCHAR(255)")
    var userAgent: String? = null

    @Column(name = "created_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "expires_at", columnDefinition = "TIMESTAMPTZ", nullable = false)
    var expiresAt: Instant = Instant.now()
}

interface UserPasskeyRepository : JpaRepository<UserPasskey, Long> {
    fun findAllByUserId(userId: Long): List<UserPasskey>

    fun findByCredentialId(credentialId: String): UserPasskey?

    @Modifying
    @Query("DELETE FROM UserPasskey p WHERE p.userId = :userId")
    fun deleteAllByUserId(userId: Long): Int
}

interface UserTotpRepository : JpaRepository<UserTotp, Long>

interface UserRecoveryCodeRepository : JpaRepository<UserRecoveryCode, Long> {
    fun findAllByUserIdAndUsedAtIsNull(userId: Long): List<UserRecoveryCode>

    @Modifying
    @Query("DELETE FROM UserRecoveryCode c WHERE c.userId = :userId")
    fun deleteAllByUserId(userId: Long): Int
}

interface UserTrustedDeviceRepository : JpaRepository<UserTrustedDevice, Long> {
    fun findByTokenHash(tokenHash: String): UserTrustedDevice?

    @Modifying
    @Query("DELETE FROM UserTrustedDevice d WHERE d.userId = :userId")
    fun deleteAllByUserId(userId: Long): Int
}
