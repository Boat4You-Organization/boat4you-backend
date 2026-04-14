package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

/**
 * Used for custom boats as they can be related to multiple countries and locations.
 */
@Entity
@Table(name = "yacht_locations")
open class YachtLocation {
    @EmbeddedId
    open var id: YachtLocationId? = null

    @MapsId("locationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "location_id", nullable = false)
    open var location: Location? = null

    @MapsId("yachtId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "yacht_id", nullable = false)
    open var yacht: Yacht? = null
}
