package hr.workspace.boat4you.domains.external.job

import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkAuditedClient
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtSyncService
import hr.workspace.boat4you.domains.external.nausys.client.NauSysAuditedClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.openapitools.client.nausys.model.AllYachtsRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Weekly consistency "inventory" (Mario 12.7.2026): READ-ONLY comparison of our
 * DB against the partners as source of truth, mailed to the admins. It never
 * deletes or fixes anything — it surfaces drift the sync/mirror layers missed
 * (the silent-rollback incident proved cleanup can break without anyone
 * noticing; this is the independent control that catches such rot early).
 *
 * Checks:
 *  A. Per active agency: OUR active-yacht external ids vs the partner's CURRENT
 *     yacht list. "Partner has, we don't" counts ONLY sync-eligible vessels
 *     (Mario 11.7.2026): houseboats/river boats, rubber boats, trimarans,
 *     "other" and MMK day-charter-only yachts are deliberately never imported,
 *     so they must not show up as drift. The eligibility rules are the SAME
 *     ones the sync applies (MmkYachtSyncService.shouldSkip / VesselType).
 *     "We have, partner doesn't" still uses the partner's FULL list — that is
 *     the take-back baseline.
 *  B. DB invariants (pure SQL, no partner calls): orphaned yacht mappings,
 *     active yachts under an inactive agency, future offers on inactive yachts,
 *     active yachts without a single image.
 *
 * The report is ALWAYS mailed (weekly heartbeat) — "sve OK" is a result too.
 * Per-agency partner failures are tolerated and counted, never fatal.
 */
