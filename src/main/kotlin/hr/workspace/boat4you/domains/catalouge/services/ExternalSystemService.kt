package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystemRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
class ExternalSystemService(
    private val externalSystemRepository: ExternalSystemRepository,
) {
    @Cacheable(value = ["externalSystemCache"], unless = "#result == null")
    fun findById(id: Long): ExternalSystem {
        return externalSystemRepository.findById(id).orElseThrow()
    }
}
