package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.ManufacturerDto
import hr.workspace.boat4you.domains.catalouge.jpa.ManufacturerRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ManufacturerQueryingService(
    private val manufacturerRepository: ManufacturerRepository,
) {
    @Cacheable("manufacturersCache")
    fun getManufacturersCached(
        name: String?,
        pageable: Pageable,
    ): Page<ManufacturerDto> {
        return if (name.isNullOrBlank()) {
            manufacturerRepository.findAll(pageable).map { it.toDto() }
        } else {
            manufacturerRepository.findManufacturersByNameIgnoreCase(name.trim(), pageable).map { it.toDto() }
        }
    }

    fun getManufacturers(
        name: String?,
        id: Long?,
        pageable: Pageable,
    ): Page<ManufacturerDto> {
        return if (id != null) {
            PageImpl(listOf(manufacturerRepository.findByIdOrNull(id)?.toDto()))
        } else if (name.isNullOrBlank()) {
            manufacturerRepository.findAll(pageable).map { it.toDto() }
        } else {
            manufacturerRepository.findManufacturersByNameIgnoreCase(name.trim(), pageable).map { it.toDto() }
        }
    }
}
