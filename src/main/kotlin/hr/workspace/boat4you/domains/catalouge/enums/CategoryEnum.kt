package hr.workspace.boat4you.domains.catalouge.enums

enum class CategoryEnum(
    val value: Short,
) {
    // Legacy 3-category bucket — preserved at original ordinals so historical
    // yacht_equipment rows that haven't been remapped yet still resolve. New
    // Equipment rows go to the 9-category set below; the V1_73 migration
    // recategorises the existing 58 rows so SALOON_AND_CABINS / NAVIGATION_AND_SAFETY
    // end up effectively unused but the ordinal slots stay reserved for safety.
    SALOON_AND_CABINS(0),
    NAVIGATION_AND_SAFETY(1),
    ENTERTAINMENT(2),
    COMFORT(3),
    DECK(4),
    GALLEY(5),
    INTERIOR(6),
    NAVIGATION(7),
    SAFETY(8),
    SAILS(9),
    YACHT_ELECTRICS(10),
}
