package hr.workspace.boat4you.domains.external.model

import java.time.LocalDateTime

data class ReservationData(
    val startDate: LocalDateTime,
    val endDate: LocalDateTime,
    val externalYachtId: Long,
    val externalAgencyId: Long,
    val name: String,
    val surname: String,
    // Email + phone are REQUIRED by stricter NauSys agencies (Navigare, Dream Yacht Charter) to
    // create an option — without them createOption returns INSUFFICIENT_DATA. The booking form
    // already collects both; thread them through so the partner client record is complete. 30.6.2026.
    val email: String,
    val phone: String,
    val selectedServices: List<Long>?,
    val selectedEquipment: List<Long>?,
) {
    fun getFullName(): String {
        return "$name $surname"
    }
}
