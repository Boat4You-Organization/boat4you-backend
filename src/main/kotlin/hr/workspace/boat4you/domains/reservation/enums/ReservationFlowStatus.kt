package hr.workspace.boat4you.domains.reservation.enums

enum class ReservationFlowStatus(
    val value: Int,
) {
    UNKNOWN(0),
    IN_PROGRESS(1),
    DONE(2),
    ABANDONED(3),
}
