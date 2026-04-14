package hr.workspace.boat4you.domains.catalouge.enums

enum class SimpleOfferStatus {
    FREE,
    OPTION,
    UNAVAILABLE,
    ;

    companion object {
        fun fromOfferStatus(value: OfferStatus?): SimpleOfferStatus {
            return when (value) {
                OfferStatus.FREE -> FREE
                OfferStatus.OPTION -> OPTION
                OfferStatus.OPTION_WAITING -> OPTION
                else -> UNAVAILABLE
            }
        }
    }
}
