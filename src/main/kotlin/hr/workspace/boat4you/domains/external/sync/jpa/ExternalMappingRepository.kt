package hr.workspace.boat4you.domains.external.sync.jpa

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ExternalMappingRepository : JpaRepository<ExternalMapping, Long> {
    /**
     * Bulk-clean reservation mappings whose external_reservation no longer exists (orphaned by the
     * expired-option purge / absent-reconcile). Set-based so the one-time ~120k backlog cleanup
     * doesn't hammer cusma4. systemId holds the ExternalReservation.id (a soft reference, not a DB
     * FK), so an orphan is one whose target row is gone.
     */
    @Modifying
    @Query(
        "DELETE FROM ExternalMapping m WHERE m.type = :type " +
            "AND NOT EXISTS (SELECT 1 FROM ExternalReservation r WHERE r.id = m.systemId)",
    )
    fun deleteOrphanReservationMappings(
        @Param("type") type: String,
    ): Int
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

    fun findByExternalIdAndExternalSystemAndType(
        externalId: Long,
        externalSystem: ExternalSystem,
        type: String,
    ): ExternalMapping?
}
