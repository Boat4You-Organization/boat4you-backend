package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository

interface LanguageRepository : JpaRepository<Language, Int> {
    @Cacheable("languageCache")
    override fun findAll(): List<Language>
}
