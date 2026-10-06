package hr.workspace.boat4you.domains.catalouge.capacity

import hr.workspace.boat4you.domains.catalouge.enums.SailTypeEnum
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import org.openapitools.client.nausys.model.RestYacht
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Partner payload -> the yacht's capacity and rig columns (capacity contract v1, section 3), for the MMK and NauSys
 * yacht syncs. Stored AS SENT: notes and labels only normalized (blanks), numbers untouched; the public blocks are
 * derived at read time (YachtCapacityMapper). Every assignment is unconditional, null included, so a value the partner
 * clears is cleared here too (both syncs re-save every yacht on every run).
 */
object PartnerCapacitySync {
    private val log = LoggerFactory.getLogger(javaClass)
    private val loggedUnknownSailIds = ConcurrentHashMap.newKeySet<Int>()

    /** numeric(7,2) of yacht.engine_power_each. */
    private val MAX_POWER_EACH = BigDecimal(100000)

    /**
     * MMK, from the fleet call WITHOUT ?language= only (MmkYachtSyncService.updateFromMmkModel): MMK localizes the sail
     * labels ("Lattengroß") but not the notes, and the translation sync never comes here (critique C12).
     */
    fun applyMmk(
        yacht: Yacht,
        mmk: org.openapitools.client.mmk.model.Yacht,
    ) {
        yacht.cabins = mmk.cabins?.toShort()
        yacht.crewCabins = null
        yacht.wc = mmk.wc?.toShort()
        yacht.crewWc = null
        yacht.berths = mmk.berths?.toShort()
        yacht.crewBerths = null
        yacht.maxPersons = mmk.maxPeopleOnBoard?.toShort()
        // crew list size; null again when the partner empties the list (it used to keep the last non-empty count)
        yacht.crewNumber = mmk.crew?.size?.takeIf { it > 0 }?.toShort()
        yacht.draught = mmk.draught?.toBigDecimal()
        yacht.mainsailType = SailTypeEnum.fromMmkValue(mmk.mainsailType)
        yacht.genoaType = SailTypeEnum.fromMmkValue(mmk.genoaType)
        yacht.enginePower = EnginePowerParser.parseHp(mmk.engine)?.toShort()

        yacht.cabinsNote = CapacityText.normalizeNote(mmk.cabinsNote)
        yacht.berthsNote = CapacityText.normalizeNote(mmk.berthsNote)
        yacht.wcNote = CapacityText.normalizeNote(mmk.wcNote)
        yacht.cabinBerths = null
        yacht.salonBerths = null
        yacht.showers = null
        yacht.crewShowers = null
        yacht.recommendedPersons = null
        yacht.mainsailLabel = CapacityText.normalizeNote(mmk.mainsailType)
        yacht.genoaLabel = CapacityText.normalizeNote(mmk.genoaType)
        yacht.engineLabel = CapacityText.normalizeNote(mmk.engine)
        yacht.engineCount = null
        yacht.enginePowerEach = null
        // ADMIN ONLY (undocumented MMK field, added to mmk_api_2_1_5.yaml)
        yacht.internalRemark = CapacityText.normalizeNote(mmk.comment)
    }

    /** NauSys (NauSysYachtSyncService.updateFromNausysModel). */
    fun applyNausys(
        yacht: Yacht,
        ns: RestYacht,
    ) {
        yacht.cabins = ns.cabins?.toShort()
        yacht.crewCabins = ns.cabinsCrew?.toShort()
        yacht.wc = ns.wc?.toShort()
        // was ns.wc (crew_wc = wc on every NauSys boat, e.g. Corali "crew WC 4" for 2)
        yacht.crewWc = ns.wcCrew?.toShort()
        yacht.berths = ns.berthsTotal?.toShort()
        yacht.crewBerths = ns.berthsCrew?.toShort()
        yacht.maxPersons = ns.maxPersons?.toShort()
        yacht.crewNumber = ns.crewCount?.toShort()
        yacht.draught = ns.draft
        yacht.mainsailType = SailTypeEnum.fromNausysValue(ns.sailTypeId)
        yacht.genoaType = SailTypeEnum.fromNausysValue(ns.genoaTypeId)
        // engines x power per engine, rounded half up (was truncated to a whole number per engine before multiplying)
        yacht.enginePower = EnginePowerParser.nausysTotalHp(ns.engines, ns.enginePower)?.toShort()

        yacht.cabinsNote = null
        yacht.berthsNote = null
        yacht.wcNote = null
        yacht.cabinBerths = shortOrNull(ns.berthsCabin)
        yacht.salonBerths = shortOrNull(ns.berthsSalon)
        yacht.showers = shortOrNull(ns.showers)
        yacht.crewShowers = shortOrNull(ns.showersCrew)
        yacht.recommendedPersons = shortOrNull(ns.recommendedPersons)
        yacht.mainsailLabel = nausysSailLabel(ns.sailTypeId)
        yacht.genoaLabel = nausysSailLabel(ns.genoaTypeId)
        yacht.engineLabel = null
        yacht.engineCount = shortOrNull(ns.engines)
        yacht.enginePowerEach = ns.enginePower?.takeIf { it.abs() < MAX_POWER_EACH }
        // ADMIN ONLY; into the column, not yacht_translations (a NOTE type there would hit the match-by-language-only
        // bug of both syncs' createTranslations and overwrite the description)
        yacht.internalRemark = CapacityText.normalizeNote(ns.noteIntText?.textEN) ?: CapacityText.normalizeNote(ns.note)
    }

    private fun nausysSailLabel(id: Int?): String? {
        if (id == null) return null
        val label = SailLabels.NAUSYS_SAIL_LABELS[id]
        if (label == null && loggedUnknownSailIds.add(id)) {
            log.warn("NauSys sail type id $id is not in SailLabels.NAUSYS_SAIL_LABELS - stored as null; add it (sailTypes catalogue)")
        }
        return label
    }

    private fun shortOrNull(v: Int?): Short? = v?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.toShort()
}
