package hr.workspace.boat4you.domains.catalouge.enums

/**
 * Display kind of a mainsail / headsail (capacity contract v1, 5.4), derived at read time from the partner label
 * (yacht.mainsail_label / genoa_label). A closed set the sites translate with their own labels; a label outside it is
 * shown as the partner wrote it (in English). Not the filter: that stays [SailTypeEnum].
 */
enum class SailKind {
    FULL_BATTEN,
    SEMI_FULL_BATTEN,
    HALF_BATTEN,
    CLASSIC,
    FURLING,
    SELF_TACKING_JIB,
    JIB,
}
