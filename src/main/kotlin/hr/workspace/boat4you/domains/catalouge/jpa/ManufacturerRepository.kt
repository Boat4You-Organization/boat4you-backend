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
}
