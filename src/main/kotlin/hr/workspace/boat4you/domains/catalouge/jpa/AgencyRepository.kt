package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AgencyRepository : JpaRepository<Agency, Long> {
    // F2-030: `JOIN FETCH a.agencySources` is a collection fetch — each
    // agency row is multiplied by the number of agencySources over
    // the wire before Hibernate de-dupes in memory. `DISTINCT` on the
    // three affected queries collapses to one row per agency. Same end
    // result, much smaller payload when the agency has 2+ sync sources.

    @Query(
        """
        SELECT DISTINCT a
        FROM Agency a
        JOIN FETCH a.agencySources ar
        JOIN FETCH ar.externalSystem es
        WHERE ar.primary = true
        AND es.id = :extarnalSystemId
        AND a.active = true
        """,
    )
    fun findAllActiveByPrimarySyncProvider(extarnalSystemId: Long): Set<Agency>

    @Query(
        """
        SELECT a.id
        FROM Agency a
        JOIN a.agencySources ar
        JOIN ar.externalSystem es
        WHERE ar.primary = true 
        AND es.id = :extarnalSystemId
        AND a.active = true
        AND NOT EXISTS (
            SELECT 1 FROM Yacht y WHERE y.agency.id = a.id
            )
    """,
    )
    fun findAllActiveWithoutYachts(extarnalSystemId: Long): Set<Long>

    @Query(
        """
        SELECT DISTINCT a
        FROM Agency a
        JOIN FETCH a.agencySources ar
        JOIN FETCH ar.externalSystem es
        WHERE ar.primary = true
        AND es.id = :extarnalSystemId
        AND a.active = true
        AND EXISTS (
            SELECT 1 FROM Yacht y WHERE y.agency.id = a.id
            )
        """,
    )
    fun findAllActiveByPrimarySyncProviderAndActiveYachts(extarnalSystemId: Long): Set<Agency>

    @Query(
        """
        SELECT DISTINCT a
        FROM Agency a
        JOIN FETCH a.agencySources ar
        JOIN FETCH ar.externalSystem es
        WHERE ar.primary = true
        AND es.id = :extarnalSystemId
        AND a.active = true
        AND EXISTS (
            SELECT 1 FROM Yacht y WHERE y.agency.id = a.id
        )
        """,
    )
    fun findAllActiveByPrimarySyncProviderAndHasYacht(extarnalSystemId: Long): Set<Agency>

    fun findByVatCode(vatCode: String): Agency?

    @Query(
        """
        SELECT a
        FROM Agency a
        WHERE LOWER(a.name) = LOWER(:name)
        AND NOT EXISTS (
            SELECT 1 FROM AgencySource ar WHERE ar.id.agencyId = a.id AND ar.id.externalSystemId = :externalSystemId
        )
    """,
    )
    fun findByNameAndNotExistsInOtherSystem(
        name: String,
        externalSystemId: Int,
    ): Agency?

    // F2-029: same redundant-STR fix as in InquiryRepository. `:name`
    // is bound as String, the JPQL `STR(...)` is a no-op.
    @Query(
        """
        SELECT a FROM Agency a
        WHERE 1 = 1
        AND (:active IS NULL OR a.active = :active)
        AND (:name IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :name, '%')))
        AND (:countryCode IS NULL OR a.country = :countryCode)
        AND (
            :primarySource IS NULL OR EXISTS (
                SELECT 1 FROM AgencySource s
                WHERE s.id.agencyId = a.id
                AND s.externalSystem.id = :primarySource
                AND s.primary = true
            )
        )
        """,
    )
    fun findAllByParamsForAdmin(
        @Param("name") name: String?,
        @Param("active") active: Boolean?,
        @Param("countryCode") countryCode: String?,
        @Param("primarySource") primarySource: Int?,
        pageable: Pageable,
    ): Page<Agency>

    @Query(
        """
        SELECT a FROM Agency a
        JOIN ExternalMapping em ON a.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId
        AND em.externalId = :externalId
        AND em.type = 'Agency'
    """,
    )
    fun findByExternalIdAndExternalSystemId(
        externalId: Long,
        externalSystemId: Long,
    ): Agency?
}
