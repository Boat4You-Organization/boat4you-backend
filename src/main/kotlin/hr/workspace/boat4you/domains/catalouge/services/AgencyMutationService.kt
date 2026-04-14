package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.AgencyDto
import hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrElse

@Service
@Transactional
class AgencyMutationService(
    private val agencyRepository: AgencyRepository,
    private val yachtRepository: YachtRepository,
) {
    fun updateAgency(
        id: Long,
        agency: AgencyDto,
    ): AgencyDto {
        val dbAgency = agencyRepository.findById(id).getOrElse { throw AgencyDoesNotExistException() }

        dbAgency.apply {
            updateBlockWithModel(agency)
        }

        return agencyRepository.save(dbAgency).toDto()
    }

    fun toggleActive(
        id: Long,
        isActive: Boolean,
    ): AgencyDto {
        val dbAgency = agencyRepository.findById(id).getOrElse { throw AgencyDoesNotExistException() }

        dbAgency.active = isActive

        return agencyRepository.save(dbAgency).toDto()
    }

    fun updateYachtsDiscount(
        agencyId: Long,
        yachtDtos: List<AgencyYachtDto>,
    ) {
        val dbAgency = agencyRepository.findById(agencyId).getOrElse { throw AgencyDoesNotExistException() }

        // Not optimized as its used only in an admin panel
        val allYachts = yachtRepository.findAllByAgencyId(dbAgency.id!!)
        allYachts.forEach { yacht ->
            val dto = yachtDtos.firstOrNull { it.id == yacht.id }
            if (dto != null) {
                yacht.excludeDiscount = dto.excludeDiscount
            } else {
                return@forEach
            }
            yachtRepository.save(yacht)
        }
    }
}
