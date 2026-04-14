package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.ModelDto
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrNull

@Service
@Transactional(readOnly = true)
class ModelQueryingService(
    private val modelRepository: ModelRepository,
) {
    fun getModelsByManufacturerId(
        manufacturerIds: List<Long>,
        name: String?,
        pageable: Pageable,
    ): Page<ModelDto> {
        return modelRepository
            .findAllByManufacturerIdAndNameIgnoreCase(manufacturerIds, name?.trim(), pageable)
            .map { it.toDto() }
    }

    fun getModelsById(modelId: Long): Page<ModelDto> {
        val model = modelRepository.findByIdOrNull(modelId)?.toDto()
        return PageImpl(listOf(model))
    }

    @Cacheable(value = ["modelByExternalIdAndExternalSystem"], unless = "#result == null")
    fun findModelByExternalIdAndExternalSystem(
        externalId: Long,
        externalSystemId: Long,
    ): Model? {
        return modelRepository.findModelByExternalIdAndExternalSystem(externalId, externalSystemId).getOrNull()
    }

    @Cacheable(value = ["modelByName"], unless = "#result == null")
    fun findByNameIgnoreCaseAndExternalManufacturerId(
        name: String,
        manufacturerExternalId: Long,
        manufacturerExternalSystemId: Int,
    ): Model? {
        return modelRepository.findByNameIgnoreCaseAndExternalManufacturerId(name, manufacturerExternalId, manufacturerExternalSystemId)
    }
}
