package hr.workspace.boat4you.domains.catalouge.enums

/**
 * Where & when an extra is paid for. Replaces the overloaded `payableInBase`
 * boolean which the historical code mapped to a single "Paid at marina"
 * customer-facing label — that conflated four genuinely different payment
 * flows (advance to operator, on-site at marina, with the booking total,
 * fully included). See DEPLOY_NOTES section AG and the 23.4.2026 audit.
 *
 * Classification rules (V1_57 backfill + sync mappers):
 *  - INCLUDED            → priceEur == 0
 *  - WITH_BOOKING        → payableInBase == false (paid alongside the
 *                          base reservation amount at booking time)
 *  - ON_SITE             → name regex matches "tourist tax|transit log|
 *                          fuel|gas|mooring|marina fee|port fee|harbor"
 *                          (paid in cash/card at the charter base)
 *  - ADVANCE_TO_OPERATOR → default for payableInBase=true rows that are
 *                          NOT on-site (Skipper, Hostess, Cook, APA,
 *                          equipment rental — all bank-transferred to
 *                          the operator before embarkation)
 */
enum class ExtraPaymentType(
    val value: Int,
) {
    INCLUDED(0),
    WITH_BOOKING(1),
    ADVANCE_TO_OPERATOR(2),
    ON_SITE(3),
    ;

    companion object {
        // Single source of truth for the on-site name regex — used both by
        // the V1_57 backfill (in raw SQL) and the MMK sync mapper. Keep the
        // SQL pattern in V1_57__add_extra_payment_type.sql in sync.
        //
        // Nausys no longer uses this regex (23.4.2026): it transmits
        // calculationType directly with three values (SEPARATE_PAYMENT /
        // ADVANCE_PAYMENT / INCLUDED_IN_PRICE), so `fromNausysCalculationType`
        // below is the authoritative classifier for Nausys-sourced rows.
        private val ON_SITE_NAME_REGEX = Regex(
            "tourist tax|transit log|fuel|gas|mooring|marina fee|port fee|harbor",
            RegexOption.IGNORE_CASE,
        )

        /**
         * MMK classifier. Still relies on `payableInBase` + a name regex
         * for on-site detection because MMK doesn't expose an equivalent
         * of Nausys's three-state `calculationType`. When/if we find a
         * stronger MMK signal, replace this with a direct mapping.
         *
         * @param name  Raw extras name from the partner.
         * @param price Raw price the partner sent (BigDecimal). Null/0 → INCLUDED.
         * @param payableInBase Partner's flag. True = "settled alongside base"
         *                     in their model; we further refine here.
         */
        fun classify(
            name: String?,
            price: java.math.BigDecimal?,
            payableInBase: Boolean,
        ): ExtraPaymentType {
            if (price == null || price.signum() == 0) return INCLUDED
            if (!payableInBase) return WITH_BOOKING
            if (name != null && ON_SITE_NAME_REGEX.containsMatchIn(name)) return ON_SITE
            return ADVANCE_TO_OPERATOR
        }

        /**
         * Nausys classifier. Nausys sends an explicit three-state field
         * `calculationType` on every extra/service/equipment row — we use
         * it directly, no name heuristics needed. Mapping:
         *   SEPARATE_PAYMENT   → ON_SITE             (paid at the marina)
         *   ADVANCE_PAYMENT    → ADVANCE_TO_OPERATOR (wired to operator pre-embark)
         *   INCLUDED_IN_PRICE  → WITH_BOOKING        (settled with base price)
         * Zero price still wins and returns INCLUDED regardless of the field.
         * Unknown / null calculationType falls back to ADVANCE_TO_OPERATOR
         * (the safest "opaque extra charge" bucket — surfaces in UI as a
         * flagged item rather than silently collapsing to INCLUDED).
         */
        fun fromNausysCalculationType(
            calculationType: String?,
            price: java.math.BigDecimal?,
        ): ExtraPaymentType {
            if (price == null || price.signum() == 0) return INCLUDED
            return when (calculationType) {
                "SEPARATE_PAYMENT" -> ON_SITE
                "ADVANCE_PAYMENT" -> ADVANCE_TO_OPERATOR
                "INCLUDED_IN_PRICE" -> WITH_BOOKING
                else -> ADVANCE_TO_OPERATOR
            }
        }

        // MMK-specific name patterns. Unlike Nausys there's no 3-state
        // payment field, so we fall back to two regex buckets on top of
        // `payableInBase`. Crew / advance-package names go to the
        // operator in advance; everything else with payableInBase=false
        // is collected at the marina (Princess Karla raw data shows SUP /
        // Kayak / Wakeboard / Seabob / Fun Pack / Safety net all come as
        // payableInBase=false yet are paid on handover).
        private val MMK_ADVANCE_NAME_REGEX = Regex(
            """\b(
              captain | chef | cook | hostess | skipper | stewardess | crew
              | masseuse | nanny | yoga\b.*instructor | fitness\b.*instructor | instructor
              | apa | provisioning
              | comfort\s*pack | welcome\s*pack | basic\s*pack | charter\s*pack | starter\s*pack
              | flex\s*plan | flexible\s*(?:cancellation|booking)
              | deposit\s*waiver | deposit\s*insurance | damage\s*waiver
            )\b""".trimIndent().replace("\n", "").replace(" ", ""),
            RegexOption.IGNORE_CASE,
        )

        /**
         * MMK classifier. MMK only sends `payableInBase` (Boolean) — no
         * analogue of Nausys's three-state `calculationType`. Mapping:
         *   price == 0                          → INCLUDED
         *   payableInBase == true               → ON_SITE
         *     (obligatory fees the partner collects at the marina, e.g.
         *      tourist tax, transit log, harbor fees)
         *   payableInBase == false AND
         *       name matches MMK_ADVANCE_NAME_REGEX
         *                                       → ADVANCE_TO_OPERATOR
         *     (crew + advance packages like APA / Comfort / Flex cancel)
         *   payableInBase == false otherwise    → ON_SITE
         *     (equipment rentals, optional extras — paid on handover)
         *
         * Historical NOTE: the old generic `classify()` used to map
         * `payableInBase == false` to WITH_BOOKING by default, which was
         * wrong for MMK — partner doesn't bundle those into the base
         * price. Keep MMK behavior isolated here.
         */
        fun fromMmkPayableInBase(
            name: String?,
            price: java.math.BigDecimal?,
            payableInBase: Boolean,
        ): ExtraPaymentType {
            if (price == null || price.signum() == 0) return INCLUDED
            if (payableInBase) return ON_SITE
            if (name != null && MMK_ADVANCE_NAME_REGEX.containsMatchIn(name)) {
                return ADVANCE_TO_OPERATOR
            }
            return ON_SITE
        }
    }
}
