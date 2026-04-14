package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.EquipmentAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.ManufacturerDto
import hr.workspace.boat4you.domains.catalouge.dto.ModelDto
import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.services.ManufacturerQueryingService
import hr.workspace.boat4you.domains.catalouge.services.ModelQueryingService
import hr.workspace.boat4you.domains.catalouge.services.toAdminDto
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/catalogue")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCatalogueController(
    private val equipmentRepository: EquipmentRepository,
    private val extraRepository: ExtraRepository,
    private val manufacturerQueryingService: ManufacturerQueryingService,
    private val modelQueryingService: ModelQueryingService,
) {
    @GetMapping("/extras")
    fun getAllExtras(
        @PageableDefault(
            sort = ["id"],
            direction = Sort.Direction.ASC,
        ) pageable: Pageable,
    ): ResponseEntity<PagedModel<ExtrasAdminDto>> {
        return ResponseEntity.ok(PagedModel(extraRepository.findAll(pageable).map { it.toAdminDto() }))
    }

    @GetMapping("/amenities")
    fun getAllEquipment(
        @PageableDefault(
            sort = ["id"],
            direction = Sort.Direction.ASC,
        ) pageable: Pageable,
    ): ResponseEntity<PagedModel<EquipmentAdminDto>> {
        return ResponseEntity.ok(PagedModel(equipmentRepository.findAll(pageable).map { it.toAdminDto() }))
    }

    @GetMapping("/manufacturers")
    fun getManufacturers(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) id: Long?,
        @PageableDefault(sort = ["name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<PagedModel<ManufacturerDto>> {
        return ResponseEntity.ok(PagedModel(manufacturerQueryingService.getManufacturers(name, id, pageable)))
    }

    @GetMapping("/models")
    fun getModels(
        @RequestParam(required = false) manufacturerIds: List<Long>?,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) id: Long?,
        @PageableDefault(sort = ["name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<PagedModel<ModelDto>> {
        return if (id != null) {
            ResponseEntity.ok(PagedModel(modelQueryingService.getModelsById(id)))
        } else {
            ResponseEntity.ok(
                PagedModel(
                    modelQueryingService.getModelsByManufacturerId(
                        manufacturerIds!!,
                        name,
                        pageable,
                    ),
                ),
            )
        }
    }
}
