package hr.workspace.boat4you.domains.external.mmk.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import java.text.ParseException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MmkDateTimeWrapper(
    p1: String,
) {
    @JsonIgnore
    val value: LocalDateTime?

    @JsonValue
    fun getFormattedDate(): String? {
        return value?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    init {
        value = parse(p1)
    }

    companion object {
        val READ_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun parse(dateTime: String): LocalDateTime {
            try {
                return LocalDateTime.parse(dateTime, READ_FORMATTER)
            } catch (_: RuntimeException) {
            }

            throw ParseException("Error in parsing MMK yyyy-MM-dd HH:mm:ss, value: $dateTime", 0)
        }
    }
}
