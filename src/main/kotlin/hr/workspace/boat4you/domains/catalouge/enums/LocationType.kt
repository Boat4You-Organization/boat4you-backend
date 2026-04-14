package hr.workspace.boat4you.domains.catalouge.enums

import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Region

enum class LocationType(
    val value: String,
) {
    MARINA(Location::class.simpleName.toString()),
    COUNTRY(Country::class.simpleName.toString()),
    REGION(Region::class.simpleName.toString()),
}
