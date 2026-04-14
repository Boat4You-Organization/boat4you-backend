package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.LocationViewDto
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.AllLocationView
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationView

fun Location.toDto(): LocationDto =
    LocationDto(
        id = "l-$id",
        name = name,
        countryCode = countryCode,
    )

fun Location.toLocationViewDto(): LocationViewDto =
    LocationViewDto(
        id = "l-$id",
        realId = id,
        name = name,
        locationType = LocationType.MARINA,
        countryCode = countryCode,
    )

fun LocationView.toLocationViewDto(): LocationViewDto =
    LocationViewDto(
        id = id,
        realId = realId,
        name = name,
        locationType = locationType,
        countryCode = countryCode,
    )

fun AllLocationView.toLocationViewDto(): LocationViewDto =
    LocationViewDto(
        id = id,
        realId = realId,
        name = name,
        locationType = locationType,
        countryCode = countryCode,
    )
