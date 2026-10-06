package hr.workspace.boat4you.domains.catalouge.dto

import hr.workspace.boat4you.domains.catalouge.enums.SailKind
import java.math.BigDecimal

// Capacity contract v1 (6.10.2026), section 2: what the partner (MMK / NauSys) says about cabins, berths, WC and the
// rig, faithfully, on every surface. Field names must never match /agency|external|partner|company|source|mmk|nausys|
// operator/i: the sisters' withoutPartnerIds strips such keys silently.

/**
 * Capacity of one listing as its own partner gives it; every figure > 0 or null (unknown, 0 and negative are hidden).
 * NauSys crew cabins / crew WC / crew showers are separately labelled figures, NEVER added to cabins / WC / showers
 * (critique B-6). [maxPersons] is the partner's "max people on board", never derived.
 */
data class CapacityDto(
    val cabins: CapacityDimDto?,
    val berths: CapacityDimDto?,
    /** WC. */
    val heads: CapacityDimDto?,
    val crewCabins: Short?,
    val crewHeads: Short?,
    val showers: Short?,
    val crewShowers: Short?,
    val maxPersons: Short?,
    val recommendedPersons: Short?,
    val crewNumber: Short?,
)

/**
 * One capacity figure: the partner's number exactly as sent, its note (MMK only; sanitized; on search rows only a
 * short language-neutral one like "8+2"), and the parts that add up exactly to the number when the note or the NauSys
 * berths breakdown says so.
 */
data class CapacityDimDto(
    val value: Short,
    val note: String?,
    val split: CapacitySplitDto?,
)

/** Every key present; null = no such part; at least 2 parts; the parts add up to the figure. */
data class CapacitySplitDto(
    val guests: Short?,
    val inCabins: Short?,
    val saloon: Short?,
    val crew: Short?,
    val skipper: Short?,
)

/** Sails, engine and draught as the partner gives them (detail, reservation; not on search rows). */
data class RigDto(
    val mainsail: SailDto?,
    val headsail: SailDto?,
    val engine: EngineDto?,
    /** Metres. */
    val draught: BigDecimal?,
)

/** [label] (the partner's English label, sanitized) only when [kind] is null - a label outside the closed set. */
data class SailDto(
    val kind: SailKind?,
    val label: String?,
)

/** MMK: [label] verbatim (only with a power unit, sanitized). NauSys: [count] x [powerEach] (hp; unit undocumented). */
data class EngineDto(
    val label: String?,
    val count: Short?,
    val powerEach: BigDecimal?,
)

/**
 * ADMIN ONLY (search rows, SYSTEM_ADMIN): the partner's raw stored notes and internal remark, unsanitized, so the
 * broker sees what the partner wrote. Shown behind an info icon; never copied into the offer e-mail or any customer
 * text. Null for everyone else.
 */
data class BrokerNotesDto(
    val cabinsNote: String?,
    val berthsNote: String?,
    val headsNote: String?,
    val remark: String?,
)
