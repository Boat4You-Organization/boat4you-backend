package hr.workspace.boat4you.domains.users.controllers

import hr.workspace.boat4you.domains.gdpr.dto.UserDataExportDto
import hr.workspace.boat4you.domains.gdpr.jpa.GdprAuditLogEntity
import hr.workspace.boat4you.domains.gdpr.services.DataExportService
import hr.workspace.boat4you.domains.gdpr.services.GdprAuditService
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.GetAllUsersQuery
import hr.workspace.boat4you.domains.users.services.UserInviteService
import hr.workspace.boat4you.domains.users.services.UserMutationService
import hr.workspace.boat4you.domains.users.services.UserQueryingService
import hr.workspace.boat4you.domains.users.services.UserRegistrationService
import kotlin.jvm.optionals.getOrElse
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.checkAccessForAdminOrSelf
import hr.workspace.boat4you.security.getAuthenticatedUserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

@Controller
@Validated
internal class UserController(
    private val userQueryingService: UserQueryingService,
    private val userMutationService: UserMutationService,
    private val userInviteService: UserInviteService,
    private val dataExportService: DataExportService,
    private val gdprAuditService: GdprAuditService,
    private val userRepository: UserRepository,
    private val userRegistrationService: UserRegistrationService,
    private val reservationFlowRepository: ReservationFlowRepository,
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
                birthday = body.birthday,
            ),
        )
    }

    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN')")
    override fun inviteUsers(ids: List<Long>): ResponseEntity<Unit> {
        // Admin-driven invite: always render in English regardless of recipient
        // language. Mario rule (3.5.2026): admin-triggered invites are
        // operational/team comms — guest-facing emails (booking flow) use
        // user.language (captured from front-end Accept-Language).
        return ResponseEntity(userInviteService.inviteUsers(ids, forceEnglish = true), HttpStatus.OK)
    }

    /**
     * GDPR right-to-erasure (Article 17) — customer-initiated.
     *
     * Self-service endpoint: authenticated user requests deletion of their
     * own account. Anonymizes PII in `users` row, revokes all auth tokens
     * (immediate logout on next request), drops role assignments. Past
     * bookings (`reservation_flow.user_id` FK) are intentionally preserved
     * — admin retains full booking history for partner-agency
     * reconciliation + accounting obligations. The user identity is just
     * detached from PII.
     *
     * No body required: identity comes from the JWT. The frontend should
     * trigger logout immediately after a 200 response.
     */
    @DeleteMapping("/users/me")
    fun deleteMyAccount(request: HttpServletRequest): ResponseEntity<Unit> {
        val userId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
            ?: throw AccessDeniedException("User is not authenticated")
        userMutationService.softDeleteForGdpr(userId)
        gdprAuditService.log(
            userId = userId,
            action = GdprAuditLogEntity.ACTION_DELETE_ACCOUNT,
            request = request,
            notes = "PII anonymized; reservations preserved per Mario decision 1.5.2026.",
        )
        return ResponseEntity(HttpStatus.OK)
    }

    /**
     * Self-service "account at a glance" — used by /my-profile to render the
     * Account info readout (member-since, last login, total bookings, email
     * verification status). Computed live; no caching because this is loaded
     * once per profile view, volume is trivial.
     */
    @GetMapping("/users/me/account-info")
    fun getMyAccountInfo(): ResponseEntity<MyAccountInfoResponse> {
        val userId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
            ?: throw AccessDeniedException("User is not authenticated")
        val user = userRepository.findById(userId).getOrElse { throw UserDoesNotExistException() }
        val totalBookings = reservationFlowRepository.findAllByUserId(userId).size
        return ResponseEntity.ok(
            MyAccountInfoResponse(
                memberSince = user.created,
                lastLoginAt = user.lastLoginAt,
                totalBookings = totalBookings,
                emailVerified = user.registrationStatus == UserRegistrationStatusEnum.REGISTERED,
                provider = user.provider,
                passwordSet = user.passwordSet,
            ),
        )
    }

    /**
     * Trigger a fresh email-verification code mail. Reuses the existing
     * `UserRegistrationService.resendEmailVerificationCode` which throws if
     * the account is already verified or the rate limit (60s) hasn't passed.
     */
    @PostMapping("/users/me/resend-verification")
    fun resendMyVerification(): ResponseEntity<Unit> {
        val userId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
            ?: throw AccessDeniedException("User is not authenticated")
        userRegistrationService.resendEmailVerificationCode(userId)
        return ResponseEntity.ok().build()
    }

    /**
     * GDPR Article 20 — right to data portability. Self-service export of
     * everything the system holds about the authenticated user, returned
     * as a JSON file (`Content-Disposition: attachment`). Customer can
     * use the file to take their data elsewhere or just review what we
     * have on file.
     */
    @GetMapping("/users/me/export")
    fun exportMyData(request: HttpServletRequest): ResponseEntity<UserDataExportDto> {
        val userId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
            ?: throw AccessDeniedException("User is not authenticated")
        val export = dataExportService.exportForUser(userId)
        gdprAuditService.log(
            userId = userId,
            action = GdprAuditLogEntity.ACTION_EXPORT_DATA,
            request = request,
            notes = "Reservations: ${export.reservations.size}; custom offers: ${export.customOffers.size}.",
        )
        val filename = DataExportService.filenameFor(userId, export.exportedAt)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(export)
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
    val birthday: java.time.LocalDate? = null,
)

/**
 * Compact profile readout for /my-profile "Account info" section.
 */
data class MyAccountInfoResponse(
    val memberSince: java.time.Instant,
    val lastLoginAt: java.time.Instant?,
    val totalBookings: Int,
    val emailVerified: Boolean,
    val provider: String?,
    val passwordSet: Boolean,
)
