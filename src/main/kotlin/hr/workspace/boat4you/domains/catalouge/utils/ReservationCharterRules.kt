package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.CharterType

/**
 * The charter type a RESERVATION shows (Mario 21.10.2026: "GULET JE UVIJEK CREWED"), in this order:
 *
 * 1. a gulet ([GuletRules.isGulet]) is CREWED, whatever the partner tagged the offer (prod 21.10.2026: 97 future gulet
 *    offers carry BAREBOAT) and whether or not a skipper extra is booked;
 * 2. a crewed offer product (CREWED, or ALL_INCLUSIVE / CRUISE, crewed as in [OfferCharterRules]) is CREWED;
 * 3. a booked extra whose name contains "skipper" (any case) is SKIPPERED;
 * 4. otherwise BAREBOAT (also when the offer has no product, as before).
 *
 * Used by the charter agreement PDF ([kind], so also the confirmation e-mail it is attached to), my-bookings and the
 * admin reservation detail ([displayType]). Partner data stays as it is (offer.product, yacht_charter_type, extras,
 * prices): only what a reservation shows reads it this way.
 */
object ReservationCharterRules {
    private val CREWED_PRODUCTS = setOf(CharterType.CREWED, CharterType.ALL_INCLUSIVE, CharterType.CRUISE)

    /** What the charter agreement says, with its label on page 1 ("Charter type"). */
    enum class Kind(
        val label: String,
    ) {
        BAREBOAT("Bareboat charter"),
        SKIPPERED("Skippered charter"),
        CREWED("Crewed charter"),
    }

    /**
     * @param product the offer's product (reservation_view.charter_type = offer.product)
     * @param extraNames the booked extras' stored names (reservation_extras.name)
     */
    fun kind(
        gulet: Boolean,
        product: CharterType?,
        extraNames: Collection<String?>,
    ): Kind =
        when {
            gulet || product in CREWED_PRODUCTS -> Kind.CREWED
            extraNames.any { it?.contains("skipper", ignoreCase = true) == true } -> Kind.SKIPPERED
            else -> Kind.BAREBOAT
        }

    /**
     * The reservation DTOs' charterType (my-bookings, admin): a gulet's is CREWED (its ALL_INCLUSIVE / CRUISE stay, they
     * are crewed); every other boat keeps the offer's product (the enum has no SKIPPERED).
     */
    fun displayType(
        product: CharterType?,
        gulet: Boolean,
    ): CharterType? = if (gulet && product !in CREWED_PRODUCTS) CharterType.CREWED else product
}
