package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface YachtImageRepository : JpaRepository<YachtImage, Long> {
    fun countYachtImageBySyncedFalse(): Long

    fun findBySyncedFalseOrderByIdDesc(pageable: Pageable): List<YachtImage>
}
