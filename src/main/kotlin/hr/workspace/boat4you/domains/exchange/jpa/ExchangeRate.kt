package hr.workspace.boat4you.domains.exchange.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "exchange_rate")
open class ExchangeRate {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @NotNull
    @Column(name = "valid_at", nullable = false)
    open var validAt: LocalDate? = null

    @Size(max = 3)
    @NotNull
    @Column(name = "currency", nullable = false, length = 3)
    open var currency: String? = null

    @NotNull
    @Column(name = "rate", nullable = false)
    open var rate: BigDecimal? = null
}
