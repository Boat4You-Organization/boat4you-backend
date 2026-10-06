package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.dto.BrokerNotesDto
import hr.workspace.boat4you.domains.catalouge.dto.CapacityDimDto
import hr.workspace.boat4you.domains.catalouge.dto.CapacityDto
import hr.workspace.boat4you.domains.catalouge.dto.CapacitySplitDto
import hr.workspace.boat4you.domains.catalouge.dto.EngineDto
import hr.workspace.boat4you.domains.catalouge.dto.RigDto
import hr.workspace.boat4you.domains.catalouge.dto.SailDto
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import org.springframework.stereotype.Component
import java.math.BigDecimal

/** The yacht columns the capacity / rig blocks are derived from (the entity, or a per-page row of the listing). */
data class CapacityColumns(
    val cabins: Short? = null,
    val crewCabins: Short? = null,
    val wc: Short? = null,
    val crewWc: Short? = null,
    val berths: Short? = null,
    val crewBerths: Short? = null,
    val cabinBerths: Short? = null,
    val salonBerths: Short? = null,
    val showers: Short? = null,
    val crewShowers: Short? = null,
    val maxPersons: Short? = null,
    val recommendedPersons: Short? = null,
    val crewNumber: Short? = null,
    val cabinsNote: String? = null,
    val berthsNote: String? = null,
    val wcNote: String? = null,
    val mainsailLabel: String? = null,
    val genoaLabel: String? = null,
    val engineLabel: String? = null,
    val engineCount: Short? = null,
    val enginePowerEach: BigDecimal? = null,
    val draught: BigDecimal? = null,
    /** ADMIN ONLY - read by [YachtCapacityMapper.brokerNotes] alone, never by the public blocks. */
    val internalRemark: String? = null,
) {
    companion object {
        fun of(yacht: Yacht): CapacityColumns =
            CapacityColumns(
                cabins = yacht.cabins,
                crewCabins = yacht.crewCabins,
                wc = yacht.wc,
                crewWc = yacht.crewWc,
                berths = yacht.berths,
                crewBerths = yacht.crewBerths,
                cabinBerths = yacht.cabinBerths,
                salonBerths = yacht.salonBerths,
                showers = yacht.showers,
                crewShowers = yacht.crewShowers,
                maxPersons = yacht.maxPersons,
                recommendedPersons = yacht.recommendedPersons,
                crewNumber = yacht.crewNumber,
                cabinsNote = yacht.cabinsNote,
                berthsNote = yacht.berthsNote,
                wcNote = yacht.wcNote,
                mainsailLabel = yacht.mainsailLabel,
                genoaLabel = yacht.genoaLabel,
                engineLabel = yacht.engineLabel,
                engineCount = yacht.engineCount,
                enginePowerEach = yacht.enginePowerEach,
                draught = yacht.draught,
                internalRemark = yacht.internalRemark,
            )
    }
}

/**
 * Yacht columns -> the public `capacity` / `rig` blocks (capacity contract v1, sections 2 and 5). Works from the columns
 * alone and never needs the source system: notes exist only for MMK, the berths breakdown and the crew figures only for
 * NauSys. Partner text passes [PartnerTextSanitizer.capacityNote] here, once, for every surface.
 */
@Component
class YachtCapacityMapper(
    private val sanitizer: PartnerTextSanitizer,
) {
    enum class Mode {
        /** Detail, reservation, trip: the sanitized note. */
        FULL,

        /** Search rows: the note only when language-neutral and at most 12 characters ("4 +2", "(8+2)"). */
        BRIEF,
    }

    fun capacity(
        c: CapacityColumns,
        mode: Mode,
    ): CapacityDto =
        CapacityDto(
            cabins = dim(c.cabins, c.cabinsNote, CapacityDim.CABINS, mode),
            berths =
                dim(
                    c.berths,
                    c.berthsNote,
                    CapacityDim.BERTHS,
                    mode,
                    CapacityNoteParser.nausysBerthsSplit(
                        c.berths?.toInt(),
                        c.cabinBerths?.toInt(),
                        c.salonBerths?.toInt(),
                        c.crewBerths?.toInt(),
                    ),
                ),
            heads = dim(c.wc, c.wcNote, CapacityDim.HEADS, mode),
            crewCabins = positive(c.crewCabins),
            crewHeads = positive(c.crewWc),
            showers = positive(c.showers),
            crewShowers = positive(c.crewShowers),
            maxPersons = positive(c.maxPersons),
            recommendedPersons = positive(c.recommendedPersons),
            crewNumber = positive(c.crewNumber),
        )

    fun rig(c: CapacityColumns): RigDto {
        val powerEach = c.enginePowerEach?.takeIf { it.signum() > 0 }
        val label = c.engineLabel?.takeIf { EnginePowerParser.hasUnit(it) }?.let { sanitizer.capacityNote(it) }
        val engine =
            when {
                powerEach != null -> EngineDto(label = null, count = positive(c.engineCount) ?: ONE_ENGINE, powerEach = plain(powerEach))
                label != null -> EngineDto(label = label, count = null, powerEach = null)
                else -> null
            }
        return RigDto(
            mainsail = sail(c.mainsailLabel),
            headsail = sail(c.genoaLabel),
            engine = engine,
            draught = c.draught?.takeIf { it.signum() > 0 }?.let { plain(it) },
        )
    }

    /** ADMIN ONLY: the raw stored notes and the internal remark. */
    fun brokerNotes(c: CapacityColumns): BrokerNotesDto {
        return BrokerNotesDto(cabinsNote = c.cabinsNote, berthsNote = c.berthsNote, headsNote = c.wcNote, remark = c.internalRemark)
    }

    private fun dim(
        value: Short?,
        rawNote: String?,
        dim: CapacityDim,
        mode: Mode,
        nausysSplit: CapacitySplit? = null,
    ): CapacityDimDto? {
        val v = positive(value) ?: return null
        val safe = sanitizer.capacityNote(rawNote)
        val split = nausysSplit ?: CapacityNoteParser.parse(v.toInt(), safe, dim)
        val note = safe?.takeIf { mode == Mode.FULL || CapacityText.isShortNote(it) }
        return CapacityDimDto(value = v, note = note, split = split?.toDto())
    }

    private fun sail(label: String?): SailDto? {
        if (SailLabels.isNone(label)) return null
        SailLabels.kind(label)?.let { return SailDto(kind = it, label = null) }
        return sanitizer.capacityNote(label)?.let { SailDto(kind = null, label = it) }
    }

    private fun CapacitySplit.toDto(): CapacitySplitDto =
        CapacitySplitDto(
            guests = guests?.toShort(),
            inCabins = inCabins?.toShort(),
            saloon = saloon?.toShort(),
            crew = crew?.toShort(),
            skipper = skipper?.toShort(),
        )

    private fun positive(v: Short?): Short? = v?.takeIf { it > 0 }

    /** 115.00 -> 115, 1.30 -> 1.3, never 1.15E+2. */
    private fun plain(v: BigDecimal): BigDecimal = v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private companion object {
        /** NauSys power without an engine count: one engine. */
        const val ONE_ENGINE: Short = 1
    }
}
