package hr.workspace.boat4you.domains.users.services

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber.CountryCodeSource
import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.exceptions.UnmodifiableFieldsException
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
import kotlin.jvm.optionals.getOrElse

@Service
@Suppress("TooManyFunctions")
class UserMutationService(
    private val userRepository: UserRepository,
    private val roleService: RoleService,
    private val roleAssignmentRepository: RoleAssignmentRepository,
    private val passwordService: PasswordService,
    private val tokenService: TokenService,
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

        tokenService.revokeAllUserTokens(id)

        roleAssignmentRepository.deleteByUserId(id)
        userRepository.deleteById(id)

        logger.debug("Deleted User with id $id")
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

        val emailValidator: EmailValidator = EmailValidator.getInstance()
        if (!emailValidator.isValid(user.email)) {
            badOrMissingParameters["email"] = "Email not valid"
        }

        if (!user.phoneNumber.isNullOrBlank()) {
            val phoneValidator = PhoneNumberUtil.getInstance()
            val phoneNumber =
                try {
                    phoneValidator.parse(user.phoneNumber, CountryCodeSource.UNSPECIFIED.name)
                } catch (e: NumberParseException) {
                    badOrMissingParameters["phoneNumber"] = "Phone number not valid"
                    null
                }
            if (phoneNumber != null && !phoneValidator.isValidNumber(phoneNumber)) {
                badOrMissingParameters["phoneNumber"] = "Phone number not valid"
            }
        }

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
