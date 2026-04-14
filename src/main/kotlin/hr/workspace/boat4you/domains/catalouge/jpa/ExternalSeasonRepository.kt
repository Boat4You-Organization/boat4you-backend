package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository

interface ExternalSeasonRepository : JpaRepository<ExternalSeason, Long> {
    @Cacheable("seasonsCache", unless = "#result == null")
    fun findByExternalId(externalId: Long): ExternalSeason?
}
