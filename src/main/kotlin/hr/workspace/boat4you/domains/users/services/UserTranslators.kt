package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.common.services.getRandomPassword
import hr.workspace.boat4you.common.services.toJpaEntityStatus
import hr.workspace.boat4you.common.services.toStatusModel
import hr.workspace.boat4you.domains.roles.services.toRoleModel
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserInviteStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import org.openapitools.model.RoleEnum
import org.openapitools.model.User
import org.openapitools.model.UserInviteStatus
import org.openapitools.model.UserRegistrationRequest
import org.openapitools.model.UserRole

fun UserEntity.toUserModel(): User =
    User(
        id = id,
        name = name,
        surname = surname,
        email = email,
        phoneNumber = phoneNumber,
        language = language,
        currency = currency,
        userStatus = entityStatus.toStatusModel(),
        roles = roleAssignments.map { it.role.toRoleModel() },
        inviteStatus = inviteStatus.toUserInviteStatusModel(),
    )

fun User.toJpaUserEntity(): UserEntity {
    val model = this
    return UserEntity().apply {
        model.id?.let {
            id = model.id
        }
        name = model.name
        surname = model.surname
        password = if (model.password.isNullOrBlank()) getRandomPassword() else model.password!!
        email = model.email
        phoneNumber = model.phoneNumber
        model.userStatus?.let {
            entityStatus = it.toJpaEntityStatus()
        }
        registrationStatus = UserRegistrationStatusEnum.REGISTERED
    }
}

fun UserEntity.updateBlockWithModel(model: User) {
    name = model.name
    surname = model.surname
    email = model.email
    phoneNumber = model.phoneNumber
    model.userStatus?.let {
        entityStatus = it.toJpaEntityStatus()
    }
}

fun UserEntity.toDomainUser() = UserDomainEntity(this)

fun UserRegistrationRequest.toUserModel(): User =
    User(
        name = this.name,
        surname = this.surname,
        email = this.email,
        phoneNumber = this.phoneNumber,
        password = this.password,
        roles = listOf(UserRole(RoleEnum.USER)),
    )

fun UserInviteStatusEnum.toUserInviteStatusModel(): UserInviteStatus =
    when (this) {
        UserInviteStatusEnum.NOT_INVITED -> UserInviteStatus.NOT_INVITED
        UserInviteStatusEnum.INVITED -> UserInviteStatus.INVITED
        UserInviteStatusEnum.ACCEPTED -> UserInviteStatus.ACCEPTED
    }
