package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.dto.LocationCountDto
import hr.workspace.boat4you.domains.catalouge.dto.LocationViewDto
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Locations", description = "Location operations for querying locations and countries")
@RestController
@RequestMapping("/public")
class LocationController(
    private val locationQueryingService: LocationQueryingService,
) {
    @Operation(description = "Get locations with optional filtering by name")
    @GetMapping("/locations")
    fun getLocationsViews(
        @RequestParam(value = "name", required = false) name: String?,
        @PageableDefault(size = 20, sort = ["locationType", "name"], direction = Sort.Direction.ASC) pageable: Pageable,
        @RequestParam(value = "selected", required = false) selectedLocations: List<String>?,
    ): ResponseEntity<PagedModel<LocationViewDto>> {
        return ResponseEntity.ok(
            PagedModel(
                locationQueryingService.getLocationViews(
                    name,
                    selectedLocations,
                    pageable,
                ),
            ),
        )
    }

    @Operation(description = "Get all countries with optional filtering by name")
    @GetMapping("/all-countries")
    fun getAllCountries(
        @RequestParam(value = "name", required = false) name: String?,
        @PageableDefault(size = 20, sort = ["locationType", "name"], direction = Sort.Direction.ASC) pageable: Pageable,
    ): ResponseEntity<PagedModel<LocationViewDto>> {
        return ResponseEntity.ok(PagedModel(locationQueryingService.getAllCountries(name, pageable)))
    }

    @Operation(description = "Get all countries where yachts are located")
    @GetMapping("/countries")
    fun getCountries(): ResponseEntity<List<LocationViewDto>> {
        return ResponseEntity.ok(locationQueryingService.getCountries())
    }

    @Operation(description = "Get all regions where yachts are located")
    @GetMapping("/regions")
    fun getRegions(
        @RequestParam(value = "countryCode", required = true) countryCode: String,
    ): ResponseEntity<List<LocationViewDto>> {
        return ResponseEntity.ok(locationQueryingService.getRegions(countryCode))
    }

    @Operation(description = "Get countries with their yacht counts. Returns only countries with yachts")
    @GetMapping("/countries-count")
    fun getCountriesCount(): List<LocationCountDto> {
        return locationQueryingService.getCountriesCount()
    }

    @Operation(description = "Get locations with their yacht counts. Returns only locations with yachts")
    @GetMapping("/locations-count")
    fun getLocationsCount(): List<LocationCountDto> {
        return locationQueryingService.getLocationsCount()
    }
}
