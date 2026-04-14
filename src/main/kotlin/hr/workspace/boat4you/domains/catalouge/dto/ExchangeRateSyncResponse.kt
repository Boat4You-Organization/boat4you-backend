package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

data class ExchangeRateSyncResponse(
    @get:JsonProperty("date")
    val date: String,
    @get:JsonProperty("rates")
    val rates: Map<String, BigDecimal>,
)
