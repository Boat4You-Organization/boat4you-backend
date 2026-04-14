package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface YachtTranslationRepository : JpaRepository<YachtTranslation, Long> {
    fun deleteByYachtId(yachtId: Long)

    fun findAllByYachtId(yachtId: Long): List<YachtTranslation>
}
