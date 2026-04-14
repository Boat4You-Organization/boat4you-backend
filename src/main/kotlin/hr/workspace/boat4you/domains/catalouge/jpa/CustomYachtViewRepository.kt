package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CustomYachtViewRepository : JpaRepository<CustomYachtView, Long> {
    @Query(
        """
        SELECT c FROM CustomYachtView c
        WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%'))
    """,
    )
    fun findAllByNameLikeIgnoreCase(
        name: String,
        pageable: Pageable,
    ): Page<CustomYachtView>
}
