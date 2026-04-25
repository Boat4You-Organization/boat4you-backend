package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import java.math.BigDecimal

data class YachtExtrasDto(
    val id: Long,
    val name: String?,
    val payableInBase: Boolean,
    val obligatory: Boolean,
    val priceEur: BigDecimal,
    val priceInfo: PriceInfoDto?,
    val unit: ExtrasUnitType?,
    val extras: ExtrasDto?,
    val key: String,
    val isStartingPrice: Boolean?,
    // Free-form partner description shown as small print beneath the name
    // (e.g. MMK "FUN PACK A [Jokerboat Coaster 470 + 70HP; deposit €1000;
    // Croatian waters only]" or Nausys service.description). Null when the
    // partner sent none or the row predates V1_52 and hasn't been re-synced.
    val description: String? = null,
    // Refined payment classification (V1_57) — replaces overloaded
    // payableInBase boolean for customer-facing display. Frontend groups
    // extras into per-bucket sections (Included / With booking / Advance
    // to operator / On-site). Null only on entirely-missed rows.
    val paymentType: hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType? = null,
)
