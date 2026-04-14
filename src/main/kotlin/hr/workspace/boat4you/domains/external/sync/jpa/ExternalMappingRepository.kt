package hr.workspace.boat4you.domains.external.sync.jpa

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ExternalMappingRepository : JpaRepository<ExternalMapping, Long> {
    fun findAllByTypeAndExternalSystem(
        type: String,
        externalSystem: ExternalSystem,
    ): List<ExternalMapping>

    fun findAllByTypeAndExternalSystemAndExtendedType(
        type: String,
        externalSystem: ExternalSystem,
        extendedType: String,
    ): List<ExternalMapping>

    @Query(
        """
        SELECT DISTINCT em.externalId
        FROM ExternalMapping em
        JOIN Yacht y ON y.id = em.systemId
        WHERE y.agency.id = :agencyId
        AND em.type = 'Yacht'
        AND em.externalSystem.id = :externalSystemId
    """,
    )
    fun findAllExternalYachtIdsForAgency(
        agencyId: Long,
        externalSystemId: Long,
    ): List<Long>

    fun findBySystemIdAndExternalSystemAndType(
        systemId: Long,
        externalSystem: ExternalSystem,
        type: String,
    ): ExternalMapping?

    fun findAllByTypeAndExternalSystemIdAndExternalIdIn(
        type: String,
        externalSystemId: Int,
        externalIds: List<Long>,
    ): List<ExternalMapping>

    fun findBySystemIdAndType(
        systemId: Long,
        type: String,
    ): ExternalMapping?
}
