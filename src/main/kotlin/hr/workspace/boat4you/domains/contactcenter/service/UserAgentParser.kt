package hr.workspace.boat4you.domains.contactcenter.service

/**
 * Device class, browser (with its major version) and OS from a User-Agent string, for `cc_visit.device_class/browser/os`.
 * A small ordered table, not a UA database: the first matching rule wins, so the specific engines (Edge, Opera, Samsung Internet)
 * sit before the generic Chrome and Safari tokens that they also carry.
 */
object UserAgentParser {
    /** [deviceClass] is MOBILE, TABLET or DESKTOP; [browser] reads "Chrome 131"; both [browser] and [os] are null only for a blank UA. */
    data class ParsedUa(
        val deviceClass: String,
        val browser: String?,
        val os: String?,
    )

    private const val OTHER = "Other"

    /** `cc_visit.browser` and `cc_visit.os` are VARCHAR(40); a hostile UA with a long version number must not fail the insert. */
    private const val MAX_LABEL = 40

    private val TABLET = Regex("(?i)ipad|tablet|playbook|silk|android(?!.*mobile)")
    private val MOBILE = Regex("(?i)mobi|iphone|ipod|windows phone|blackberry|opera mini")

    private val BROWSERS =
        listOf(
            "Edge" to Regex("Edg(?:e|A|iOS)?/(\\d+)"),
            "Opera" to Regex("OPR/(\\d+)"),
            "Samsung Internet" to Regex("SamsungBrowser/(\\d+)"),
            "Firefox" to Regex("(?:Firefox|FxiOS)/(\\d+)"),
            "Chrome" to Regex("(?:CriOS|Chrome)/(\\d+)"),
            "Safari" to Regex("Version/(\\d+)(?:\\.\\d+)*.*Safari/"),
        )

    private val OSES =
        listOf(
            "Windows" to Regex("Windows NT"),
            "iOS" to Regex("iPhone|iPad|iPod"),
            "Android" to Regex("Android"),
            "ChromeOS" to Regex("CrOS"),
            "macOS" to Regex("Mac OS X|Macintosh"),
            "Linux" to Regex("Linux"),
        )

    fun parse(ua: String?): ParsedUa {
        if (ua.isNullOrBlank()) return ParsedUa("DESKTOP", null, null)
        val device =
            when {
                TABLET.containsMatchIn(ua) -> "TABLET"
                MOBILE.containsMatchIn(ua) -> "MOBILE"
                else -> "DESKTOP"
            }
        val label = BROWSERS.firstNotNullOfOrNull { (name, pattern) -> pattern.find(ua)?.let { "$name ${it.groupValues[1]}" } }
        val browser = label?.take(MAX_LABEL) ?: OTHER
        val os = OSES.firstOrNull { (_, pattern) -> pattern.containsMatchIn(ua) }?.first ?: OTHER
        return ParsedUa(device, browser, os)
    }
}