@Profile("data-sync")
@Component
class ConsistencyVerifierJob(
    private val agencyRepository: AgencyRepository,
    private val mmkAuditedClient: MmkAuditedClient,
    private val mmkYachtSyncService: MmkYachtSyncService,
    private val nauSysAuditedClient: NauSysAuditedClient,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val emailService: EmailService,
    private val userRepository: UserRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    private data class AgencyDrift(
        val agencyId: Long,
        val agencyName: String,
        val system: String,
        val oursActive: Int,
        val atPartner: Int,
        val weHavePartnerDoesnt: Int,
        val partnerHasWeDont: Int,
        val partnerFleetEmpty: Boolean,
    )

    /** Partner fleet: every reported id + the subset our sync would actually import. */
    private data class PartnerFleet(
        val allIds: Set<Long>,
        val eligibleIds: Set<Long>,
    )

    /** Sunday 09:30 — after the whole morning sync chain (06:00–08:30) settles. */
    @Scheduled(cron = "0 30 9 * * SUN")
    @SchedulerLock(name = "consistencyVerifier", lockAtMostFor = "PT4H")
    fun runWeekly() {
        runCatching { verifyAndMail() }
            .onFailure { e -> log.error("Consistency verifier failed", e) }
    }

    fun verifyAndMail() {
        val report = buildReport()
        val recipients = userRepository.findAllAdminEmailAddresses()
        if (recipients.isEmpty()) {
            log.warn("Consistency verifier: no admin email addresses — report only in log:\n{}", report)
            return
        }
        emailService.sendEmail(
            recipients = recipients,
            subject = "Tjedna inventura sinkronizacije ✅ Boat4You",
            templateName = "email/adminConsistencyReport",
            variables = mapOf("reportBody" to report),
        )
        log.info("Consistency verifier report mailed to {} recipient(s)", recipients.size)
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.appendLine("TJEDNA INVENTURA SINKRONIZACIJE (naša baza vs partneri)")
        sb.appendLine("Ništa se ne briše automatski — ovo je izvještaj o odstupanjima.")
        sb.appendLine()

        // ---- A: fleet drift per agency, both systems -------------------------
        val drifts = mutableListOf<AgencyDrift>()
        var checked = 0
        var failures = 0
        val nausysModelCategories = loadNausysModelCategories()

        for (system in listOf(ExternalSystemEnum.MMK, ExternalSystemEnum.NAUSYS)) {
            val agencies = agencyRepository.findAllActiveByPrimarySyncProvider(system.value.toLong())
            for (agency in agencies) {
                val extId = agency.getExternalId() ?: continue
                val partner = runCatching { partnerFleet(system, extId, nausysModelCategories) }
                    .getOrElse {
                        failures++
                        continue
                    }
                checked++
                val ourIds = jdbcTemplate.queryForList(
                    """
                    SELECT em.external_id FROM external_mapping em
                    JOIN yacht y ON y.id = em.system_id
                    WHERE em.type = 'Yacht' AND em.external_system_id = ?
                      AND y.agency_id = ? AND y.sys_active = true
                    """.trimIndent(),
                    Long::class.java,
                    system.value,
                    agency.id,
                ).toSet()
                val weExtra = (ourIds - partner.allIds).size
                val theyExtra = (partner.eligibleIds - ourIds).size
                if (weExtra > 0 || theyExtra > 0) {
                    drifts += AgencyDrift(
                        agencyId = agency.id!!,
                        agencyName = agency.name ?: "?",
                        system = system.name,
                        oursActive = ourIds.size,
                        atPartner = partner.eligibleIds.size,
                        weHavePartnerDoesnt = weExtra,
                        partnerHasWeDont = theyExtra,
                        partnerFleetEmpty = partner.allIds.isEmpty(),
                    )
                }
            }
        }

        sb.appendLine("A) FLOTA PO AGENCIJI (aktivne agencije, provjereno: $checked, partner-greške: $failures)")
        if (drifts.isEmpty()) {
            sb.appendLine("   ✅ Sve flote se poklapaju s partnerima.")
        } else {
            sb.appendLine("   ⚠️ ${drifts.size} agencija s odstupanjem (naše aktivne vs partner):")
            drifts
                .sortedByDescending { it.weHavePartnerDoesnt + it.partnerHasWeDont }
                .take(30)
                .forEach { d ->
                    val emptyNote = if (d.partnerFleetEmpty) " ⚠️ partner vraća PRAZNU flotu (guard ne deaktivira automatski)" else ""
                    sb.appendLine(
                        "   - [${d.system}] ${d.agencyName} (id ${d.agencyId}): " +
                            "mi ${d.oursActive} / partner ${d.atPartner} — " +
                            "viška kod nas ${d.weHavePartnerDoesnt}, fali kod nas ${d.partnerHasWeDont}$emptyNote",
                    )
                }
            if (drifts.size > 30) sb.appendLine("   … i još ${drifts.size - 30} agencija (top 30 prikazano).")
        }
        sb.appendLine()

        // ---- B: DB invariants -------------------------------------------------
        fun countSql(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0

        val orphanYachtMappings = countSql(
            "SELECT count(*) FROM external_mapping em WHERE em.type='Yacht' " +
                "AND NOT EXISTS (SELECT 1 FROM yacht y WHERE y.id = em.system_id)",
        )
        val activeYachtsInactiveAgency = countSql(
            "SELECT count(*) FROM yacht y JOIN agency a ON a.id=y.agency_id " +
                "WHERE y.sys_active=true AND a.active=false",
        )
        val futureOffersInactiveYachts = countSql(
            "SELECT count(*) FROM offer o JOIN yacht y ON y.id=o.yacht_id " +
                "WHERE o.date_to >= CURRENT_DATE AND y.sys_active=false",
        )
        val activeYachtsNoImages = countSql(
            "SELECT count(*) FROM yacht y WHERE y.sys_active=true " +
                "AND NOT EXISTS (SELECT 1 FROM yacht_image yi WHERE yi.yacht_id=y.id)",
        )

        sb.appendLine("B) INTERNE PROVJERE BAZE")
        sb.appendLine("   - Yacht mappingi bez jahte (orphan): $orphanYachtMappings")
        sb.appendLine("   - Aktivne jahte pod NEaktivnom agencijom: $activeYachtsInactiveAgency")
        sb.appendLine("   - Budući offeri na NEaktivnim jahtama: $futureOffersInactiveYachts")
        sb.appendLine("   - Aktivne jahte bez ijedne slike: $activeYachtsNoImages")
        sb.appendLine()
        sb.appendLine("Napomena: 'partner' brojka = samo plovila koja naš sync UVOZI (houseboat/riječni, gumenjaci,")
        sb.appendLine("trimarani, 'other' i MMK plovila bez charter produkta — dnevni najam — se NE broje).")
        sb.appendLine("'viška kod nas' = partner tu jahtu više ne lista (kandidat za deaktivaciju);")
        sb.appendLine("'fali kod nas' = plovilo koje bismo trebali imati, a nemamo.")
        return sb.toString()
    }

    private fun partnerFleet(
        system: ExternalSystemEnum,
        agencyExternalId: Long,
        nausysModelCategories: Map<Long, Long?>,
    ): PartnerFleet = when (system) {
        ExternalSystemEnum.MMK -> {
            val yachts = mmkAuditedClient.getYachts(companyId = agencyExternalId)
            PartnerFleet(
                allIds = yachts.map { it.id }.toSet(),
                eligibleIds = yachts.filterNot { mmkYachtSyncService.shouldSkip(it) }.map { it.id }.toSet(),
            )
        }
        ExternalSystemEnum.NAUSYS -> {
            val yachts = nauSysAuditedClient
                .allYachts(
                    agencyExternalId,
                    AllYachtsRequest(
                        username = nauSysAuthProvider.nauSysUsername!!,
                        password = nauSysAuthProvider.nauSysPassword!!,
                        yachtIDs = null,
                        onlyIDs = false,
                    ),
                ).yachts ?: emptyList()
            PartnerFleet(
                allIds = yachts.mapNotNull { it.id }.toSet(),
                eligibleIds = yachts
                    .filter { y ->
                        // Mirror of NauSysYachtSyncService: unresolvable model → the
                        // sync never imports it; otherwise skip by vessel category.
                        val modelId = y.yachtModelId
                        modelId != null &&
                            nausysModelCategories.containsKey(modelId) &&
                            !VesselType.shouldSkipVesselType(
                                VesselType.fromNauSysCategoryId(nausysModelCategories[modelId]),
                            )
                    }.mapNotNull { it.id }
                    .toSet(),
            )
        }
        else -> PartnerFleet(emptySet(), emptySet())
    }

    /** NauSys model external id → external_category_id (one query, reused for every agency). */
    private fun loadNausysModelCategories(): Map<Long, Long?> =
        jdbcTemplate.query(
            """
            SELECT em.external_id, m.external_category_id FROM external_mapping em
            JOIN model m ON m.id = em.system_id
            WHERE em.type = 'Model' AND em.external_system_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getLong(1) to rs.getObject(2)?.let { (it as Number).toLong() } },
            ExternalSystemEnum.NAUSYS.value,
        ).toMap()
}
