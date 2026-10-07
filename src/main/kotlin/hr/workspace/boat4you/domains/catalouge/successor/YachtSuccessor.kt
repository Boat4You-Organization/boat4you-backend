package hr.workspace.boat4you.domains.catalouge.successor

import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils

/**
 * The live listing of the same physical boat as a retired one (yacht_successor, V9_73): its id and the slug its boat
 * page answers under. Carried by the 1502 "Yacht is not active" answer so the web sites can redirect the old URL.
 */
data class YachtSuccessor(
    val id: Long,
    val slug: String,
)

/**
 * Reads the successor of one retired boat on the API node — only on the 1502 path, so an active boat never pays for it.
 *
 * The table is recomputed once a day (YachtSuccessorJob), so the successor is checked again here against what the
 * public boat page serves (YachtQueryingService.getValidYacht): still active, agency active and not
 * availability-blocked, not at an inland base (V9_64). A successor switched off since the last run is simply left out.
 * Named parameter `:id` (JPA native query); no `::` casts, which the parameter parser would misread.
 */
object YachtSuccessorLookup {
    const val SQL = """
        SELECT y.id, mf.name AS manufacturer, m.name AS model, y.name
        FROM yacht_successor s
        JOIN yacht y              ON y.id = s.new_id
        JOIN agency a             ON a.id = y.agency_id
        LEFT JOIN location l      ON l.id = y.location_id
        LEFT JOIN model m         ON m.id = y.model_id
        LEFT JOIN manufacturer mf ON mf.id = m.manufacturer_id
        WHERE s.old_id = :id
          AND y.sys_active
          AND a.active
          AND NOT a.availability_blocked
          AND NOT COALESCE(l.inland, false)
    """

    /** One [SQL] row (id, manufacturer, model, name) → the successor, slug built exactly like the boat page's own. */
    fun fromRow(row: Array<*>): YachtSuccessor {
        val id = (row[0] as Number).toLong()
        return YachtSuccessor(id, SlugUtils.toSlugWithId(row[1] as String?, row[2] as String?, row[3] as String?, id))
    }
}
