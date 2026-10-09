package hr.workspace.boat4you.domains.catalouge.utils

import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.VesselType

/**
 * A gulet is never bareboat (Mario 8.10.2026: "GULET JE UVIJEK SA POSADOM"; 9.10.2026: "GULET NIKAD NIJE BAREBOAT").
 *
 * A yacht is a gulet when its vessel type is GULET or its model name contains "gulet" (any case): b4y Gallant 11771 is
 * a MOTOR_YACHT of model "Gulet". Partners still tag a few of them BAREBOAT (Sylvia R 18886; prod 9.10.2026: 251 active
 * gulets, 247 by type + 4 by model only; 2 tagged BAREBOAT only, 1 BAREBOAT + CREWED). Partner data stays as it is
 * (yacht_charter_type, offers, extras, prices); only what the public API says and filters on reads them as crewed:
 *
 * - the boat page / listing charter type: a gulet's types without BAREBOAT, CREWED when nothing is left;
 * - the charterType filter: BAREBOAT never matches a gulet, CREWED matches every gulet (whatever the partner tagged);
 *   the facet counts (`byCharterType`) and the relax suggestions count the same way.
 *
 * Every other boat keeps its partner charter types unchanged. [SQL_IS_GULET] and [charterTypeSql] are the native-SQL
 * twins on yacht_search_view (unqualified columns); YachtQueryingService builds the same test with the criteria API.
 */
object GuletRules {
    /** The model-name word that makes a boat a gulet, matched as a case-insensitive substring like SQL ILIKE '%gulet%'. */
    const val MODEL_WORD = "gulet"

    /** A yacht_search_view row is a gulet: vessel type GULET or a model name containing "gulet". Never NULL. */
    const val SQL_IS_GULET = "(COALESCE(vessel_type, '') = 'GULET' OR COALESCE(model_name, '') ILIKE '%$MODEL_WORD%')"

    fun isGulet(
        vesselType: VesselType?,
        modelName: String?,
    ): Boolean = vesselType == VesselType.GULET || modelName?.contains(MODEL_WORD, ignoreCase = true) == true

    /** The boat page's charter types: a gulet's without BAREBOAT (CREWED when nothing is left); others unchanged. */
    fun publicCharterTypes(
        types: Set<CharterType>,
        gulet: Boolean,
    ): Set<CharterType> {
        if (!gulet) return types
        return (types - CharterType.BAREBOAT).ifEmpty { setOf(CharterType.CREWED) }
    }

    /**
     * The listing row's one charter type. The search already takes LEAST over the yacht's rows without a gulet's
     * BAREBOAT rows, so a gulet with nothing else reaches here as null and reads CREWED (as [publicCharterTypes]).
     */
    fun publicCharterType(
        type: CharterType?,
        gulet: Boolean,
    ): CharterType? = if (gulet && (type == null || type == CharterType.BAREBOAT)) CharterType.CREWED else type

    /**
     * The charterType filter on yacht_search_view, bound to `:[param]` = the asked type names: the asked types, never a
     * gulet's BAREBOAT row, and every gulet when CREWED is asked.
     */
    fun charterTypeSql(
        types: Collection<CharterType>,
        param: String,
    ): String {
        val listed = "(charter_type IN (:$param) AND NOT (charter_type = 'BAREBOAT' AND $SQL_IS_GULET))"
        return if (CharterType.CREWED in types) "($listed OR $SQL_IS_GULET)" else listed
    }
}
