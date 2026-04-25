package hr.workspace.boat4you.domains.catalouge.enums

enum class ExtrasUnitType(
    val value: Int,
) {
    UNKNOWN(0),
    AMOUNT(1),
    PERCENTAGE(2),
    PER_WEEK(3),
    PER_WEEK_PERSON(4),
    PER_BOOKING(5),
    PER_BOOKING_PERSON(6),
    PER_NIGHT(7),
    PER_NIGHT_PERSON(8),
    PER_BOAT(9),
    // V1_58: Nausys/MMK partners report richer per-unit codes (per hour, per
    // piece, per litre, ...). Historically the mapper collapsed them all to
    // UNKNOWN, which surfaced as "Not specified" on the yacht page even
    // though the price itself is perfectly known. These variants are now
    // carried through for display; pricing treats them as one-time amounts
    // (see PriceCalculations.calculateExtrasPrice). New values MUST be
    // appended at the end — the column is @Enumerated (ORDINAL).
    PER_HOUR(10),
    PER_PIECE(11),
    PER_LITRE(12),
    PER_MEAL(13),
    PER_NM(14),
    PER_PACK(15),
    PER_PET(16),
    PER_SET(17),
    PER_BED(18),
    PER_TANK(19),
    PER_TON(20),
    PER_TRIP(21),
    PER_GB(22),
    PER_BOTTLE(23),
    PER_CABIN(24),
    PER_LICENCE(25),
    ;

    companion object {
        // same values in QuantityUnit enum
        fun fromNausysValue(value: Long?): ExtrasUnitType {
            return when (value) {
                54L -> PER_BOOKING // per booking
                1167647L -> PER_BOOKING // per service ???
                109923L -> PER_BOOKING // per booking / crew
                1332060L -> PER_BOOKING_PERSON // per person/per course
                125575L -> PER_BOAT // per boat
                52L -> PER_BOOKING_PERSON // per person
                1L -> PER_WEEK // per week
                101457L -> PER_WEEK // per week + food
                37265719L -> PER_WEEK_PERSON // per guest/week
                56L -> PER_WEEK_PERSON // per person/week
                51L -> PER_NIGHT // per day
                120982L -> PER_NIGHT // per night
                55L -> PER_NIGHT // per day + food
                17347059L -> PER_NIGHT_PERSON // per guest/day
                27279139L -> PER_NIGHT_PERSON // per guest/night
                53L -> PER_NIGHT_PERSON // per person/night
                478194L -> PER_NIGHT_PERSON // per person/day
                958014L -> PER_HOUR // per hour
                528326L -> PER_HOUR // per running hour
                974076L -> PER_LICENCE // per licence
                865925L -> PER_LITRE // per litre
                1202520L -> PER_MEAL // per meal
                556692L -> PER_NM // per nautical mile
                604502L -> PER_PACK // per pack
                524553L -> PER_PET // per pet
                112719L -> PER_PIECE // per piece
                101939L -> PER_SET // per set
                1091408L -> PER_BED // per single bed
                1176515L -> PER_TANK // per tank
                1058031L -> PER_TON // per ton
                557481L -> PER_TRIP // round trip
                940725L -> PER_GB // per GB
                1312513L -> PER_BOTTLE // per bottle
                525147L -> PER_CABIN // per cabin
                // Genuinely ambiguous units kept as UNKNOWN — pricing for
                // multi-week / one-way / half-hour / per-crew-change doesn't
                // fit the one-shot charter flow cleanly.
                1174949L -> UNKNOWN // half an hour
                101767L -> UNKNOWN // one-way
                126869L -> UNKNOWN // one-way/person
                485612L -> UNKNOWN // per two weeks
                485613L -> UNKNOWN // per three weeks
                555737L -> UNKNOWN // per four weeks and more
                23526024L -> UNKNOWN // per crew change
                else -> UNKNOWN
            }
        }

        fun fromMmkValue(value: String?): ExtrasUnitType {
            // MMK actually transmits underscore-delimited identifiers
            // (`per_week`, `per_booking_person`, `per_night`, ...). Older
            // spec docs and early integrations used spaces, so we normalize
            // both shapes to the same tokens and match on the normalized
            // form. Without this, ~72k MMK-sourced yacht_extras rows fall
            // through to UNKNOWN and the UI renders "Not specified" next to
            // real prices.
            val normalized = value?.lowercase()?.replace('_', ' ')?.trim()
            return when (normalized) {
                "amount" -> AMOUNT
                "percentage" -> PERCENTAGE
                "per week",
                // "per week started" = rounded-up fractional week (Nausys
                // legacy). Price basis is still one week, just charged when
                // partial — safe to group under PER_WEEK for display.
                "per week started" -> PER_WEEK
                "per week person",
                "per week started person" -> PER_WEEK_PERSON
                "per booking" -> PER_BOOKING
                "per booking person" -> PER_BOOKING_PERSON
                "per night",
                "per day" -> PER_NIGHT
                "per night person",
                "per day person" -> PER_NIGHT_PERSON
                "per hour" -> PER_HOUR
                "per piece" -> PER_PIECE
                "per litre", "per liter" -> PER_LITRE
                "per meal" -> PER_MEAL
                "per nautical mile" -> PER_NM
                "per pack" -> PER_PACK
                "per pet" -> PER_PET
                "per set" -> PER_SET
                "per bed", "per single bed" -> PER_BED
                "per tank" -> PER_TANK
                "per ton" -> PER_TON
                "round trip", "per trip" -> PER_TRIP
                "per gb" -> PER_GB
                "per bottle" -> PER_BOTTLE
                "per cabin" -> PER_CABIN
                "per licence", "per license" -> PER_LICENCE
                else -> UNKNOWN
            }
        }
    }
}
