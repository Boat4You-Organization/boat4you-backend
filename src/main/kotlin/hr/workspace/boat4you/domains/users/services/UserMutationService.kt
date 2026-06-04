package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.exceptions.UnmodifiableFieldsException
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import hr.workspace.boat4you.domains.roles.jpa.RoleAssignmentEntity
import hr.workspace.boat4you.domains.roles.jpa.RoleAssignmentRepository
import hr.workspace.boat4you.domains.roles.services.RoleService
import hr.workspace.boat4you.domains.users.exceptions.UserAlreadyExistsException
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.jpa.TokenService
import hr.workspace.boat4you.security.services.PasswordService
import org.apache.commons.validator.routines.EmailValidator
import org.openapitools.model.CurrencyEnum
import org.openapitools.model.LanguageEnum
import org.openapitools.model.User
import org.openapitools.model.UserRole
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.jvm.optionals.getOrElse

@Service
@Suppress("TooManyFunctions")
class UserMutationService(
    private val userRepository: UserRepository,
    private val roleService: RoleService,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val passwordService: PasswordService,
    private val tokenService: TokenService,
    private val inquiryRepository: InquiryRepository,
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @Transactional(readOnly = false)
    fun createUser(user: User): User {
        logger.debug("Attempting to create User ${user.email}")
        validateUserInput(user)
        if (existsByEmail(user.email)) {
            throw UserAlreadyExistsException(listOf("email"))
        }

        val userEntity =
            user.toJpaUserEntity().apply {
                id = null
                password = passwordService.encodePassword(password)
            }

        val dbUserEntity = userRepository.save(userEntity)
        dbUserEntity.apply {
            roleAssignments = createUserRoleAssignments(user.roles, dbUserEntity)
        }
        val newUserEntity = userRepository.save(dbUserEntity)

        return newUserEntity.toUserModel().also {
            logger.debug("Successfully created User ${user.email}")
        }
    }

    @Transactional(readOnly = false)
    fun updateUser(
        id: Long,
        user: User,
    ): User {
        logger.debug("Attempting to update User ${user.email}")
        validateUserInput(user)

        if (existsByIdNotAndEmail(id, user.email)) {
            throw UserAlreadyExistsException(listOf("email"))
        }

        val dbUser = userRepository.findById(id).getOrElse { throw UserDoesNotExistException() }

        val modifyingViolations = mutableListOf<String>()
        if (id != user.id) {
            modifyingViolations.add("id")
        }
        if (modifyingViolations.isNotEmpty()) {
            throw UnmodifiableFieldsException(modifyingViolations)
        }

        val updatedRoleAssignments = updateUserRoleAssignments(user.roles, dbUser)
        if (dbUser.roleAssignments.map { it.role.name } != updatedRoleAssignments.map { it.role.name }) {
            tokenService.revokeAllUserTokens(dbUser.id!!)
        }

        dbUser
            .apply { updateBlockWithModel(user) }
            .apply {
                roleAssignments = updatedRoleAssignments
            }

        val newUserEntity = userRepository.save(dbUser)

        return newUserEntity
            .toUserModel()
            .also {
                logger.debug("Successfully updated User ${user.email}")
            }
    }

    /**
     * Self-service profile update for the logged-in user. Only allows editing
     * basic contact fields (name, surname, email, phoneNumber) — roles,
     * status, and preferences are intentionally skipped so end users can't
     * escalate their own privileges through this endpoint.
     */
    @Transactional(readOnly = false)
    fun updateOwnProfile(
        id: Long,
        name: String,
        surname: String,
        email: String,
        phoneNumber: String?,
        address: String?,
        city: String?,
        country: String?,
        birthday: java.time.LocalDate? = null,
    ): User {
        val dbUser = userRepository.findById(id).getOrElse { throw UserDoesNotExistException() }

        // Re-run the validator used by admin updates so phone format / email
        // format rules stay consistent between endpoints. `validateUserInput`
        // only reads email + phoneNumber, so we can pass a minimal User shell.
        validateUserInput(
            User(
                id = id,
                name = name,
                surname = surname,
                email = email,
                phoneNumber = phoneNumber,
                roles = emptyList(),
            ),
        )

        // Email is the login identity — it changes ONLY through the verified email-change flow
        // (request → confirm via signed link), never a silent profile PATCH. A normal profile
        // edit re-sends the unchanged email, which passes through untouched; any actual change
        // is rejected here.
        if (!email.equals(dbUser.email, ignoreCase = true)) {
            throw ParameterValidationException(mapOf("email" to "Email can only be changed through verification"))
        }

        dbUser.name = name
        dbUser.surname = surname
        dbUser.phoneNumber = phoneNumber
        dbUser.address = address?.takeIf { it.isNotBlank() }
        dbUser.city = city?.takeIf { it.isNotBlank() }
        dbUser.country = country?.takeIf { it.isNotBlank() }
        // birthday: caller passes null only if the field wasn't included in
        // the request body (PATCH semantics). We don't allow clearing once
        // set — sentinel for "I want to wipe my birthday" is not implemented;
        // GDPR delete account already covers the wipe path.
        if (birthday != null) {
            dbUser.birthday = birthday
        }

        return userRepository.save(dbUser).toUserModel()
    }

    @Transactional(readOnly = false)
    fun updateUserPreferences(
        id: Long,
        currency: CurrencyEnum?,
        language: LanguageEnum?,
    ): User {
        val userEntity =
            userRepository
                .findById(id)
                .orElseThrow { IllegalArgumentException("User not found") }

        currency?.let { userEntity.currency = it }
        language?.let { userEntity.language = it }

        return userRepository.save(userEntity).toUserModel()
    }

    @Transactional(readOnly = false)
    fun deleteUser(id: Long) {
        logger.debug("Attempting to delete User with id $id")
        if (!userRepository.existsById(id)) {
            throw UserDoesNotExistException()
        }

        // Hard-delete (not just revoke) the user's tokens — tokens.user_id is a
        // NO ACTION FK, so flagged-but-present rows would block the user delete.
        tokenService.deleteAllUserTokens(id)

        roleAssignmentRepository.deleteByUserId(id)
        userRepository.deleteById(id)

        logger.debug("Deleted User with id $id")
    }

    /**
     * GDPR Article 17 — right to erasure. Customer-initiated soft delete.
     *
     * Anonymizes PII (name, surname, email, phone, address, city, country),
     * rotates password to a random hash so login is impossible, revokes
     * tokens and role assignments, and stamps `deleted_at`. Past bookings
     * (`reservation_flow.user_id` FK) are preserved untouched — Mario
     * decision 1.5.2026: "ako je klijent rezervirao s nama plovilo, to se
     * ne smije brisat, to nam treba ostati u našoj evidenciji". Audit +
     * partner-agency financial trail stays intact; only the personal data
     * tied to the user identity is wiped.
     *
     * Idempotent: calling twice for an already-deleted user returns silently.
     */
    @Transactional(readOnly = false)
    fun softDeleteForGdpr(id: Long) {
        logger.info("GDPR soft-delete requested for user id={}", id)
        val user = userRepository.findById(id).getOrElse { throw UserDoesNotExistException() }
        if (user.deletedAt != null) {
            logger.info("User id={} already soft-deleted at {} — no-op", id, user.deletedAt)
            return
        }

        // Capture the real email BEFORE anonymisation so we can purge the user's
        // inquiry leads (audit B3) — inquiries are keyed by email only (no user
        // FK), so the user-row anonymisation below doesn't reach them.
        val originalEmail = user.email

        // Step 1: kill auth — token revocation must happen before we change
        // the password hash so any in-flight requests holding stale tokens
        // get 401 immediately (next call) instead of completing on a
        // partially-anonymized account.
        tokenService.revokeAllUserTokens(id)
        roleAssignmentRepository.deleteByUserId(id)

        // Step 2: anonymize PII. Email format `deleted-{id}@boat4you-deleted.invalid`
        // is unique per user (suffix is the id) and uses the `.invalid` TLD
        // (RFC 2606 reserved) so it's guaranteed undeliverable.
        user.name = "Deleted"
        user.surname = "User"
        user.email = "deleted-$id@boat4you-deleted.invalid"
        user.phoneNumber = null
        user.address = null
        user.city = null
        user.country = null
        // Step 3: kill password — random 64-char + bcrypt rehash. Even if
        // someone got the row, can't authenticate.
        user.password = passwordService.encodePassword(java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString())
        user.passwordResetCode = null
        user.passwordResetCodeIssuedAt = null
        user.emailVerificationCode = null
        user.inviteCode = null

        user.deletedAt = Instant.now()

        userRepository.save(user)

        // GDPR Art. 17 — also erase the user's inquiry leads (name/email/phone/
        // message). Unlike reservations, inquiries carry no accounting/partner
        // retention obligation, so they are hard-deleted rather than tombstoned.
        val purgedInquiries = inquiryRepository.deleteByEmailIgnoreCase(originalEmail)

        logger.info(
            "GDPR soft-delete completed for user id={} (purged {} inquiry lead(s))",
            id,
            purgedInquiries,
        )
    }

    private fun createUserRoleAssignments(
        userRoles: List<UserRole>,
        dbUserEntity: UserEntity,
    ): MutableSet<RoleAssignmentEntity> {
        val dbRoleEntities = roleService.checkIfRolesExistAndReturnDbRoles(userRoles)

        val roleAssignmentEntities =
            dbRoleEntities.map {
                RoleAssignmentEntity().apply {
                    this.user = dbUserEntity
                    this.role = it
                }
            }

        return roleAssignmentRepository.saveAll(roleAssignmentEntities).toMutableSet()
    }

    private fun updateUserRoleAssignments(
        userRoles: List<UserRole>,
        dbUserEntity: UserEntity,
    ): MutableSet<RoleAssignmentEntity> {
        val dbRoleEntities = roleService.checkIfRolesExistAndReturnDbRoles(userRoles)

        val wantedRoleAssignmentEntities =
            dbRoleEntities.map {
                RoleAssignmentEntity().apply {
                    this.user = dbUserEntity
                    this.role = it
                }
            }

        val currentRoleAssignmentEntities = dbUserEntity.roleAssignments

        val roleAssignmentsToCreate = wantedRoleAssignmentEntities - currentRoleAssignmentEntities
        val roleAssignmentsToDelete = currentRoleAssignmentEntities - wantedRoleAssignmentEntities

        roleAssignmentRepository.deleteAll(roleAssignmentsToDelete)

        return roleAssignmentRepository.saveAll(currentRoleAssignmentEntities - roleAssignmentsToDelete + roleAssignmentsToCreate).toMutableSet()
    }

    private fun validateUserInput(user: User) {
        val badOrMissingParameters = mutableMapOf<String, String>()

        if (user.name.isNullOrBlank() || user.name.trim().length < 2) {
            badOrMissingParameters["name"] = "Name is required and must be at least 2 characters"
        }

        if (user.surname.isNullOrBlank() || user.surname.trim().length < 2) {
            badOrMissingParameters["surname"] = "Surname is required and must be at least 2 characters"
        }

        val emailValidator: EmailValidator = EmailValidator.getInstance()
        if (!emailValidator.isValid(user.email)) {
            badOrMissingParameters["email"] = "Email not valid"
        }

        // Phone validation intentionally relaxed. Admins create accounts on
        // behalf of clients from partial info (phone often unknown or in a
        // local format). libphonenumber with `UNSPECIFIED` region was
        // rejecting anything without a `+country_code` prefix — too strict
        // for the admin flow. We now accept any non-blank string; clients
        // can self-correct via their profile later.

        if (badOrMissingParameters.isNotEmpty()) {
            throw ParameterValidationException(badOrMissingParameters)
        }
    }

    private fun existsByEmail(email: String) = userRepository.existsByEmailIgnoreCase(email)

    private fun existsByIdNotAndEmail(
        id: Long,
        email: String,
    ) = userRepository.existsByIdNotAndEmailIgnoreCase(id, email)

    private fun findById(id: Long) = userRepository.findById(id)
}
