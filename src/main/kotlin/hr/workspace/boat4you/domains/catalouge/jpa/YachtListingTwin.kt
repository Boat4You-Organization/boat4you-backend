package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * A second listing of a boat another channel already lists (materialized view yacht_listing_twin, V9_69; 26.9.2026
 * audit B17): the undated search, its facets and the charter facts skip [yachtId]; [canonicalYachtId] is the copy shown.
 */
@Entity
@Immutable
@Table(name = "yacht_listing_twin")
open class YachtListingTwin protected constructor() {
    @Id
    @Column(name = "yacht_id")
    open var yachtId: Long? = null
        protected set

    @Column(name = "canonical_yacht_id")
    open var canonicalYachtId: Long? = null
        protected set
}
