package hr.workspace.boat4you.domains.catalouge.services

import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which location rows are the SAME physical marina. The partner catalogues import one marina once per provider under
 * different names — "Marina Kastela" / "Marina Kaštela" (same spelling up to diacritics), MMK "Marina Baotić" /
 * NauSys "Trogir, Yachtclub Seget (Marina Baotić)" (one name inside the other), and a few the names cannot pair at all
 * ("D-Marin Marina Lefkas" / "Lefkas, D-Marin", curated in `location_same_place`, V9_68).
 *
 * One rule set, used by the location autocomplete merge (LocationQueryingService), the search's `l-` resolution and the
 * charter facts (bases and marina keys), so a landing, its facts block and its "main bases" list always mean the same
 * boats (26.9.2026 audit B13 / B14).
 *
 * Name rules are vetoed by the data when it disagrees: "Marina Frapa" (Rogoznica) sits inside "Marina Frapa Dubrovnik"
 * but is 170 km away, so the name rule alone put 17 Rogoznica boats on the Dubrovnik catamaran landing. Two rows the
 * name rules pair stay separate when both have coordinates further apart than the rule's limit, or — without
 * coordinates — both have a city and the cities differ.
 */
object MarinaPlaces {
    data class Marina(
        val id: Long,
        val name: String,
        val countryCode: String?,
        val city: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
    )

    /** Same spelling (diacritics folded): coordinates veto only a clearly different place. */
    const val SAME_SPELLING_MAX_KM = 50.0

    /** One name inside the other: a looser name rule, so a tighter distance. */
    const val CONTAINED_NAME_MAX_KM = 20.0

    /** Shorter names are too generic to pair by containment ("Kos" is inside "Marina Kos" and "Kos Harbour"). */
    private const val MIN_CONTAINED_LENGTH = 4

    private const val EARTH_RADIUS_KM = 6371.0

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")
    private val MARKS = Regex("\\p{M}+")

    /** The historic autocomplete fold: lower case, letters and digits only, diacritics KEPT ("marinabaotić"). */
    fun containmentFold(s: String?): String = (s ?: "").lowercase().replace(NON_ALNUM, "")

    /** [containmentFold] with diacritics stripped too: "Marina Kaštela" and "Marina Kastela" -> "marinakastela". */
    fun spellingFold(s: String?): String =
        Normalizer
            .normalize(containmentFold(s).replace('đ', 'd').replace('ł', 'l').replace('ø', 'o'), Normalizer.Form.NFD)
            .replace(MARKS, "")

