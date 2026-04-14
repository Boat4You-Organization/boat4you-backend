package hr.workspace.boat4you.common.services

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Root
import org.springframework.data.jpa.domain.Specification
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Optional
import kotlin.random.Random

private const val DEFAULT_PASSWORD_LENGTH = 6

fun <T : Any, R : Any> Optional<T>.ifNotNull(block: (T) -> R): R? {
    if (this.isPresent) {
        return block.invoke(this.get())
    } else {
        return null
    }
}

fun <T : Any, R : Any> Optional<T>.ifNotNullElseThrow(
    block: (T) -> R,
    ex: Exception,
): R {
    if (this.isPresent) {
        return block.invoke(this.get())
    } else {
        throw ex
    }
}

fun <T : Any> T.nullIfSensitive(isSensitive: Boolean): T? {
    if (isSensitive) {
        return null
    } else {
        return this
    }
}

fun getRandomString(length: Int): String {
    val charset = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
    return (1..length)
        .map { charset.random() }
        .joinToString("")
}

fun getRandomNumericalString(length: Int): String {
    val charset = "0123456789"
    return (1..length)
        .map { charset.random() }
        .joinToString("")
}

fun getRandomLong(
    from: Long = 0,
    to: Long = 1000,
) = Random.nextLong(from, to)

fun getRandomPassword() = getRandomString(DEFAULT_PASSWORD_LENGTH)

fun String?.nonBlankOrNull(): String? = if (this.isNullOrBlank()) null else this

fun <T> initSpecification(spec: Specification<T>?): Specification<T> {
    return spec ?: Specification { root: Root<T>?, query: CriteriaQuery<*>?, builder: CriteriaBuilder? -> null }
}

class TwoDecimalSerializer : JsonSerializer<BigDecimal>() {
    override fun serialize(
        value: BigDecimal,
        gen: JsonGenerator,
        serializers: SerializerProvider,
    ) {
        gen.writeNumber(value.setScale(2, RoundingMode.HALF_UP))
    }
}

fun extractAndMultiplyNumbers(input: String): Int? {
    // Find all numbers in the string using regex
    val numbers =
        Regex("""\d+""")
            .findAll(input)
            .map { it.value.toInt() }
            .toList()

    return when (numbers.size) {
        0 -> null // No numbers found
        1 -> numbers[0] // Single number found
        2 -> numbers[0] * numbers[1] // Two numbers found, multiply them
        else -> null // More than 2 numbers found
    }
}

fun parseYachtSearchViewLocationName(locationName: String?): LocationDto {
    if (locationName == null) {
        return LocationDto(
            id = null,
            name = null,
            countryCode = null,
        )
    }
    val firstDash = locationName.indexOf('-')
    val lastDash = locationName.lastIndexOf('-')

    val id = locationName.substring(0, firstDash)
    val countryCode = locationName.substring(lastDash + 1)
    val name = locationName.substring(firstDash + 1, lastDash)
    return LocationDto(
        id = id,
        name = name,
        countryCode = countryCode,
    )
}
