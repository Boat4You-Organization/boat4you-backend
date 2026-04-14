package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.ExternalEquipmentType
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ExternalEquipmentRepository : JpaRepository<ExternalEquipment, Int> {
    fun findByExternalSystemId(externalSystemId: Int): List<ExternalEquipment>

    fun findByExternalSystemIdAndType(
        externalSystemId: Int,
        type: ExternalEquipmentType,
    ): List<ExternalEquipment>

    @Cacheable("externalEquipmentCache")
    @Query("SELECT e FROM ExternalEquipment e WHERE e.externalSystem.id = :externalSystemId")
    fun getCachedByExternalSystemId(externalSystemId: Int): List<ExternalEquipment>
}
