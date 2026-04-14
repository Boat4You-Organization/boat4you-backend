package hr.workspace.boat4you.domains.exchange.jpa

import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

interface ExchangeRateRepository : JpaRepository<ExchangeRate, Long> {
    @Cacheable("exchangeRateForCurrency", unless = "#result == null")
    @Query(
        """
           SELECT e FROM ExchangeRate e
           WHERE e.currency = :currency
           ORDER BY e.validAt DESC
           LIMIT 1
           """,
    )
    fun findLatestByCurrency(
        @Param("currency") currency: String,
    ): ExchangeRate?
}
