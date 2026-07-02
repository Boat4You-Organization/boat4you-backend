package hr.workspace.boat4you.domains.reservation.enums

/**
 * Identity of an admin-uploaded reservation document — drives the label and
 * icon the customer sees in the my-bookings "Travel documents" section
 * ("Boarding pass" instead of `base_info_final.pdf`). [OTHER] is the default
 * and the value of every document uploaded before the type existed.
 */
enum class ReservationDocumentType {
    BOARDING_PASS,
    CREW_LIST,
    CONTRACT,
    OTHER,
    ;

    companion object {
        /** Lenient parse for the upload request param — unknown/blank → [OTHER]. */
        fun fromParam(value: String?): ReservationDocumentType =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: OTHER
    }
}
