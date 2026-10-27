package hr.workspace.boat4you.domains.contactcenter.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/**
 * One charter search with the criteria the visitor gave (IRIS tool call, site search or form) and how many boats it
 * found. `estimated` marks rows rebuilt from old chat text, `flagged` rows of a security-flagged session.
 */
@Entity
@Table(name = "cc_search")
open class CcSearch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false)
    open var source: SearchSource? = null

    @Column(name = "site_id", length = 40, nullable = false)
    open var siteId: String? = null

    @Column(name = "chat_session_id")
    open var chatSessionId: Long? = null

    @Column(name = "visit_id")
    open var visitId: Long? = null

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 2)
    open var countryCode: String? = null

    @Column(name = "location", length = 80)
    open var location: String? = null

    @Column(name = "did")
    open var did: Long? = null

    @Column(name = "start_date")
    open var startDate: LocalDate? = null

    @Column(name = "end_date")
    open var endDate: LocalDate? = null

    @Column(name = "min_persons")
    open var minPersons: Int? = null

    @Column(name = "min_cabins")
    open var minCabins: Int? = null

    @Column(name = "min_price_eur")
    open var minPriceEur: Int? = null

    @Column(name = "max_price_eur")
    open var maxPriceEur: Int? = null

    @Column(name = "vessel_type", length = 30)
    open var vesselType: String? = null

    @Column(name = "equipment")
    open var equipment: String? = null

    @Column(name = "result_count")
    open var resultCount: Int? = null

    @Column(name = "total_available")
    open var totalAvailable: Int? = null

    @Column(name = "searched_area", length = 120)
    open var searchedArea: String? = null

    @Column(name = "flagged", nullable = false)
    open var flagged: Boolean = false

    @Column(name = "estimated", nullable = false)
    open var estimated: Boolean = false

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}
