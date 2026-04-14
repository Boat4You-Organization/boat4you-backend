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
