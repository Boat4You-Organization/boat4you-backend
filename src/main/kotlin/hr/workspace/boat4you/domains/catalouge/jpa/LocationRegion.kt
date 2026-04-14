package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * In MMK one location can have relation to multiple sailing areas
 */
@Entity
@Table(name = "location_region")
open class LocationRegion {
    @EmbeddedId
    open var id: LocationRegionId? = null
}
