package hr.workspace.boat4you.domains.contactcenter.service

import hr.workspace.boat4you.domains.contactcenter.jpa.CcSearch
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSearchRepository
import hr.workspace.boat4you.domains.contactcenter.jpa.SearchSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Stores the `search_yachts` calls of one IRIS reply as `cc_search` rows. It joins the transaction that stores the
 * reply (tx2 of the chat flow, BACKEND-17/-18) and never opens one of its own, so a search is stored if and only if
 * its reply (or the lock that replaces it) is.
 */
@Service
class ChatSearchRecorder(
    private val searches: CcSearchRepository,
) {
    /** One row per entry of [criteria], in order; [flagged] is true when the reply locked the session. An empty list stores no row. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        siteId: String,
        chatSessionId: Long,
        visitId: Long?,
        criteria: List<SearchCriteria>,
        flagged: Boolean,
    ): List<CcSearch> {
        val rows =
            criteria.map { c ->
                CcSearch().apply {
                    source = SearchSource.AI_CHAT
                    this.siteId = siteId
                    this.chatSessionId = chatSessionId
                    this.visitId = visitId
                    countryCode = c.countryCode
                    location = c.location
                    did = c.did
                    startDate = c.startDate
                    endDate = c.endDate
                    minPersons = c.minPersons
                    minCabins = c.minCabins
                    minPriceEur = c.minPriceEur
                    maxPriceEur = c.maxPriceEur
                    vesselType = c.vesselType
                    equipment = c.equipment.joinToString(",").ifEmpty { null }
                    resultCount = c.resultCount
                    totalAvailable = c.totalAvailable
                    searchedArea = c.searchedArea
                    this.flagged = flagged
                    estimated = false
                }
            }
        return searches.saveAll(rows)
    }
}
