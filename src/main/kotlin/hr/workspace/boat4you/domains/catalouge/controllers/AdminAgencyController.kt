package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.AgencyDto
import hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto
import hr.workspace.boat4you.domains.catalouge.enums.CountryIsoEnum
import hr.workspace.boat4you.domains.catalouge.services.AgencyMutationService
import hr.workspace.boat4you.domains.catalouge.services.AgencyQueryingService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Agency Management", description = "Agency management operations for administrators")
@RestController
@RequestMapping("/admin/agencies")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminAgencyController(
    private val agencyQueryingService: AgencyQueryingService,
    private val agencyMutationService: AgencyMutationService,
) {
    @GetMapping
    fun getAllAgencies(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) countryCode: CountryIsoEnum?,
        @RequestParam(required = false) primarySource: ExternalSystemEnum?,
        @PageableDefault(sort = ["name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<PagedModel<AgencyDto>> {
        return ResponseEntity.ok(
            PagedModel(
                agencyQueryingService.getAllAgencies(
                    name,
                    active,
                    countryCode,
                    primarySource,
                    pageable,
                ),
            ),
        )
    }

    @GetMapping("/{id}")
    fun getAgencyById(
        @PathVariable id: Long,
    ): ResponseEntity<AgencyDto> {
        return ResponseEntity.ok(agencyQueryingService.getAgencyById(id))
    }

    @PutMapping("/{id}")
    fun updateAgency(
        @PathVariable id: Long,
        @RequestBody agency: AgencyDto,
    ): ResponseEntity<AgencyDto> {
        return ResponseEntity.ok(agencyMutationService.updateAgency(id, agency))
    }

    @PatchMapping("/{id}/activate")
    fun activate(
        @PathVariable id: Long,
    ): ResponseEntity<AgencyDto> {
        return ResponseEntity.ok(agencyMutationService.toggleActive(id, true))
    }

    @PatchMapping("/{id}/deactivate")
    fun deactivate(
        @PathVariable id: Long,
    ): ResponseEntity<AgencyDto> {
        return ResponseEntity.ok(agencyMutationService.toggleActive(id, false))
    }

    @GetMapping("/{id}/yachts")
    fun getYachtsByAgencyId(
        @PathVariable id: Long,
    ): ResponseEntity<List<AgencyYachtDto>> {
        return ResponseEntity.ok(agencyQueryingService.getYachtsByAgencyId(id))
    }

    @PostMapping("/{id}/yachts")
    fun updateYachtsDiscount(
        @PathVariable id: Long,
        @RequestBody yachts: List<AgencyYachtDto>,
    ): ResponseEntity<List<AgencyYachtDto>> {
        agencyMutationService.updateYachtsDiscount(id, yachts)
        return ResponseEntity.ok(agencyQueryingService.getYachtsByAgencyId(id))
    }

    @PostMapping("/{id}/recalculate-prices")
    fun recalculatePrices(
        @PathVariable id: Long,
    ): ResponseEntity<Map<String, Any>> {
        val updated = agencyMutationService.recalculatePricesForAgency(id)
        return ResponseEntity.ok(mapOf("updatedOffers" to updated))
    }
}