    /** Great-circle distance, null when either row has no usable coordinates (0,0 is the partners' "unknown"). */
    fun distanceKm(
        a: Marina,
        b: Marina,
    ): Double? {
        val (lat1, lon1) = coordinates(a) ?: return null
        val (lat2, lon2) = coordinates(b) ?: return null
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun coordinates(m: Marina): Pair<Double, Double>? {
        val lat = m.lat ?: return null
        val lon = m.lon ?: return null
        return if (lat == 0.0 && lon == 0.0) null else lat to lon
    }

    /**
     * Whether the data allows two name-paired rows to be one place: coordinates decide when both rows have them,
     * otherwise two known cities must match (one containing the other: "Kaštel Gomilica" / "Kastel Gomilica").
     */
    fun sameArea(
        a: Marina,
        b: Marina,
        maxKm: Double,
    ): Boolean {
        distanceKm(a, b)?.let { return it <= maxKm }
        val ca = spellingFold(a.city)
        val cb = spellingFold(b.city)
        return ca.isEmpty() || cb.isEmpty() || ca.contains(cb) || cb.contains(ca)
    }

    /**
     * Containment pairs (shorter -> longer) in [marinas]: a marina whose [containmentFold] name is a proper substring
     * of EXACTLY ONE other marina's name in the same country (a bare city name inside several marinas pairs with none),
     * vetoed by [sameArea]. Chained nestings (A in B in C) pair only the leaf, like the autocomplete always did.
     */
    fun containmentPairs(marinas: List<Marina>): List<Pair<Marina, Marina>> {
        val candidates = marinas.filter { containmentFold(it.name).length >= MIN_CONTAINED_LENGTH }
        val folded = candidates.associate { it.id to containmentFold(it.name) }
        val pairs = mutableListOf<Pair<Marina, Marina>>()
        val consumed = mutableSetOf<Long>()
        val containers = mutableSetOf<Long>()
        for (shorter in candidates) {
            val fs = folded.getValue(shorter.id)
            val inside =
                candidates.filter { longer ->
                    val fl = folded.getValue(longer.id)
                    longer.id != shorter.id && longer.countryCode == shorter.countryCode && fl.length > fs.length && fl.contains(fs)
                }
            if (inside.size != 1) continue
            val longer = inside.first()
            if (longer.id in consumed || shorter.id in containers) continue
            if (!sameArea(shorter, longer, CONTAINED_NAME_MAX_KM)) continue
            pairs.add(shorter to longer)
            consumed.add(shorter.id)
            containers.add(longer.id)
        }
        return pairs
    }

    /** Whether a row carries any location data (usable coordinates or a city) — SQL: location_has_area (V9_67). */
    fun hasArea(m: Marina): Boolean = coordinates(m) != null || spellingFold(m.city).isNotEmpty()

    /**
     * Place id (the smallest member id) for every row in [marinas]: rows are joined when they share a [spellingFold]
     * name in the same country ([SAME_SPELLING_MAX_KM] veto), by [containmentPairs], and by the [curated] pairs whose
     * both ends are present. When one spelling covers two places (two rows with data that are not [sameArea]), the
     * rows with data join only the rows proven near them and a row without data stays alone — the same rule as
     * LocationRepository.findSamePlaceMarinaIds.
     */
    fun placeIds(
        marinas: List<Marina>,
        curated: Collection<Pair<Long, Long>> = emptyList(),
    ): Map<Long, Long> {
        val parent = marinas.associate { it.id to it.id }.toMutableMap()

        fun find(x: Long): Long {
            var r = x
            while (parent.getValue(r) != r) r = parent.getValue(r)
            var c = x
            while (parent.getValue(c) != r) {
                val next = parent.getValue(c)
                parent[c] = r
                c = next
            }
            return r
        }

        fun union(
            a: Long,
            b: Long,
        ) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) {
                if (ra < rb) parent[rb] = ra else parent[ra] = rb
            }
        }

        marinas
            .groupBy { (it.countryCode ?: "") to spellingFold(it.name) }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val located = group.filter { hasArea(it) }
                val ambiguous =
                    located.indices.any { i ->
                        (i + 1 until located.size).any { j -> !sameArea(located[i], located[j], SAME_SPELLING_MAX_KM) }
                    }
                if (!ambiguous) {
                    group.forEach { union(group.first().id, it.id) }
                } else {
                    for (i in located.indices) {
                        for (j in i + 1 until located.size) {
                            if (sameArea(located[i], located[j], SAME_SPELLING_MAX_KM)) union(located[i].id, located[j].id)
                        }
                    }
                }
            }
        containmentPairs(marinas).forEach { (a, b) -> union(a.id, b.id) }
        curated.filter { (a, b) -> a in parent && b in parent }.forEach { (a, b) -> union(a, b) }
        return marinas.associate { it.id to find(it.id) }
    }

    /**
     * The row that names a place everywhere (facts bases, autocomplete merge): the longest name (the container of a
     * containment pair — "Trogir, Yachtclub Seget (Marina Baotić)", as the autocomplete shows it), then the spelling
     * with diacritics ("Marina Kaštela", not "Marina Kastela"), then the lowest id. Deterministic, so the same marina
     * never reads differently on two landings.
     */
    fun representative(members: Collection<Marina>): Marina =
        members.minWith(
            compareByDescending<Marina> { containmentFold(it.name).length }
                .thenByDescending { containmentFold(it.name) != spellingFold(it.name) }
                .thenBy { it.id },
        )
}
