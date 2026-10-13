package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.domains.custompayment.CustomPaymentSite
import hr.workspace.boat4you.domains.custompayment.CustomPaymentSites
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * The secret `/pay/<linkKey>` keys per site, from `application.revolut.custom-payment.link-keys` in the form
 * `site=key[,key];site2=key` (two keys per site may be valid at once during a rotation). A key must be at least
 * [MIN_KEY_LENGTH] URL-safe characters (`A-Z a-z 0-9 _ -`, it is a path segment); unknown sites, short or malformed
 * keys are dropped with a WARN that never prints a key. Comparison is constant-time ([MessageDigest.isEqual]).
 */
class CustomPaymentLinkKeys(
    raw: String,
) {
    private val keysBySite: Map<String, List<ByteArray>> = parse(raw)

    /** The site when [linkKey] is one of its keys, otherwise null (unknown site, wrong or blank key). */
    fun resolve(
        siteKey: String?,
        linkKey: String?,
    ): CustomPaymentSite? {
        val site = CustomPaymentSites.byKey(siteKey) ?: return null
        val candidate = linkKey?.trim()?.toByteArray(Charsets.UTF_8) ?: return null
        val keys = keysBySite[site.key] ?: return null
        // No early exit: every key of the site is compared.
        var match = false
        for (key in keys) {
            if (MessageDigest.isEqual(key, candidate)) match = true
        }
        return site.takeIf { match }
    }

    /** Sites with at least one key and how many (for the startup log; never the keys). */
    fun summary(): Map<String, Int> = keysBySite.mapValues { it.value.size }

    companion object {
        const val MIN_KEY_LENGTH = 32
        private const val MAX_KEY_LENGTH = 128
        private val KEY = Regex("^[A-Za-z0-9_-]{$MIN_KEY_LENGTH,$MAX_KEY_LENGTH}$")
        private val log = LoggerFactory.getLogger(CustomPaymentLinkKeys::class.java)

        private fun parse(raw: String): Map<String, List<ByteArray>> {
            val result = linkedMapOf<String, MutableList<ByteArray>>()
            raw
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { index, pair ->
                    val separator = pair.indexOf('=')
                    val siteKey = if (separator > 0) pair.substring(0, separator).trim() else ""
                    if (CustomPaymentSites.byKey(siteKey) == null) {
                        log.warn("custom-payment.link-keys entry {} ignored: unknown site or not site=key", index + 1)
                        return@forEachIndexed
                    }
                    pair
                        .substring(separator + 1)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { key ->
                            if (KEY.matches(key)) {
                                result.getOrPut(siteKey) { mutableListOf() }.add(key.toByteArray(Charsets.UTF_8))
                            } else {
                                log.warn(
                                    "custom-payment.link-keys: a key of site {} ignored ({}-{} characters A-Z a-z 0-9 _ - required)",
                                    siteKey,
                                    MIN_KEY_LENGTH,
                                    MAX_KEY_LENGTH,
                                )
                            }
                        }
                }
            return result
        }
    }
}
