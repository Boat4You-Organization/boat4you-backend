package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface RegionRepository : JpaRepository<Region, Long> {
    @Query(
        "SELECT r FROM Region r" +
            " JOIN ExternalMapping em ON r.id = em.systemId" +
            " WHERE em.externalSystem.id = :externalSystemId" +
            " AND em.externalId = :externalId",
    )
    fun findByNausysRegionId(
        externalId: Long,
        externalSystemId: Long = ExternalSystemEnum.NAUSYS.value.toLong(),
    ): Region?

    fun findByName(name: String): Region?
}
