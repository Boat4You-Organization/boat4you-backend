package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMapping
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMappingRepository
import org.springframework.stereotype.Component

/**
 * The link of a partner equipment item to our catalogue, recomputed by the MMK and NauSys syncs on every pass
 * (equipment audit 8.10.2026, FIX_CONTRACT 7-8):
 * 1. an explicit partner_equipment_mapping row - (system, item, '') for a catalogue item, (system, -1, normalised name)
 *    for MMK free text - wins, its NULL meaning "no link";
 * 2. otherwise the best hit of [EquipmentMatcher];
 * 3. an alias result is followed to its canonical row (merged_into_id), so an alias is never linked.
 */
@Component
class EquipmentLinkResolver(
    private val equipmentRepository: EquipmentRepository,
    private val mappingRepository: PartnerEquipmentMappingRepository,
) {
    private class Compiled(
        val source: List<Equipment>,
        val matcher: EquipmentMatcher,
    )

    @Volatile
    private var compiled: Compiled? = null

    /**
     * A resolver for one sync pass (one agency): the catalogue and the explicit links as cached right now (10 h, a
     * restart refreshes them), results memoised by (system, item, name). The keys are parsed again only when
     * equipmentCache hands out a new list.
     */
    fun newPass(): Pass {
        val equipment = equipmentRepository.findAllByOrderByIdAsc()
        val matcher =
            compiled?.takeIf { it.source === equipment }?.matcher
                ?: EquipmentMatcher(equipment).also { compiled = Compiled(equipment, it) }
        return Pass(equipment, matcher, mappingRepository.findAllForResolver())
    }

    class Pass(
        equipment: List<Equipment>,
        private val matcher: EquipmentMatcher,
        mappings: List<PartnerEquipmentMapping>,
    ) {
        private val byId: Map<Long?, Equipment> = equipment.associateBy { it.id }
        private val explicit: Map<MappingKey, Long?> =
            mappings.associate { MappingKey(it.externalSystemId!!, it.partnerItemId!!, it.partnerNameNorm) to it.equipmentId }
        private val memo = HashMap<Triple<Int, Long, String?>, Equipment?>()

        /** [partnerItemId] is the partner catalogue id (external_equipment.external_id), -1 for MMK free text. */
        fun resolve(
            systemId: Int,
            partnerItemId: Long,
            name: String?,
        ): Equipment? {
            val key = Triple(systemId, partnerItemId, name)
            if (memo.containsKey(key)) return memo[key]
            val mappingKey = MappingKey(systemId, partnerItemId, if (partnerItemId > 0) "" else EquipmentNames.normalize(name))
            val linked =
                if (explicit.containsKey(mappingKey)) {
                    explicit[mappingKey]?.let { byId[it] }
                } else {
                    matcher.best(name)
                }
            return canonical(linked).also { memo[key] = it }
        }

        private fun canonical(equipment: Equipment?): Equipment? {
            var current = equipment
            repeat(MAX_ALIAS_HOPS) {
                current = current?.mergedIntoId?.let { byId[it] } ?: return current
            }
            return current
        }
    }

    private data class MappingKey(
        val systemId: Int,
        val partnerItemId: Long,
        val nameNorm: String,
    )

    private companion object {
        const val MAX_ALIAS_HOPS = 5
    }
}
