package hr.workspace.boat4you.domains.catalouge.enums

/**
 * Who a yacht_image row belongs to (V9_92, owner request 17.10.2026: our own photos on partner boats). The MMK / NauSys
 * syncs own the PARTNER rows - they create, move and delete them - and never delete, move below the partner's or re-flag
 * an OWN row (OwnImageRules).
 */
enum class YachtImageSource {
    /** A partner picture (MMK / NauSys sync); also every row older than V9_92 (custom-boat uploads included). */
    PARTNER,

    /** Our own photo of the boat, uploaded by an admin (POST /admin/yachts/{id}/images, /main-image). */
    OWN,
}
