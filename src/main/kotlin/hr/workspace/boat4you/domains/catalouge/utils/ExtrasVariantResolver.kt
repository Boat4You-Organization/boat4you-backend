package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra

/**
 * Partners model ONE logical obligatory charge as several yacht-level rows that
 * differ only by duration / pax / size ("Comfort Pack", "Comfort Pack 2 weeks",
 * "Comfort Pack 3 weeks"; "Damage Waiver (SY 44'-48')" ×1/2/3 weeks; "National
 * Parks Permit (up to 4/6/8)"), all flagged obligatory. The offer feed then
 * carries exactly the variant that applies to THAT period. Merging yacht +
 * offer obligatory rows therefore listed (and charged at base) every sibling —
 * LUNA Lagoon 46 showed 600 + 700 + 800 € of Comfort Packs on a one-week
 * charter (Mario 23.8.2026: "treba biti jedan samo").
 *
 * Rule (deliberately narrow — measured 23.8.2026: 245 yachts / 21k offers, all
 * genuine siblings): a yacht-level OBLIGATORY row is superseded when it is NOT
 * itself among the anchors (no externalId / extrasKey match) but its normalized
 * name equals the normalized name of an anchor. Anchors are the obligatory rows
 * the partner attached to the offer — or, once booked, the obligatory rows
 * snapshotted on the reservation (same truth, frozen at booking time). Rows
 * whose base name has no anchor twin (Transit log, Tourist tax, Damage waiver
 * on NauSys fleets, …) are untouched — the offer feed is NOT a complete
 * obligatory list for every partner, so a blanket "offer set wins" would
 * silently drop ~3k yachts' real fees.
 *
 * Known blind spot: the normalization strips parentheticals and digits, so two
 * legitimately separate obligatory rows that differ only there ("Tourist tax
 * (adults)" / "(children)") would collide IF the partner put only one of them on
 * the offer. Not observed in the 23.8.2026 measurement; re-run the query in
 * DEPLOY_NOTES if a partner feed changes shape.
 */
object ExtrasVariantResolver {
    private val parenthetical = Regex("\\([^)]*\\)")
    private val durationWords = Regex("\\b(weeks?|wks?|days?|nights?|x)\\b")
    private val digitsAndSlashes = Regex("[0-9/]+")
    private val whitespace = Regex("\\s+")

    /** An obligatory row the partner (offer) or the booking (snapshot) vouches for. */
    data class Anchor(val name: String?, val externalId: Long?, val key: String?)

    /** "Comfort Pack 2 weeks (Final Cleaning, …)" -> "comfort pack". */
    fun normalizeVariantName(name: String?): String =
        (name ?: "")
            .lowercase()
            .replace(parenthetical, "")
            .replace(durationWords, " ")
            .replace(digitsAndSlashes, " ")
            .replace(whitespace, " ")
            .trim()

    fun supersededYachtExtraKeys(
        offerExtras: Collection<OfferExtra>,
        yachtExtras: Collection<YachtExtra>,
    ): Set<String> =
        supersededByAnchors(
            offerExtras
                .filter { it.obligatory == true }
                .map { Anchor(it.name, it.externalId, if (it.name.isNullOrBlank() && it.extrasId == null) null else it.extrasKey()) },
            yachtExtras,
        )

    /** Same rule anchored on the booking's own obligatory rows (reservation_extras snapshot). */
    fun supersededByReservation(
        reservationExtras: Collection<ReservationExtra>,
        yachtExtras: Collection<YachtExtra>,
    ): Set<String> =
        supersededByAnchors(
            reservationExtras
                .filter { it.obligatory == true }
                .map { Anchor(it.name, it.externalId, it.yachtExtrasKey) },
            yachtExtras,
        )

    /**
     * extrasKey() of every obligatory yacht-level row that is a wrong-period
     * sibling of an anchor. Empty when there are no anchors.
     */
    fun supersededByAnchors(
        anchors: Collection<Anchor>,
        yachtExtras: Collection<YachtExtra>,
    ): Set<String> {
        if (anchors.isEmpty()) return emptySet()

        val anchorExternalIds = anchors.mapNotNull { it.externalId }.toSet()
        val anchorKeys = anchors.mapNotNull { it.key }.toSet()
        val anchorBaseNames = anchors.map { normalizeVariantName(it.name) }.filter { it.isNotEmpty() }.toSet()

        return yachtExtras
            .asSequence()
            .filter { it.obligatory == true && !it.name.isNullOrBlank() }
            .filter { it.externalId == null || it.externalId !in anchorExternalIds }
            .filter { it.extrasKey() !in anchorKeys }
            .filter { normalizeVariantName(it.name) in anchorBaseNames }
            .map { it.extrasKey() }
            .toSet()
    }
}
