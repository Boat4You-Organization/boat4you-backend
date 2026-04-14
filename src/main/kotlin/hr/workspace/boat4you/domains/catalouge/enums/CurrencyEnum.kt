package hr.workspace.boat4you.domains.catalouge.enums

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.users.jpa.UserEntity

enum class CurrencyEnum(
    val value: String,
) {
    EUR("EUR"),
    USD("USD"),
    GBP("GBP"),
    CAD("CAD"),
    AUD("AUD"),
    CHF("CHF"),
    ARS("ARS"),
    ZAR("ZAR"),
    BRL("BRL"),
    NOK("NOK"),
    CZK("CZK"),
    DKK("DKK"),
    ILS("ILS"),
    SGD("SGD"),
    NZD("NZD"),
    SEK("SEK"),
    ;

    companion object {
        fun forValue(value: String): CurrencyEnum? {
            return entries.find { it -> it.value == value }
        }

        fun getCurrency(
            curr: String?,
            user: UserEntity?,
        ): CurrencyEnum {
            return if (curr != null) {
                forValue(curr)
                    ?: throw ParameterValidationException(mapOf("currency" to "Currency not provided"))
            } else if (user != null) {
                if (user.currency != null) {
                    forValue(user.currency!!.name)
                        ?: throw ParameterValidationException(mapOf("currency" to "Currency not provided"))
                } else {
                    EUR
                }
            } else {
                EUR
            }
        }
    }
}
