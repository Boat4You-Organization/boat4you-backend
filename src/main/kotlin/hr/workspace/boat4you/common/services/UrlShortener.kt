package hr.workspace.boat4you.common.services

import java.security.SecureRandom

object UrlShortener {
    private val random = SecureRandom()

    // Base62 characters (alphanumeric only)
    private const val base62Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /**
     * Generates a random 6-character base62 string
     */
    fun generateShortKey(): String {
        val key = StringBuilder(6)
        repeat(6) {
            key.append(base62Chars[random.nextInt(base62Chars.length)])
        }
        return key.toString()
    }

//    /**
//     * Shortens a URL and returns the short key
//     */
//    fun shortenUrl(originalUrl: String): String {
//        // Check if URL is already shortened
//        reverseMap[originalUrl]?.let { return it }
//
//        // Generate unique key
//        var shortKey: String
//        do {
//            shortKey = generateShortKey()
//        } while (urlMap.containsKey(shortKey))
//
//        // Store mapping
//        urlMap[shortKey] = originalUrl
//        reverseMap[originalUrl] = shortKey
//
//        return shortKey
//    }
}
