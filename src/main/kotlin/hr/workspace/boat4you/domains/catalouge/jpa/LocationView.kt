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
import java.math.BigDecimal

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

    /** REGION only: other known spellings, '|'-separated (R__1_07, region_alias). */
    @Column(name = "aliases", length = Integer.MAX_VALUE)
    open var aliases: String? = null
        protected set

    /** MARINA only: the dual-source merge's same-place check (MarinaPlaces). */
    @Column(name = "city", length = Integer.MAX_VALUE)
    open var city: String? = null
        protected set

    @Column(name = "lat")
    open var lat: BigDecimal? = null
        protected set

    @Column(name = "lon")
    open var lon: BigDecimal? = null
        protected set
}
