package hr.workspace.boat4you.domains.users.controllers

import hr.workspace.boat4you.domains.users.services.GetAllUsersQuery
import hr.workspace.boat4you.domains.users.services.UserInviteService
import hr.workspace.boat4you.domains.users.services.UserMutationService
import hr.workspace.boat4you.domains.users.services.UserQueryingService
import hr.workspace.boat4you.security.checkAccessForAdminOrSelf
import org.openapitools.api.UsersApi
import org.openapitools.model.BasicEntityStatus
import org.openapitools.model.CurrencyEnum
import org.openapitools.model.GetAllUsers200Response
import org.openapitools.model.LanguageEnum
import org.openapitools.model.RoleEnum
import org.openapitools.model.SetUserPasswordBody
import org.openapitools.model.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

@Controller
@Validated
internal class UserController(
    private val userQueryingService: UserQueryingService,
    private val userMutationService: UserMutationService,
    private val userInviteService: UserInviteService,
) : UsersApi {
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN')")
    override fun getAllUsers(
        sortBy: String?,
        sortDirection: String?,
        pageNumber: Int?,
        pageSize: Int?,
        search: String?,
        role: RoleEnum?,
        userStatus: BasicEntityStatus?,
        activeOnly: Boolean?,
    ): ResponseEntity<GetAllUsers200Response> {
        val query =
            GetAllUsersQuery(
                sortBy = sortBy,
                sortDirection = sortDirection,
                pageNumber = pageNumber,
                pageSize = pageSize,
                search = search,
                activeOnly = activeOnly,
                role = role,
                userStatus = userStatus,
            )
        return ResponseEntity(userQueryingService.getAllUsers(query), HttpStatus.OK)
    }

    override fun getUserById(id: Long): ResponseEntity<User> {
        checkAccessForAdminOrSelf(id)

        val user = userQueryingService.getUserById(id)
        return if (user == null) {
            ResponseEntity(HttpStatus.NOT_FOUND)
        } else {
            ResponseEntity(user, HttpStatus.OK)
        }
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN')")
    override fun createUser(user: User): ResponseEntity<User> {
        return ResponseEntity(userMutationService.createUser(user), HttpStatus.OK)
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN')")
    override fun deleteUser(id: Long): ResponseEntity<Unit> {
        userMutationService.deleteUser(id)
        return ResponseEntity(HttpStatus.OK)
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN')")
    override fun updateUser(
        id: Long,
        user: User,
    ): ResponseEntity<User> {
        return ResponseEntity(userMutationService.updateUser(id, user), HttpStatus.OK)
    }

    @PatchMapping("/users/{userId}/updatePreferences")
    fun updateUser(
        @PathVariable userId: Long,
        @RequestParam(required = false) currency: CurrencyEnum?,
        @RequestParam(required = false) language: LanguageEnum?,
    ): ResponseEntity<User> {
        checkAccessForAdminOrSelf(userId)
        if (currency == null && language == null) {
            return ResponseEntity.badRequest().build()
        }

        return ResponseEntity.ok(userMutationService.updateUserPreferences(userId, currency, language))
    }

    // Self-service profile edit. Allows the authenticated user (or an admin
    // editing anyone) to change basic contact fields. Roles / status are
    // intentionally NOT accepted here — those still require the admin-only
    // PUT /users/{id} endpoint.
    @PatchMapping("/users/{userId}/profile")
    fun updateMyProfile(
        @PathVariable userId: Long,
        @RequestBody body: UpdateProfileRequest,
    ): ResponseEntity<User> {
        checkAccessForAdminOrSelf(userId)
        return ResponseEntity.ok(
            userMutationService.updateOwnProfile(
                id = userId,
                name = body.name,
                surname = body.surname,
                email = body.email,
                phoneNumber = body.phoneNumber,
                address = body.address,
                city = body.city,
                country = body.country,
            ),
        )
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN')")
    override fun inviteUsers(ids: List<Long>): ResponseEntity<Unit> {
        return ResponseEntity(userInviteService.inviteUsers(ids), HttpStatus.OK)
    }

    override fun checkUserInvitationValidity(inviteCode: String): ResponseEntity<Unit> {
        userInviteService.checkInvitationValidity(inviteCode)
        return ResponseEntity(HttpStatus.OK)
    }

    override fun acceptInvitation(
        inviteCode: String,
        setUserPasswordBody: SetUserPasswordBody,
    ): ResponseEntity<Unit> {
        return ResponseEntity(userInviteService.acceptInvitation(inviteCode, setUserPasswordBody), HttpStatus.OK)
    }
}

data class UpdateProfileRequest(
    val name: String,
    val surname: String,
    val email: String,
    val phoneNumber: String?,
    val address: String?,
    val city: String?,
    val country: String?,
)
