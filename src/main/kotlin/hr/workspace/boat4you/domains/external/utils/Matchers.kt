package hr.workspace.boat4you.domains.external.utils

import org.apache.commons.text.similarity.JaroWinklerSimilarity

object Matchers {
    val jaroWinkler = JaroWinklerSimilarity()
    const val SIMILARITY_THRESHOLD = 0.9

    fun extrasNameMatch(
        keys: Set<String>,
        name: String?,
    ): Boolean {
        if (name.isNullOrBlank()) return false
        if (name.length > 70) return false

        // First, check for negative matches (not: prefix)
        val negativeKeys = keys.filter { it.trim().startsWith("not:") }
        for (key in negativeKeys) {
            val rawKey = key.trim().replace("not:", "")

            // If name contains the negative pattern, reject immediately
            if (name.contains(rawKey, ignoreCase = true)) {
                return false
            }

            // Check token-based negative matching
            val keyTokens = normalizeAndTokenize(rawKey)
            val nameTokens = normalizeAndTokenize(name)

            if (keyTokens.all { keyToken ->
                    nameTokens.any { nameToken ->
                        jaroWinkler.apply(keyToken, nameToken) >= SIMILARITY_THRESHOLD
                    }
                }
            ) {
                return false
            }
        }

        keys.forEach { key ->
            val trimmedKey = key.trim()
            if (trimmedKey.startsWith("not:")) {
                return@forEach
            }

            val rawKey =
                trimmedKey
                    .replace("case:", "")
                    .replace("full-match:", "")
                    .replace("case-substring:", "")
                    .replace("token-match:", "")

            // try exact match
            if (trimmedKey.startsWith("full-match:")) {
                if (rawKey.lowercase() == name.lowercase()) {
                    return true
                } else {
                    return@forEach
                }
            }
            // try exact match
            if (trimmedKey.startsWith("case-substring:")) {
                if (name.contains(rawKey, ignoreCase = false)) {
                    return true
                } else {
                    return@forEach
                }
            }

            // Token-based matching with normalization
            if (trimmedKey.startsWith("token-match:")) {
                val keyTokens = normalizeAndTokenize(rawKey)
                val nameTokens = normalizeAndTokenize(name)

                // All key tokens must be present in name tokens
                if (keyTokens.all { keyToken ->
                        nameTokens.any { nameToken ->
                            jaroWinkler.apply(keyToken, nameToken) >= SIMILARITY_THRESHOLD
                        }
                    }
                ) {
                    return true
                } else {
                    return@forEach
                }
            }

            // try similarity match
            val result =
                if (trimmedKey.startsWith("case:")) {
                    jaroWinkler.apply(rawKey, name)
                } else {
                    jaroWinkler.apply(rawKey.lowercase(), name.lowercase())
                }
            if (result >= SIMILARITY_THRESHOLD) return true
        }
        return false
    }

    private fun normalizeAndTokenize(text: String): List<String> {
        return text
            .lowercase()
            .replace("-", " ") // Convert hyphens to spaces
            .split(Regex("\\s+")) // Split on whitespace
            .filter { it.isNotEmpty() }
    }
}
