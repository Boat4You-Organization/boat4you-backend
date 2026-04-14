package hr.workspace.boat4you.domains.external.nausys.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import java.text.ParseException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class NauSysDateWrapper(
    p1: String,
) {
    private val formatter = DATE_FORMATTER

    @JsonIgnore
    val value: LocalDate?

    @JsonValue
    fun getFormattedDate(): String? {
        return value?.format(formatter)
    }

    init {
        try {
            value = LocalDate.parse(p1, formatter)
        } catch (e: ParseException) {
            throw ParseException("Error in parsing Nausys dd.MM.yyyy format, value: $p1", 0)
        }
    }

    companion object {
        val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
