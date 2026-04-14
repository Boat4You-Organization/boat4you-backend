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
                958014L -> UNKNOWN // per hour
                974076L -> UNKNOWN // per licence
                865925L -> UNKNOWN // per litre
                1202520L -> UNKNOWN // per meal
                556692L -> UNKNOWN // per nautical mile
                604502L -> UNKNOWN // per pack
                524553L -> UNKNOWN // per pet
                112719L -> UNKNOWN // per piece
                528326L -> UNKNOWN // per running hour
                101939L -> UNKNOWN // per set
                1091408L -> UNKNOWN // per single bed
                1176515L -> UNKNOWN // per tank
                1058031L -> UNKNOWN // per ton
                557481L -> UNKNOWN // round trip
                1174949L -> UNKNOWN // half an hour
                101767L -> UNKNOWN // one-way
                126869L -> UNKNOWN // one-way/person
                485612L -> UNKNOWN // per two weeks
                485613L -> UNKNOWN // per three weeks
                555737L -> UNKNOWN // per four weeks and more
                940725L -> UNKNOWN // per GB
                1312513L -> UNKNOWN // per bottle
                525147L -> UNKNOWN // per cabin
                23526024L -> UNKNOWN // per crew change
                else -> UNKNOWN
            }
        }

        fun fromMmkValue(value: String?): ExtrasUnitType {
            return when (value?.lowercase()) {
                "amount" -> AMOUNT
                "percentage" -> PERCENTAGE
                "per week" -> PER_WEEK
                "per week person" -> PER_WEEK_PERSON
                "per booking" -> PER_BOOKING
                "per booking person" -> PER_BOOKING_PERSON
                "per night" -> PER_NIGHT
                "per night person" -> PER_NIGHT_PERSON
                "per day" -> PER_NIGHT // "per day"
                else -> UNKNOWN
            }
        }
    }
}
