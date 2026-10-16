package hr.workspace.boat4you.domains.settings.enums

enum class SettingsKeyEnum {
    CARD_PAYMENT_SURCHARGE,
    BANK_TRANSFER_FIXED_FEE,

    // Real-price search runtime switches (14.10.2026, DESIGN.md section 1.2). Read through
    // common.config.RealPriceSwitches; a missing row means the default = today's behaviour.
    // Allowed values per key: RealPriceSwitches.ALLOWED_VALUES (checked by AdminSettingsService).
    SEARCH_PREDICATE,
    SEARCH_PRICE_FLOOR,
    SEARCH_DEMOTE_STALE_OPTIONS,
    BOOKING_C1,
    PARTNER_PRICE_GUARD,
    EXTRAS_ZERO_OPTIONAL_ON_SITE,
    MMK_OPTION_VERIFY,
    MMK_ONDEMAND_STRIKE,
    NAUSYS_REVERIFY,
    NAUSYS_D3_MODE,
    NAUSYS_WARM_LONG_RANGES,

    // Boat page gallery with model and marina photos (photos DESIGN.md, 16.10.2026): OFF = today's gallery, ON = the
    // YachtGalleryService merge. Same mechanics as the real-price switches (RealPriceSwitches.galleryExtended).
    GALLERY_EXTENDED,
}
