package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ExternalMappingService(
    private val externalMappingRepository: ExternalMappingRepository,
) {
    fun getAllMappingsByType(
        type: String,
        externalSystem: ExternalSystem,
    ): List<ExternalMapping> {
        return externalMappingRepository.findAllByTypeAndExternalSystem(type, externalSystem)
    }

    fun getAllMappingsByTypeAndExtendedType(
        type: String,
        externalSystem: ExternalSystem,
        extendedType: String,
    ): List<ExternalMapping> {
        return externalMappingRepository.findAllByTypeAndExternalSystemAndExtendedType(
            type,
            externalSystem,
            extendedType,
        )
    }

    @Cacheable("externalMappingCache")
    fun getCachedAllMappingsByType(
        type: String,
        externalSystem: ExternalSystem,
    ): List<ExternalMapping> {
        return externalMappingRepository.findAllByTypeAndExternalSystem(type, externalSystem)
    }

    @Cacheable("externalMappingExtendedCache")
    fun getCachedAllMappingsByTypeAndExtendedType(
        type: String,
        externalSystem: ExternalSystem,
        extendedType: String,
    ): List<ExternalMapping> {
        return externalMappingRepository.findAllByTypeAndExternalSystemAndExtendedType(
            type,
            externalSystem,
            extendedType,
        )
    }

    fun findBySystemIdAndExternalSystemAndType(
        systemId: Long,
        externalSystem: ExternalSystem,
        type: String,
    ): ExternalMapping? {
        return externalMappingRepository.findBySystemIdAndExternalSystemAndType(systemId, externalSystem, type)
    }

    fun findAllByTypeAndExternalSystemAndExternalIdIn(
        type: String,
        externalSystemId: Int,
        externalIds: List<Long>,
    ): List<ExternalMapping> {
        return externalMappingRepository.findAllByTypeAndExternalSystemIdAndExternalIdIn(
            type,
            externalSystemId,
            externalIds,
        )
    }

    @Transactional
    fun saveMapping(
        externalId: Long,
        systemId: Long,
        externalSystem: ExternalSystem,
        type: String,
        extendedType: String? = null,
    ) {
        externalMappingRepository.saveAndFlush(
            ExternalMapping(
                externalId = externalId,
                externalSystem = externalSystem,
                systemId = systemId,
                type = type,
                extendedType = extendedType,
            ),
        )
    }

    @Transactional
    fun migrateMappings(
        oldSystemId: Long,
        newSystemId: Long,
        type: String,
    ) {
        val mapping =
            externalMappingRepository.findBySystemIdAndType(
                oldSystemId,
                type,
            ) ?: throw RuntimeException("Can't find mapping for systemId $oldSystemId and type $type")
        mapping.systemId = newSystemId
        externalMappingRepository.save(mapping)
    }
}
