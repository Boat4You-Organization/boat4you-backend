package hr.workspace.boat4you.domains.pr.service

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.pr.dto.PrStudyDto
import hr.workspace.boat4you.domains.pr.dto.PrStudyRequest
import hr.workspace.boat4you.domains.pr.jpa.PrStudy
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.rules.PrComplianceRules
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

/**
 * Data studies (one per number) that pitches are written about. Each belongs to one of the 7 sites in BrandRegistry
 * (Brand.id, equal to its AI site id) and its title never names TripAdvisor. A number taken by another study is 409
 * STUDY_NUMBER_EXISTS; a race the check loses hits the unique index and PrErrorAdvice answers 409 CONFLICT.
 */
@Service
class PrStudyService(
    private val studies: PrStudyRepository,
    private val brands: BrandRegistry,
) {
    @Transactional(readOnly = true)
    fun list(): List<PrStudyDto> = studies.findAll(Sort.by("number")).map { it.toDto() }

    @Transactional
    fun create(req: PrStudyRequest): PrStudyDto {
        val number = number(req.number)
        val siteId = siteId(req.siteId)
        val title = title(req.title)
        if (studies.findFirstByNumber(number) != null) throw numberExists(number)
        val study =
            PrStudy().apply {
                this.number = number
                this.siteId = siteId
                this.title = title
                pageUrl = req.pageUrl.clean(URL)
                req.status?.let { status = it }
                publishedOn = req.publishedOn
                notes = req.notes.clean()
            }
        return studies.save(study).toDto()
    }

    @Transactional
    fun update(
        id: Long,
        req: PrStudyRequest,
    ): PrStudyDto {
        val study = studies.findById(id).orElseThrow { PrException.notFound("STUDY_NOT_FOUND", "Study $id not found") }
        val number = req.number?.let { number(it) } ?: study.number!!
        val siteId = req.siteId?.let { siteId(it) } ?: study.siteId
        val title = req.title?.let { title(it) } ?: study.title
        // Checked before any field changes: a query would flush a clashing change into a unique-index error.
        val clash = studies.findFirstByNumber(number)
        if (clash != null && clash.id != study.id) throw numberExists(number)
        study.number = number
        study.siteId = siteId
        study.title = title
        req.pageUrl?.let { study.pageUrl = it.clean(URL) }
        req.status?.let { study.status = it }
        req.publishedOn?.let { study.publishedOn = it }
        req.notes?.let { study.notes = it.clean() }
        return studies.save(study).toDto()
    }

    private fun number(value: Int?): Int = value?.takeIf { it >= 1 } ?: throw PrException.badRequest("STUDY_NUMBER_INVALID", "Study number is required (1 or more)")

    private fun siteId(raw: String?): String {
        val id = raw?.trim()?.lowercase(Locale.ROOT)
        if (id == null || id !in brands.byId) {
            throw PrException.badRequest("SITE_UNKNOWN", "Site must be one of: ${brands.byId.keys.sorted().joinToString()}")
        }
        return id
    }

    private fun title(raw: String?): String {
        val title = raw?.trim()?.take(TITLE)?.ifEmpty { null } ?: throw PrException.badRequest("TITLE_REQUIRED", "Study title is required")
        if (PrComplianceRules.mentionsTripAdvisor(title)) throw PrException.badRequest("TRIPADVISOR_FORBIDDEN", "The title must not mention TripAdvisor")
        return title
    }

    private fun numberExists(number: Int) = PrException.conflict("STUDY_NUMBER_EXISTS", "Study number $number already exists")

    private fun String?.clean(max: Int = Int.MAX_VALUE): String? = this?.trim()?.take(max)?.ifEmpty { null }

    private fun PrStudy.toDto() = PrStudyDto(id!!, number!!, siteId, title, pageUrl, status, publishedOn, notes, createdAt)

    companion object {
        private const val TITLE = 300
        private const val URL = 1000
    }
}
