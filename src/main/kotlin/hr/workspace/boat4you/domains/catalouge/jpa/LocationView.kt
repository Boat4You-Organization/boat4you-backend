package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.LocationType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Immutable

/**
 * Mapping for DB view
 */
@Entity
@Immutable
@Table(name = "location_view")
open class LocationView protected constructor() {
    @Id
    @Column(name = "id", length = Integer.MAX_VALUE)
    open var id: String? = null
        protected set

    @Column(name = "real_id")
    open var realId: Long? = null
        protected set

    @Column(name = "name", length = Integer.MAX_VALUE)
    open var name: String? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = Integer.MAX_VALUE)
    open var locationType: LocationType? = null
        protected set

    @Size(max = 2)
    @Column(name = "country_code", length = 2)
    open var countryCode: String? = null
        protected set

    @Column(name = "search_filed", length = Integer.MAX_VALUE)
    open var searchFiled: String? = null
        protected set
}
