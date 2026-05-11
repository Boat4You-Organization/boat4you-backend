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
import java.security.SecureRandom
import java.util.Optional

// F5-019: raised from 6 → 16. 6-char defaults made auto-generated user
// passwords trivially brute-forceable; 16 chars over the 62-char alphabet
// gives ~95 bits of entropy and aligns with NIST 800-63B "long unique
// random" recommendations for system-generated credentials.
private const val DEFAULT_PASSWORD_LENGTH = 16

// F5-012: crypto PRNG for any token that grants a capability (password
// fallback for invited users, email verification codes). Previously
// `kotlin.random.Random` (linear congruential, seedable from clock) was
// used, which is predictable enough to chain into the F1-068 anonymous
// email-bombing flow once an attacker can guess a verification code from
// the request timestamp. SecureRandom seeds from the OS entropy pool.
private val secureRandom = SecureRandom()

// F5-018: full 62-char base62 alphabet (was 60 chars previously — typos
// dropped uppercase Y and lowercase j, and doubled T). Same alphabet as
// the UrlShortener pattern (see UrlShortener.kt), now the canonical
// alphanumeric token charset in the codebase.
private const val BASE62_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private const val NUMERIC_CHARS = "0123456789"

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

fun getRandomString(length: Int): String = randomFromCharset(BASE62_CHARS, length)

fun getRandomNumericalString(length: Int): String = randomFromCharset(NUMERIC_CHARS, length)

fun getRandomPassword() = getRandomString(DEFAULT_PASSWORD_LENGTH)

private fun randomFromCharset(charset: String, length: Int): String {
    val builder = StringBuilder(length)
    repeat(length) { builder.append(charset[secureRandom.nextInt(charset.length)]) }
    return builder.toString()
}

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
