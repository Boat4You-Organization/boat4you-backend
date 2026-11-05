package hr.workspace.boat4you.domains.pr.service

import hr.workspace.boat4you.domains.pr.dto.PrPlacementDto
import hr.workspace.boat4you.domains.pr.dto.PrPlacementRequest
import hr.workspace.boat4you.domains.pr.jpa.PrLinkState
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPlacement
import hr.workspace.boat4you.domains.pr.jpa.PrPlacementRepository
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.util.Locale

/**
 * Placements: articles that used one of our studies (admin and PR API). The article URL is trimmed, its scheme and
 * host lowercased and its #fragment dropped, and it is unique (409 PLACEMENT_EXISTS; a race the check loses hits the
 * unique index, 409 CONFLICT). Creating one only asks Open PageRank for the domain rank (null when it cannot); the page
 * itself is fetched by the daily job or "Provjeri sad" (PrLinkCheckService), never here.
 */
@Service
class PrPlacementService(
    private val placements: PrPlacementRepository,
    private val studies: PrStudyRepository,
    private val outlets: PrOutletRepository,
    private val pitches: PrPitchRepository,
    private val opr: OpenPageRankClient,
) {
    @Transactional(readOnly = true)
    fun list(studyId: Long?): List<PrPlacementDto> {
        val rows = if (studyId == null) placements.findAll() else placements.findAllByStudyId(studyId)
        return toDtos(rows.sortedWith(compareByDescending<PrPlacement> { it.createdAt }.thenByDescending { it.id }))
    }

    @Transactional(readOnly = true)
    fun get(id: Long): PrPlacementDto = toDtos(listOf(placement(id))).single()

    /** Not one transaction: the Open PageRank call (up to 8 s) runs before the insert, outside any transaction. */
    fun create(req: PrPlacementRequest): PrPlacementDto {
        val url = articleUrl(req.articleUrl)
        if (placements.findFirstByArticleUrl(url) != null) throw exists(url)
        checkLinks(req)
        val rank = opr.rank(URI(url).host.removePrefix("www."))
        val placement =
            PrPlacement().apply {
                articleUrl = url
                studyId = req.studyId
                outletId = req.outletId
                pitchId = req.pitchId
                publishedOn = req.publishedOn
                targetUrl = req.targetUrl.clean(URL)
                anchorText = req.anchorText.clean(ANCHOR)
                domainRank = rank
            }
        return toDtos(listOf(placements.save(placement))).single()
    }

    /** Only the fields sent change. A new article URL is another page: its check result is reset (the alert stays). */
    @Transactional
    fun update(
        id: Long,
        req: PrPlacementRequest,
    ): PrPlacementDto {
        val placement = placement(id)
        val url = req.articleUrl?.let { articleUrl(it) }
        // Checked before any field changes: a query would flush a clashing change into a unique-index error.
        if (url != null && url != placement.articleUrl) {
            val clash = placements.findFirstByArticleUrl(url)
            if (clash != null && clash.id != placement.id) throw exists(url)
        }
        checkLinks(req)
        if (url != null && url != placement.articleUrl) {
            placement.articleUrl = url
            placement.linkState = PrLinkState.NOT_CHECKED
            placement.lastHttpStatus = null
            placement.lastCheckedAt = null
        }
        req.studyId?.let { placement.studyId = it }
        req.outletId?.let { placement.outletId = it }
        req.pitchId?.let { placement.pitchId = it }
        req.publishedOn?.let { placement.publishedOn = it }
        req.targetUrl?.let { placement.targetUrl = it.clean(URL) }
        req.anchorText?.let { placement.anchorText = it.clean(ANCHOR) }
        return toDtos(listOf(placements.save(placement))).single()
    }

    /** The only way an alert goes away: the link checker never clears one. */
    fun clearAlert(id: Long): PrPlacementDto {
        if (placements.clearAlert(id) == 0) throw notFound(id)
        return get(id)
    }

    internal fun placement(id: Long): PrPlacement = placements.findById(id).orElseThrow { notFound(id) }

    private fun checkLinks(req: PrPlacementRequest) {
        req.studyId?.let { if (!studies.existsById(it)) throw PrException.notFound("STUDY_NOT_FOUND", "Study $it not found") }
        req.outletId?.let { if (!outlets.existsById(it)) throw PrException.notFound("OUTLET_NOT_FOUND", "Outlet $it not found") }
        req.pitchId?.let { if (!pitches.existsById(it)) throw PrException.notFound("PITCH_NOT_FOUND", "Pitch $it not found") }
    }

    private fun toDtos(rows: List<PrPlacement>): List<PrPlacementDto> {
        val outletNames = outlets.findAllById(rows.mapNotNull { it.outletId }.toSet()).associate { it.id to it.name }
        return rows.map {
            PrPlacementDto(
                id = it.id!!,
                studyId = it.studyId,
                outletId = it.outletId,
                outletName = it.outletId?.let { o -> outletNames[o] },
                pitchId = it.pitchId,
                articleUrl = it.articleUrl,
                publishedOn = it.publishedOn,
                targetUrl = it.targetUrl,
                anchorText = it.anchorText,
                linkState = it.linkState,
                lastHttpStatus = it.lastHttpStatus,
                lastCheckedAt = it.lastCheckedAt,
                alert = it.alert,
                alertReason = it.alertReason,
                domainRank = it.domainRank,
                createdAt = it.createdAt,
            )
        }
    }

    private fun exists(url: String) = PrException.conflict("PLACEMENT_EXISTS", "A placement for $url already exists")

    private fun notFound(id: Long) = PrException.notFound("PLACEMENT_NOT_FOUND", "Placement $id not found")

    private fun String?.clean(max: Int): String? = this?.trim()?.take(max)?.ifEmpty { null }

    companion object {
        private const val URL = 1000
        private const val ANCHOR = 500

        /** Trimmed http(s) URL with a host and no credentials; scheme and host lowercased, #fragment dropped. 400 ARTICLE_URL_INVALID otherwise. */
        fun articleUrl(raw: String?): String {
            val invalid = PrException.badRequest("ARTICLE_URL_INVALID", "Article URL must be a full http(s) address of at most $URL characters")
            val uri = runCatching { URI(raw?.trim().orEmpty()) }.getOrElse { throw invalid }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) throw invalid
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val url = "$scheme://${uri.host.lowercase(Locale.ROOT)}$port${uri.rawPath.orEmpty()}$query"
            if (url.length > URL) throw invalid
            return url
        }
    }
}
