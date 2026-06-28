package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface AgencyRepository : JpaRepository<Agency, Long> {

    // Toggle the "partner stopped serving us" flag without merging the whole
    // agency graph (avoids touching agencySources). Hides/restores the agency's
    // yachts via yacht_search_view + getValidYacht. Mario rule 29.6.2026.
    @Transactional
    @Modifying
    @Query("UPDATE Agency a SET a.availabilityBlocked = :blocked WHERE a.id = :id")
    fun setAvailabilityBlocked(@Param("id") id: Long, @Param("blocked") blocked: Boolean): Int
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

    // `CAST(:name AS string)` is REQUIRED: a null :name param is otherwise
    // inferred as bytea by PostgreSQL, making `lower('%'||?||'%')` throw
    // "function lower(bytea) does not exist" and 500 the admin agency list.
    // See InquiryRepository for the full rationale.
    @Query(
        """
        SELECT a FROM Agency a
        WHERE 1 = 1
        AND (:active IS NULL OR a.active = :active)
        AND (:name IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
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
