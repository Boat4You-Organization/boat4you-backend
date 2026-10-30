package hr.workspace.boat4you.domains.calls

/**
 * The 7 sites an inquiry can come from, as Nika names them ("we received your inquiry on our site {spokenName}, part of the
 * Boat4You group", design §2.8). Sent by each form as the explicit `site` field; deliberately not BrandRegistry, so the call
 * center does not depend on Origin or the Contact Center brand fix. Spoken names are Claude's call, Mario may change them.
 */
enum class CallSite(
    val key: String,
    val spokenName: String,
    val domain: String,
) {
    BOAT4YOU("boat4you", "Boat4You", "www.boat4you.com"),
    CC("cc", "Catamaran Croatia Charter", "www.catamaran-croatia-charter.com"),
    GREECE("greece", "Catamaran Charter Greece", "www.catamaran-charter-greece.com"),
    ITALY("italy", "Catamaran Charter Italy", "www.catamarancharteritaly.com"),
    CARIBBEAN("caribbean", "Catamaran Charter Caribbean", "www.catamaran-charter-caribbean.com"),
    CY("cy", "Yacht Charter Croatia", "www.croatia-yachting.com"),
    EY("ey", "Europe Yachts", "www.europe-yachts.com"),
    ;

    companion object {
        fun fromKey(key: String?): CallSite? {
            val k = key?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key == k }
        }
    }
}
