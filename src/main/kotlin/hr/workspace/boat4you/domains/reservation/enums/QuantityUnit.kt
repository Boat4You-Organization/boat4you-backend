package hr.workspace.boat4you.domains.reservation.enums

import org.openapitools.client.mmk.model.QuantityUnitEnum

enum class QuantityUnit(
    val value: Int,
) {
    UNKNOWN(0),
    EMPTY(1),
    PIECES(2),
    METER(3),
    CENTIMETER(4),
    MILIMETER(5),
    SQUARE_METER(6),
    SQUARE_CENTIMETER(7),
    SQUARE_MILIMETER(8),
    LITER(9),
    KILOGRAM(10),
    GRAM(11),
    HOURS(12),
    MINUTES(13),
    PERCENTAGE(14),
    PER_NIGHT(15),
    PER_BOOKING(16),
    PER_BOOKING_PERSON(17),
    PER_BOAT(18),
    PER_WEEK(19),
    PER_WEEK_PERSON(20),
    PER_NIGHT_PERSON(21),
    ;

    companion object {
        fun fromMmkValue(value: QuantityUnitEnum?): QuantityUnit {
            return when (value) {
                QuantityUnitEnum.EMPTY -> EMPTY
                QuantityUnitEnum.PIECES -> PIECES
                QuantityUnitEnum.METER -> METER
                QuantityUnitEnum.CENTIMETER -> CENTIMETER
                QuantityUnitEnum.MILIMETER -> MILIMETER
                QuantityUnitEnum.SQUARE_METER -> SQUARE_METER
                QuantityUnitEnum.SQUARE_CENTIMETER -> SQUARE_CENTIMETER
                QuantityUnitEnum.SQUARE_MILIMETER -> SQUARE_MILIMETER
                QuantityUnitEnum.LITER -> LITER
                QuantityUnitEnum.KILOGRAM -> KILOGRAM
                QuantityUnitEnum.GRAM -> GRAM
                QuantityUnitEnum.HOURS -> HOURS
                QuantityUnitEnum.MINUTES -> MINUTES
                QuantityUnitEnum.PERCENTAGE -> PERCENTAGE
                else -> UNKNOWN
            }
        }

        fun fromNausysValue(value: Long?): QuantityUnit {
            return when (value) {
                51L -> PER_NIGHT // per day in nausys
                120982L -> PER_NIGHT
                54L -> PER_BOOKING
                1167647L -> PER_BOOKING // per service ???
                109923L -> PER_BOOKING // per booking / crew
                1332060L -> PER_BOOKING_PERSON // per person/per course
                125575L -> PER_BOAT // per boat
                52L -> PER_BOOKING_PERSON // per person
                1L -> PER_WEEK // per week
                101457L -> PER_WEEK // per week + food
                37265719L -> PER_WEEK_PERSON // per guest/week
                56L -> PER_WEEK_PERSON // per person/week
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
    }
}
