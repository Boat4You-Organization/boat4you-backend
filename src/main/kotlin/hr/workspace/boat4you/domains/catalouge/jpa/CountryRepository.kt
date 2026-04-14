package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CountryRepository : JpaRepository<Country, Long> {
    @Query(
        "SELECT c FROM Country c" +
            " JOIN Region r ON c.id = r.country.id" +
            " JOIN ExternalMapping em ON r.id = em.systemId" +
            " WHERE em.externalSystem.id = :externalSystemId" +
            " AND em.externalId = :externalId",
    )
    fun findByNausysRegionId(
        externalId: Long,
        externalSystemId: Long = ExternalSystemEnum.NAUSYS.value.toLong(),
    ): Country?
}
