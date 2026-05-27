package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface ModelRepository : JpaRepository<Model, Long> {
    @Query(
        """
        SELECT m
        FROM Model m
        WHERE m.manufacturer.id IN :manufacturerIds
        AND (:name IS NULL OR :name = '' OR LOWER(m.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
    """,
    )
    fun findAllByManufacturerIdAndNameIgnoreCase(
        manufacturerIds: List<Long>,
        name: String?,
        pageable: Pageable,
    ): Page<Model>

    @Query(
        """
        SELECT m
        FROM Model m
        JOIN ExternalMapping em ON m.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId
        AND em.type = 'Model'
        AND em.externalId = :externalId
    """,
    )
    fun findModelByExternalIdAndExternalSystem(
        externalId: Long,
        externalSystemId: Long,
    ): Optional<Model>

    @Query(
        """
        SELECT m
        FROM Model m
        JOIN m.manufacturer mf
        JOIN ExternalMapping em ON mf.id = em.systemId AND em.type = 'Manufacturer'
        WHERE LOWER(m.name) = LOWER(:name)
        AND em.externalId = :manufacturerExternalId
        AND em.externalSystem.id = :manufacturerExternalSystemId
    """,
    )
    fun findByNameIgnoreCaseAndExternalManufacturerId(
        name: String,
        manufacturerExternalId: Long,
        manufacturerExternalSystemId: Int,
    ): Model?

    @Query(
        """
        SELECT m
        FROM Model m
        WHERE (lower(m.name), m.manufacturer) IN (
            SELECT lower(m2.name), m2.manufacturer
            FROM Model m2
            GROUP BY lower(m2.name), m2.manufacturer
            HAVING COUNT(m2.id) > 1
        )
    """,
    )
    fun findModelsWithDuplicateNames(): List<Model>
}
