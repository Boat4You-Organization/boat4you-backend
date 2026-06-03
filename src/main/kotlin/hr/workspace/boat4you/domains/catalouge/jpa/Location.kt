package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Formula
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.math.BigDecimal

@Entity
@Table(name = "location")
open class Location {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false)
    open var name: String? = null

    @Size(max = 2)
    @NotNull
    @Column(name = "country_code", nullable = false, length = 2)
    open var countryCode: String? = null

    @Column(name = "lat")
    open var lat: BigDecimal? = null

    @Column(name = "lon")
    open var lon: BigDecimal? = null

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.RESTRICT)
    @JoinColumn(name = "country_id", nullable = false)
    open var country: Country? = null

    @ManyToMany
    @JoinTable(
        name = "location_region",
        joinColumns = [JoinColumn(name = "location_id")],
        inverseJoinColumns = [JoinColumn(name = "region_id")],
    )
    open var regions: MutableSet<Region> = mutableSetOf()

    @Size(max = 100)
    @Column(name = "city", length = 100)
    open var city: String? = null

    // Read-only, DB-derived marina label: "name | city" when a city is present, else just name.
    // Backed by a STORED generated column (V9_16); the catalogue syncs only ever write name/city
    // and Postgres recomputes this, so a sync can never revert the label (the bug that kept wiping
    // our " | city" names). Read this for any user-facing marina label — name stays bare for keys.
    // @Formula (not @Column) so ddl-auto=validate never type-checks the generated column.
    @Formula("display_name")
    open var displayName: String? = null
}
