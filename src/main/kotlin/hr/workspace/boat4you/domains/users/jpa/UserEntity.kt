package hr.workspace.boat4you.domains.users.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import hr.workspace.boat4you.domains.roles.jpa.RoleAssignmentEntity
import hr.workspace.boat4you.security.jpa.TokenEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.Formula
import org.openapitools.model.CurrencyEnum
import org.openapitools.model.LanguageEnum
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "users")
class UserEntity : AbstractEntity<Long>() {
    @Column(name = "name", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var name: String

    @Column(name = "surname", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var surname: String

    @Column(name = "password", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var password: String

    @Column(name = "email", columnDefinition = "VARCHAR(255)", nullable = false)
    lateinit var email: String

    @Column(name = "phone_number", columnDefinition = "VARCHAR(63)", nullable = true)
    var phoneNumber: String? = null

    @Column(name = "address", columnDefinition = "VARCHAR(255)", nullable = true)
    var address: String? = null

    @Column(name = "city", columnDefinition = "VARCHAR(100)", nullable = true)
    var city: String? = null

    @Column(name = "country", columnDefinition = "VARCHAR(100)", nullable = true)
    var country: String? = null

    @Column(name = "language", columnDefinition = "VARCHAR(10)", updatable = true, nullable = true)
    @Enumerated(EnumType.STRING)
    var language: LanguageEnum? = null

    @Column(name = "currency", columnDefinition = "VARCHAR(3)", updatable = true, nullable = true)
    @Enumerated(EnumType.STRING)
    var currency: CurrencyEnum? = null

    @Column(name = "login_attempts", columnDefinition = "INTEGER", nullable = false)
    var loginAttempts: Int = 0

    @Column(name = "password_reset_code", columnDefinition = "VARCHAR(255)", nullable = true)
    var passwordResetCode: String? = null

    /**
     * Timestamp when the current `passwordResetCode` was generated. Used
     * by `UserAuthService.checkPasswordResetValidity` to enforce a TTL on
     * reset tokens (OWASP Forgot Password cheatsheet — tokens must
     * expire). Cleared along with `passwordResetCode` on successful
     * reset. Null when there is no active reset request.
     */
    @Column(name = "password_reset_code_issued_at", columnDefinition = "TIMESTAMP", nullable = true)
    var passwordResetCodeIssuedAt: Instant? = null

    @Column(name = "last_unsuccessful_login", columnDefinition = "TIMESTAMP")
    var lastUnsuccessfulLogin: Instant? = null

    @Column(name = "email_verification_code", columnDefinition = "VARCHAR(255)", nullable = true)
    var emailVerificationCode: String? = null

    @Column(name = "registration_status", columnDefinition = "VARCHAR(31)", nullable = false)
    @Enumerated(EnumType.STRING)
    var registrationStatus: UserRegistrationStatusEnum = UserRegistrationStatusEnum.STARTED

    @Column(name = "verification_code_issued_at", columnDefinition = "TIMESTAMP", updatable = true, nullable = true)
    var verificationCodeIssuedAt: Instant? = null

    @Column(name = "invite_status", columnDefinition = "VARCHAR(31)", nullable = false)
    @Enumerated(EnumType.STRING)
    var inviteStatus: UserInviteStatusEnum = UserInviteStatusEnum.NOT_INVITED

    @Column(name = "invite_code", columnDefinition = "VARCHAR(255)", nullable = true)
    var inviteCode: String? = null

    @Column(name = "invite_time", columnDefinition = "TIMESTAMP", nullable = true)
    var inviteTime: Instant? = null

    /**
     * Date of birth — used for the annual birthday-greeting cron + shown on
     * /my-profile as a date picker. We store only the date (no time/zone)
     * because the cron matches month + day; year is informational.
     */
    @Column(name = "birthday", columnDefinition = "DATE", nullable = true)
    var birthday: LocalDate? = null

    /**
     * Timestamp of the most recent successful login. Surfaced on
     * /my-profile as "Last login" so the customer can spot unfamiliar
     * sessions without reading server logs. Set from `UserAuthService.login`
     * on every successful authentication.
     */
    @Column(name = "last_login_at", columnDefinition = "TIMESTAMP", nullable = true)
    var lastLoginAt: Instant? = null

    /**
     * GDPR right-to-erasure tombstone. When set, the user is anonymized
     * (PII wiped, password rotated, tokens revoked, roles dropped) but the
     * row stays so we can keep `reservation_flow.user_id` FK satisfied —
     * past bookings remain visible to admin for partner-agency
     * reconciliation + accounting obligations. Admin lists filter
     * `WHERE deleted_at IS NULL` for active users; mappers render
     * "[Deleted user]" for the deleted ones.
     */
    @Column(name = "deleted_at", columnDefinition = "TIMESTAMP", nullable = true)
    var deletedAt: Instant? = null

    @OneToMany(mappedBy = "user")
    var roleAssignments: MutableSet<RoleAssignmentEntity> = mutableSetOf()

    @OneToMany(mappedBy = "user")
    var tokens: MutableSet<TokenEntity> = mutableSetOf()

    /**
     * Full name of the user, concatenated from name and surname.
     * On the first call after JVM restarts will throw UninitializedPropertyAccessException
     */
    @Deprecated("Use getFullName() method instead", ReplaceWith("getFullName()"))
    @Formula("concat(name, ' ', surname)")
    lateinit var fullNameByFormula: String

    fun getFullName(): String {
        return "$name $surname"
    }
}
