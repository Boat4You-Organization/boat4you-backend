package hr.workspace.boat4you.domains.catalouge.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
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
    // Partner row id, SYSTEM_ADMIN only (null for everyone else — MMK ids end in the operator's company id).
    // The admin offer card matches a catalogue row to the offer row of the same partner charge by it when the
    // partner renamed the charge ("Premium Line Pack (… Outboard Engine)" -> "(… Outboard Engine; 1 SUP)",
    // listed twice in a client offer e-mail, 29.9.2026), the same identity the price calc merges on.
    // As a string: MMK ids exceed 2^53 and would be rounded by JSON.parse in the browser.
    @field:JsonSerialize(using = ToStringSerializer::class)
    val externalId: Long? = null,
)
