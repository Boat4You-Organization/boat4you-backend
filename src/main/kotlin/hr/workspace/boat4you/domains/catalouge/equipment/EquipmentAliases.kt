package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import org.springframework.stereotype.Component

/**
 * Old equipment ids stay valid after a merge (V9_75: refrigerator -> fridge, bow-thruster-deck -> bow-thruster,
 * sundeck-cushions -> sun-pads): an alias id in `/public/yachts?amenities=` or in an admin custom-yacht request is
 * replaced by its canonical id, so `amenities=<refrigerator>` returns exactly what `amenities=<fridge>` returns.
 */
@Component
class EquipmentAliases(
    private val equipmentRepository: EquipmentRepository,
) {
    /** Every alias id replaced by its canonical id, duplicates dropped, order kept. */
    fun canonicalIds(ids: List<Long>?): List<Long>? {
        if (ids.isNullOrEmpty()) return ids
        val aliases = aliasMap()
        return ids.map { canonical(it, aliases) }.distinct()
    }

    fun canonicalId(id: Long): Long = canonical(id, aliasMap())

    private fun aliasMap(): Map<Long, Long> =
        equipmentRepository
            .findAllByOrderByIdAsc()
            .mapNotNull { equipment -> equipment.mergedIntoId?.let { equipment.id!! to it } }
            .toMap()

    private fun canonical(
        id: Long,
        aliases: Map<Long, Long>,
    ): Long {
        var current = id
        repeat(MAX_ALIAS_HOPS) { current = aliases[current] ?: return current }
        return current
    }

    private companion object {
        const val MAX_ALIAS_HOPS = 5
    }
}
