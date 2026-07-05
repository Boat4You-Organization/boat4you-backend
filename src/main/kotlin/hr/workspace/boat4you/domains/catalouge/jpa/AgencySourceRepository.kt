package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AgencySourceRepository : JpaRepository<AgencySource, AgencySourceId> {
    /**
     * Every source row of one partner system, regardless of the agency's active flag or
     * whether the source is primary — the identity index the agency mirror dedupes against
     * (a company already attached anywhere must update/reactivate, never fork a duplicate row).
     */
    @Query("SELECT s FROM AgencySource s WHERE s.id.externalSystemId = :externalSystemId")
    fun findAllByExternalSystemId(
        @Param("externalSystemId") externalSystemId: Int,
    ): List<AgencySource>

    /**
     * All primary source rows across systems. The agency mirror's reconcile MUST read sources
     * through this (not through Agency.agencySources): agencies loaded via
     * findAllActiveByPrimarySyncProvider carry a fetch-join-FILTERED collection (only that
     * system's primary row), so a dual-primary agency looks single-sourced there.
     */
    @Query("SELECT s FROM AgencySource s WHERE s.primary = true")
    fun findAllPrimary(): List<AgencySource>
}
