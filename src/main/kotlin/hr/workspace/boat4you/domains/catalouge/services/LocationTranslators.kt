package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.LocationCoordinates
import hr.workspace.boat4you.domains.catalouge.dto.LocationDto
import hr.workspace.boat4you.domains.catalouge.dto.LocationViewDto
import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import hr.workspace.boat4you.domains.catalouge.jpa.AllLocationView
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.LocationView
import java.math.BigDecimal

/**
 * Coordinates a client can use, or null: both present and not 0,0 - the partners' "unknown" (MarinaPlaces reads 0,0
 * the same way; the syncs never store it, Mario 12.7.2026).
 */
fun coordinatesOf(
    lat: BigDecimal?,
    lon: BigDecimal?,
): LocationCoordinates? {
    if (lat == null || lon == null || (lat.signum() == 0 && lon.signum() == 0)) return null
    return LocationCoordinates(lat.toDouble(), lon.toDouble())
}

/** The same [LocationDto] with the coordinates of its location id ("63" on a search card, "l-63" elsewhere). */
fun LocationDto.withCoordinates(coordinates: Map<Long, LocationCoordinates>): LocationDto {
    val found = id?.removePrefix("l-")?.toLongOrNull()?.let { coordinates[it] } ?: return this
    return copy(lat = found.lat, lon = found.lon)
}

fun Location.toDto(): LocationDto {
    val coordinates = coordinatesOf(lat, lon)
    return LocationDto(
        id = "l-$id",
        name = displayName ?: name,
        countryCode = countryCode,
        lat = coordinates?.lat,
        lon = coordinates?.lon,
    )
}

fun Location.toLocationViewDto(): LocationViewDto =
    LocationViewDto(
        id = "l-$id",
        realId = id,
        name = displayName ?: name,
        locationType = LocationType.MARINA,
        countryCode = countryCode,
    )

fun LocationView.toLocationViewDto(): LocationViewDto {
    val coordinates = coordinatesOf(lat, lon)
    return LocationViewDto(
        id = id,
        realId = realId,
        name = name,
        locationType = locationType,
        countryCode = countryCode,
        aliases = aliases?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() },
        city = city,
        lat = coordinates?.lat,
        lon = coordinates?.lon,
    )
}

fun AllLocationView.toLocationViewDto(): LocationViewDto =
    LocationViewDto(
        id = id,
        realId = realId,
        name = name,
        locationType = locationType,
        countryCode = countryCode,
    )
