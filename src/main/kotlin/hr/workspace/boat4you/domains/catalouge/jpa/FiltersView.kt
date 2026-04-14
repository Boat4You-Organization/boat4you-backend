package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal

/**
 * Mapping for DB view
 */
@Entity
@Immutable
@Table(name = "filters_view")
open class FiltersView protected constructor() {
    @Id
    @Column(name = "id")
    open var id: Int? = null
        protected set

    @Column(name = "min_price")
    open var minPrice: BigDecimal? = null
        protected set

    @Column(name = "max_price")
    open var maxPrice: BigDecimal? = null
        protected set

    @Column(name = "min_cabins")
    open var minCabins: Short? = null
        protected set

    @Column(name = "max_cabins")
    open var maxCabins: Short? = null
        protected set

    @Column(name = "min_persons")
    open var minPersons: Short? = null
        protected set

    @Column(name = "max_persons")
    open var maxPersons: Short? = null
        protected set

    @Column(name = "min_berths")
    open var minBerths: Short? = null
        protected set

    @Column(name = "max_berths")
    open var maxBerths: Short? = null
        protected set

    @Column(name = "min_lenght")
    open var minLenght: BigDecimal? = null
        protected set

    @Column(name = "max_lenght")
    open var maxLenght: BigDecimal? = null
        protected set

    @Column(name = "min_build_year")
    open var minBuildYear: Short? = null
        protected set

    @Column(name = "max_build_year")
    open var maxBuildYear: Short? = null
        protected set

    @Column(name = "min_wc")
    open var minWc: Short? = null
        protected set

    @Column(name = "max_wc")
    open var maxWc: Short? = null
        protected set

    @Column(name = "min_engine_power")
    open var minEnginePower: Short? = null
        protected set

    @Column(name = "max_engine_power")
    open var maxEnginePower: Short? = null
        protected set
}
