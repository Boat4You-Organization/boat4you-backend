package hr.workspace.boat4you.domains.external.nausys.model

import java.text.ParseException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class NauSysTimeWrapper(
    p1: String,
) {
    val value: LocalTime?

    init {
        value = parse(p1)
    }

    companion object {
        private val patterns =
            setOf(DateTimeFormatter.ofPattern("HH:mm"), DateTimeFormatter.ofPattern("HH:mm:ss"))

        fun parse(dateTime: String): LocalTime {
            for (pattern in patterns) {
                try {
                    return LocalTime.parse(dateTime, pattern)
                } catch (_: RuntimeException) {
                }
            }
            throw ParseException("Error in parsing Nausys HH:mm format, value: $dateTime", 0)
        }
    }
}
