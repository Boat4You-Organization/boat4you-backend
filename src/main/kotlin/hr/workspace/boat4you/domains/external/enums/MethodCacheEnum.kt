package hr.workspace.boat4you.domains.external.enums

enum class MethodCacheEnum {
    OFFER,
    YACHT_SEARCH,
    SCHEDULED_NAUSYS_YACHT_SYNC,
    SCHEDULED_NAUSYS_YACHT_OFFER,
    // Written when the nightly offer sync STARTS; lets the backup slot see a still-running night.
    SCHEDULED_NAUSYS_YACHT_OFFER_STARTED,
    SCHEDULED_NAUSYS_CATALOGUE_SYNC,
    SCHEDULED_MMK_YACHT_SYNC,
    SCHEDULED_MMK_YACHT_LANG_SYNC,
    SCHEDULED_MMK_YACHT_OFFER,
    SCHEDULED_MMK_CATALOGUE_SYNC,
}
