package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.EquipmentDto
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasDto
import hr.workspace.boat4you.domains.catalouge.dto.FiltersDto
import hr.workspace.boat4you.domains.catalouge.dto.ManufacturerDto
import hr.workspace.boat4you.domains.catalouge.dto.MeasurementUnitDto
import hr.workspace.boat4you.domains.catalouge.dto.ModelDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceInfoDto
import hr.workspace.boat4you.domains.catalouge.dto.VesselTypeYachtCountDto
import hr.workspace.boat4you.domains.catalouge.enums.CharterType
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.MeasurementUnit
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.services.FiltersQueryingService
import hr.workspace.boat4you.domains.catalouge.services.ManufacturerQueryingService
import hr.workspace.boat4you.domains.catalouge.services.ModelQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import hr.workspace.boat4you.domains.catalouge.services.toDto
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Catalogue", description = "Catalogue operations for manufacturers and models")
@RestController
@RequestMapping("/public/catalogue")
class CatalogueController(
    private val manufacturerQueryingService: ManufacturerQueryingService,
    private val modelQueryingService: ModelQueryingService,
    private val yachtQueryingService: YachtQueryingService,
    private val equipmentRepository: EquipmentRepository,
    private val extraRepository: ExtraRepository,
    private val filtersQueryingService: FiltersQueryingService,
    private val userRepository: UserRepository,
) {
    @GetMapping("/filters")
    fun filters(
        @RequestParam(name = "currency", required = false) curr: String?,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<FiltersDto> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val language = LanguageEnum.getLanguage(lang, user)
        val currency = CurrencyEnum.getCurrency(curr, user)

        val result =
            filtersQueryingService.getFilters(
                currency = currency,
                language = language,
            )
        return ResponseEntity.ok(result)
    }

    @GetMapping("/manufacturers")
    fun getManufacturers(
        @RequestParam(required = false) name: String?,
        @PageableDefault(sort = ["name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<PagedModel<ManufacturerDto>> {
        return ResponseEntity.ok(PagedModel(manufacturerQueryingService.getManufacturersCached(name, pageable)))
    }

    @GetMapping("/models")
    fun getModels(
        @RequestParam(required = true) manufacturerIds: List<Long>,
        @RequestParam(required = false) name: String?,
        @PageableDefault(sort = ["name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<PagedModel<ModelDto>> {
        return ResponseEntity.ok(
            PagedModel(
                modelQueryingService.getModelsByManufacturerId(
                    manufacturerIds,
                    name,
                    pageable,
                ),
            ),
        )
    }

    @Operation(description = "Get all used vessel types")
    @GetMapping("/vesselTypes")
    fun getUsedVesselTypes(): List<VesselType> {
        return yachtQueryingService.getUsedVesselTypes()
    }

    @Operation(description = "Get vessel type count")
    @GetMapping("/type-count")
    fun getVesselTypeYachtCount(): List<VesselTypeYachtCountDto> {
        return yachtQueryingService.getVesselTypeYachtCount()
    }

    @Operation(description = "Get all used charter types")
    @GetMapping("/charterTypes")
    fun getUsedCharterTypes(): List<CharterType> {
        return yachtQueryingService.getUsedCharterTypes()
    }

    @Operation(description = "Get amenities for filtering yachts")
    @GetMapping("/amenities")
    fun getEquipment(): List<EquipmentDto> {
        return equipmentRepository.findForFilters().map { it.toDto() }
    }

    @Operation(description = "Get services for filtering yachts")
    @GetMapping("/services")
    fun getExtras(): List<ExtrasDto> {
        return extraRepository.findForFilters().map { it.toDto() }
    }

    @Operation(description = "Get all amenities")
    @GetMapping("/all-amenities")
    fun getAllEquipment(): List<EquipmentDto> {
        return equipmentRepository.findAll().map { it.toDto() }
    }
}
