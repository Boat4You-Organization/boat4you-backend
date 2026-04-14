package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.CreateCustomOfferDto
import hr.workspace.boat4you.domains.catalouge.dto.CustomOfferDto
import hr.workspace.boat4you.domains.catalouge.services.CustomOfferMutationService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/custom-offers")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
class AdminCustomOfferController(
    private val customOfferMutationService: CustomOfferMutationService,
) {
    @PostMapping()
    fun createCustomOffer(
        @RequestBody @Valid customOfferDto: CreateCustomOfferDto,
    ): ResponseEntity<CustomOfferDto> {
        return ResponseEntity.ok(customOfferMutationService.createNewCustomOffer(customOfferDto))
    }
}
