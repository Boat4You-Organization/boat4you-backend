package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.Size
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction

/**
 * Currently only for nausys bases
 */
@Entity
@Table(name = "external_bases")
open class ExternalBase {
    @Id
    @Column(name = "id", columnDefinition = "SERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "external_id")
    open var externalId: Long? = null

    /**
     * This will always be nausys
     */
    @Column(name = "external_system_id")
    open var externalSystemId: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "agency_id")
    open var agency: Agency? = null

    @Column(name = "ext_agency_id")
    open var extAgencyId: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "location_id")
    open var location: Location? = null

    @Column(name = "ext_location_id")
    open var extLocationId: Long? = null

    @Size(max = 20)
    @Column(name = "checkin_time", length = 20)
    open var checkinTime: String? = null

    @Size(max = 20)
    @Column(name = "checkout_time", length = 20)
    open var checkoutTime: String? = null
}
