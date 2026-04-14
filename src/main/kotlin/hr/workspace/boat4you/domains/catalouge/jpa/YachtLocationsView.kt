package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable

/**
 * Mapping for DB view
 */
@Entity
@Immutable
@Table(name = "yacht_locations_view")
open class YachtLocationsView protected constructor() {
    @EmbeddedId
    open var id: YachtLocationsViewId? = null

    @Size(max = 255)
    @Column(name = "location_name")
    open var locationName: String? = null
        protected set

    @Size(max = 100)
    @Column(name = "country_name", length = 100)
    open var countryName: String? = null
        protected set

    @Size(max = 2)
    @Column(name = "country_code", length = 2)
    open var countryCode: String? = null
        protected set

    @Column(name = "country_id")
    open var countryId: Int? = null
        protected set

    @Size(max = 15)
    @Column(name = "continent", length = 15)
    open var continent: String? = null
        protected set
}
