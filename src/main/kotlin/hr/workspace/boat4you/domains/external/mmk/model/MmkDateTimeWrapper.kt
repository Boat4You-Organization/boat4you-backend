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
        // MMK swagger DateTime example is `"2019-01-01T00:00:00"` — no
        // fractional seconds. ISO_LOCAL_DATE_TIME produces nanos when the
        // backing LocalDateTime carries them (createOption sets endDate
        // via LocalTime.MAX = 23:59:59.999999999), and MMK silently
        // rejects those with a generic "Yacht not available in period."
        // instead of a parse error. Drop nanos before formatting.
        return value?.withNano(0)?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
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
