package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
@Table(name = "custom_yacht_view")
open class CustomYachtView protected constructor() {
    @Id
    @Column(name = "id")
    open var id: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "name")
    open var name: String? = null
        protected set

    @Column(name = "model_id")
    open var modelId: Long? = null
        protected set

    @Size(max = 255)
    @Column(name = "model_name")
    open var modelName: String? = null
        protected set

    @Column(name = "country_id")
    open var countryId: Int? = null
        protected set

    @Column(name = "country_name", length = Integer.MAX_VALUE)
    open var countryName: String? = null
        protected set

    @Size(max = 2)
    @Column(name = "country_code", length = 2)
    open var countryCode: String? = null
        protected set

    @Column(name = "low_price")
    open var lowPrice: BigDecimal? = null
        protected set

    @Size(max = 255)
    @Column(name = "manufacturer_name")
    open var manufacturerName: String? = null
        protected set

    @Size(max = 20)
    @Column(name = "country_key", length = 20)
    open var countryKey: String? = null
        protected set
}
