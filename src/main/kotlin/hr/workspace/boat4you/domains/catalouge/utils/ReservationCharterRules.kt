package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.CharterType

/**
 * The charter type a RESERVATION shows (Mario 21.10.2026: "GULET JE UVIJEK CREWED").
 *
 * The charter agreement ([kind]) changes only for a gulet (Mario 21.10.2026: "ugovor ne trebaš sada dirat"):
 *
 * 1. a gulet ([GuletRules.isGulet]) is CREWED, whatever the partner tagged the offer (prod 21.10.2026: 97 future gulet
 *    offers carry BAREBOAT) and whether or not a skipper extra is booked;
 * 2. every other boat as before this rule, whatever the offer's product (also CREWED / ALL_INCLUSIVE / CRUISE): a booked
 *    extra whose name contains "skipper" (any case) is SKIPPERED, otherwise BAREBOAT.
 *
 * The reservation DTOs ([displayType], my-bookings and the admin reservation detail): a gulet's is CREWED, every other
 * boat keeps the offer's product. The agreement PDF is also attached to the confirmation e-mail. Partner data stays as
 * it is (offer.product, yacht_charter_type, extras, prices): only what a reservation shows reads it this way.
 */
object ReservationCharterRules {
    private val CREWED_PRODUCTS = OfferCharterRules.CREWED_PRODUCTS

    /** What the charter agreement says, with its label on page 1 ("Charter type"). */
    enum class Kind(
        val label: String,
    ) {
        BAREBOAT("Bareboat charter"),
        SKIPPERED("Skippered charter"),
        CREWED("Crewed charter"),
    }

    /** @param extraNames the booked extras' stored names (reservation_extras.name) */
    fun kind(
        gulet: Boolean,
        extraNames: Collection<String?>,
    ): Kind =
        when {
            gulet -> Kind.CREWED
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
