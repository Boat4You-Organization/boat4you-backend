package hr.workspace.boat4you.domains.catalouge.enums

/**
 * Where & when an extra is paid for. Replaces the overloaded `payableInBase`
 * boolean which the historical code mapped to a single "Paid at marina"
 * customer-facing label — that conflated four genuinely different payment
 * flows (advance to operator, on-site at marina, with the booking total,
 * fully included). See DEPLOY_NOTES section AG and the 23.4.2026 audit.
 *
 * Classification rules (V1_57 backfill + sync mappers):
 *  - INCLUDED / ON_SITE  → priceEur == 0, see [zeroPrice] (D6, 14.10.2026)
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
         * Payment type of an extra the partner sends without a price (null or 0), D6 (Mario 14.10.2026).
         * An obligatory one is part of the charter: [INCLUDED] ("Included"). An optional one is a service the
         * customer asks for and the operator prices and collects at the marina: [ON_SITE] ("On request, paid
         * in the marina") - but only with the runtime switch `EXTRAS_ZERO_OPTIONAL_ON_SITE=ON`
         * ([optionalOnSite]). With the switch OFF (the default) every unpriced extra stays [INCLUDED], as before.
         * Both types sit outside our online payment, so totals never change.
         */
        fun zeroPrice(
            obligatory: Boolean,
            optionalOnSite: Boolean,
        ): ExtraPaymentType = if (obligatory || !optionalOnSite) INCLUDED else ON_SITE

        private fun isUnpriced(price: java.math.BigDecimal?): Boolean = price == null || price.signum() == 0

        /**
         * MMK classifier. Still relies on `payableInBase` + a name regex
         * for on-site detection because MMK doesn't expose an equivalent
         * of Nausys's three-state `calculationType`. When/if we find a
         * stronger MMK signal, replace this with a direct mapping.
         *
         * @param name  Raw extras name from the partner.
         * @param price Raw price the partner sent (BigDecimal). Null/0 → [zeroPrice].
         * @param payableInBase Partner's flag. True = "settled alongside base"
         *                     in their model; we further refine here.
         * @param obligatory / optionalOnSite See [zeroPrice]. The defaults keep the historical INCLUDED, which the
         *                     read path of booked extras without a persisted type relies on (history never changes).
         */
        fun classify(
            name: String?,
            price: java.math.BigDecimal?,
            payableInBase: Boolean,
            obligatory: Boolean = false,
            optionalOnSite: Boolean = false,
        ): ExtraPaymentType {
            if (isUnpriced(price)) return zeroPrice(obligatory, optionalOnSite)
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
         * Zero price is decided by [zeroPrice] regardless of the field, except for
         * a percentage item ([isPercentage]): NauSys sends its amount as a percent,
         * which we store as price 0 (PriceCalculations has no percentage unit), so
         * the 0 is not "free" - with the D6 switch ON it is classified by
         * calculationType and never reads "Included". With the switch OFF it stays
         * INCLUDED as before.
         * Unknown / null calculationType falls back to ADVANCE_TO_OPERATOR
         * (the safest "opaque extra charge" bucket — surfaces in UI as a
         * flagged item rather than silently collapsing to INCLUDED).
         */
        fun fromNausysCalculationType(
            calculationType: String?,
            price: java.math.BigDecimal?,
            obligatory: Boolean,
            optionalOnSite: Boolean,
            isPercentage: Boolean = false,
        ): ExtraPaymentType {
            val byPercentType = isPercentage && optionalOnSite
            if (isUnpriced(price) && !byPercentType) return zeroPrice(obligatory, optionalOnSite)
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
         *   price == 0                          → [zeroPrice]
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
         * wrong for MMK YACHT-level and optional extras — partner doesn't
         * bundle those into the base price. The offer's own obligatory
         * extras are the exception: see [fromMmkOfferObligatory].
         */
        fun fromMmkPayableInBase(
            name: String?,
            price: java.math.BigDecimal?,
            payableInBase: Boolean,
            obligatory: Boolean,
            optionalOnSite: Boolean,
        ): ExtraPaymentType {
            if (isUnpriced(price)) return zeroPrice(obligatory, optionalOnSite)
            if (payableInBase) return ON_SITE
            if (name != null && MMK_ADVANCE_NAME_REGEX.containsMatchIn(name)) {
                return ADVANCE_TO_OPERATOR
            }
            return ON_SITE
        }

        /**
         * NauSys classifier for the OFFER's own `obligatoryExtras`. NauSys says
         * how each is paid through `calculationType`, and its own advance total
         * (`totalPriceWithExtras`: "total price to be paid in advance by the
         * Agency to the Fleet operator, including discounts and all extras
         * marked as advance payment") always contains the ADVANCE_PAYMENT ones:
         * on 80,691 of 87,392 future NauSys offers it equals clientPrice + those
         * items to the cent, and on none does it leave them out (26.9.2026; the
         * rest carry further taxes/percentages on top). So an obligatory
         * ADVANCE_PAYMENT extra is paid with the booking and folds into our
         * online total (Mario's rule, same as [fromMmkOfferObligatory]).
         * SEPARATE_PAYMENT stays at the base and INCLUDED_IN_PRICE is already
         * WITH_BOOKING — both unchanged, as are optional extras and yacht rows.
         * Every row here is obligatory, so an unpriced one is always INCLUDED
         * ([zeroPrice] with obligatory = true; the D6 switch never applies).
         */
        fun fromNausysOfferObligatory(
            calculationType: String?,
            price: java.math.BigDecimal?,
        ): ExtraPaymentType {
            val byType = fromNausysCalculationType(calculationType, price, obligatory = true, optionalOnSite = false)
            return if (calculationType == "ADVANCE_PAYMENT" && byType != INCLUDED) WITH_BOOKING else byType
        }

        /**
         * MMK classifier for the OFFER's own `obligatoryExtras` — the list MMK
         * attaches to a reservation when the option is placed. MMK bills every
         * one of them with `payableInBase=false` inside the reservation
         * clientPrice and payment plan, i.e. in advance with the booking:
         * clientPrice = basePrice x (1 - discount%) + sum of those items, to the
         * cent, on all 165 reservations our account held for 2026-2027
         * (checked 26.9.2026 — Additional fixed part, handling fees, APA,
         * captain, charter/comfort packs, Greek VAT alike). Mario's rule: what
         * the partner bills with the booking is paid with the booking here too,
         * so these fold into our online total. `payableInBase=true` items stay
         * outside it (collected at the base).
         *
         * Yacht-level extras keep [fromMmkPayableInBase]: MMK bills only what
         * the offer lists, and yacht rows also back the extras list shown on
         * existing bookings, which must not change.
         *
         * Every row here is obligatory, so an unpriced one is always INCLUDED
         * ([zeroPrice] with obligatory = true; the D6 switch never applies).
         */
        fun fromMmkOfferObligatory(
            price: java.math.BigDecimal?,
            payableInBase: Boolean,
        ): ExtraPaymentType {
            if (isUnpriced(price)) return zeroPrice(obligatory = true, optionalOnSite = false)
            return if (payableInBase) ON_SITE else WITH_BOOKING
        }
    }
}
