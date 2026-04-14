package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.AgencyDto
import hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto
import hr.workspace.boat4you.domains.catalouge.enums.CountryIsoEnum
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.jvm.optionals.getOrElse

@Service
@Transactional(readOnly = true)
class AgencyQueryingService(
    private val agencyRepository: AgencyRepository,
    private val yachtRepository: YachtRepository,
) {
    fun getAllAgencies(
        name: String?,
        active: Boolean?,
        countryCode: CountryIsoEnum?,
        primarySource: ExternalSystemEnum?,
        pageable: Pageable,
    ): Page<AgencyDto> {
        return agencyRepository
            .findAllByParamsForAdmin(
                name = name,
                active = active,
                countryCode = countryCode?.name,
                primarySource = primarySource?.value,
                pageable = pageable,
            ).map { it.toDto() }
    }

    fun getAgencyById(id: Long): AgencyDto {
        return agencyRepository.findById(id).map { it.toDto() }.getOrElse { throw AgencyDoesNotExistException() }
    }

    fun getYachtsByAgencyId(id: Long): List<AgencyYachtDto> {
        val agency = agencyRepository.findById(id).orElseThrow { AgencyDoesNotExistException() }
        return yachtRepository.findAllByAgencyIdToDto(agency.id!!)
    }
}
