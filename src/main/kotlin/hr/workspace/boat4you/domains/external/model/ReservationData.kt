package hr.workspace.boat4you.domains.external.model

import java.time.LocalDateTime

data class ReservationData(
    val startDate: LocalDateTime,
    val endDate: LocalDateTime,
    val externalYachtId: Long,
    val externalAgencyId: Long,
    val name: String,
    val surname: String,
    val selectedServices: List<Long>?,
    val selectedEquipment: List<Long>?,
) {
    fun getFullName(): String {
        return "$name $surname"
    }
}
