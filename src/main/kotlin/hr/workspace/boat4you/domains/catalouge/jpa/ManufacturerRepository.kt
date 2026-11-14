package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ManufacturerRepository : JpaRepository<Manufacturer, Long> {
    @Query("SELECT m FROM Manufacturer m WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun findManufacturersByNameIgnoreCase(
        @Param("name") name: String,
        pageable: Pageable,
    ): Page<Manufacturer>

    /** Every manufacturer name: a builder ("Lagoon", "Bali") is never redacted as a charter company in an offer e-mail. */
    @Query("SELECT m.name FROM Manufacturer m WHERE m.name IS NOT NULL")
    fun findAllNames(): List<String>
}
