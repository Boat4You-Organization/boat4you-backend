package hr.workspace.boat4you.domains.external.nausys.model

import java.text.ParseException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class NauSysDateTimeWrapper(
    p1: String,
) {
    val value: LocalDateTime?

    init {
        value = parse(p1)
    }

    companion object {
        private val patterns =
            setOf(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"), DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))

        fun parse(dateTime: String): LocalDateTime {
            for (pattern in patterns) {
                try {
                    return LocalDateTime.parse(dateTime, pattern)
                } catch (_: RuntimeException) {
                }
            }
            throw ParseException("Error in parsing Nausys dd.MM.yyyy HH:mm format, value: $dateTime", 0)
        }
    }
}
