package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.ColumnDefault
import java.math.BigDecimal
import kotlin.jvm.Transient

@Entity
@Table(name = "agency")
open class Agency {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Size(max = 255)
    @NotNull
    @Column(name = "name", nullable = false)
    open var name: String? = null

    @Size(max = 255)
    @Column(name = "address")
    open var address: String? = null

    @Size(max = 150)
    @Column(name = "city", length = 150)
    open var city: String? = null

    @Size(max = 100)
    @Column(name = "country", length = 100)
    open var country: String? = null

    @Size(max = 30)
    @Column(name = "zip", length = 30)
    open var zip: String? = null

    @Size(max = 100)
    @Column(name = "vat_code", length = 100)
    open var vatCode: String? = null

    @Size(max = 255)
    @Column(name = "web")
    open var web: String? = null

    @Size(max = 150)
    @Column(name = "email", length = 150)
    open var email: String? = null

    @Size(max = 200)
    @Column(name = "phone", length = 200)
    open var phone: String? = null

    @Size(max = 200)
    @Column(name = "mobile", length = 200)
    open var mobile: String? = null

    @Size(max = 34)
    @Column(name = "iban", length = 34)
    open var iban: String? = null

    @NotNull
    @Column(name = "active", nullable = false)
    open var active: Boolean? = false

    @OneToMany(mappedBy = "agency")
    open var agencySources: MutableSet<AgencySource> = mutableSetOf()

    /**
     * Boat4you discount for given agency
     */
    @Column(name = "discount")
    open var discount: BigDecimal? = null

    @Size(max = 100)
    @Column(name = "director", length = 100)
    open var director: String? = null

    /**
     * Some agencies do not use external systems for booking. They will cancel the booking in external system manually.
     */
    @NotNull
    @ColumnDefault("false")
    @Column(name = "skip_external_system", nullable = false)
    open var skipExternalSystem: Boolean? = false

    @Transient
    open var primarySource: AgencySource? = null
        get() = agencySources.find { it.primary == true }

    @Size(max = 255)
    @Column(name = "bank_accounts")
    open var bankAccounts: String? = null

    fun getExternalId(): Long? {
        return primarySource?.externalId
    }

    fun getDiscountOrZero(): BigDecimal {
        return discount ?: BigDecimal.ZERO
    }
}
