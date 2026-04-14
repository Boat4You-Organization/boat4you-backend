package hr.workspace.boat4you.domains.reservation.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateReservationDto(
    @field:NotNull
    val yachtId: Long,
    @field:NotNull
    val offerId: Long,
    @field:Email
    @field:NotNull
    val email: String,
    @field:Size(min = 2)
    @field:NotNull
    val name: String?,
    @field:Size(min = 2)
    @field:NotNull
    val surname: String?,
    @field:Pattern(regexp = "^\\+?[0-9]{10,15}$")
    @field:NotNull
    val phoneNumber: String?,
    val specialRequest: String?,
    val selectedExtras: Set<String>?,
)
