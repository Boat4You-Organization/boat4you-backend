package hr.workspace.boat4you.domains.contactcenter.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface CcContactRepository : JpaRepository<CcContact, Long>

interface CcVisitorRepository : JpaRepository<CcVisitor, Long> {
    fun findByVisitorKey(visitorKey: String): CcVisitor?
}

interface CcVisitRepository : JpaRepository<CcVisit, Long>

interface CcPageViewRepository : JpaRepository<CcPageView, Long>

interface CcEventRepository : JpaRepository<CcEvent, Long>

interface CcSearchRepository : JpaRepository<CcSearch, Long> {
    fun findAllByChatSessionIdOrderByIdAsc(chatSessionId: Long): List<CcSearch>
}
