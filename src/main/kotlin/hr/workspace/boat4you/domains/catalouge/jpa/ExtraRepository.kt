package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ExtraRepository : JpaRepository<Extra, Long> {
    @Cacheable("extrasCache")
    override fun findAll(): List<Extra>

    @Cacheable("extrasFilter")
    @Query(
        """
        SELECT e FROM Extra e
        WHERE e.filterOrder IS NOT NULL
        ORDER BY e.filterOrder
    """,
    )
    fun findForFilters(): List<Extra>
}
