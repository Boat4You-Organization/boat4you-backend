package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface PartnerEquipmentMappingRepository : JpaRepository<PartnerEquipmentMapping, Long> {
    /** Every explicit link, in memory 10 h on each node (a restart refreshes it), like equipmentCache. */
    @Cacheable("partnerEquipmentMappingCache")
    @Query("SELECT m FROM PartnerEquipmentMapping m ORDER BY m.id")
    fun findAllForResolver(): List<PartnerEquipmentMapping>
}
