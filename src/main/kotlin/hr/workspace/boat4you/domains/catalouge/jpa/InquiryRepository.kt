package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InquiryRepository : JpaRepository<Inquiry, Long> {
    @Query(
        """
        SELECT i FROM Inquiry i
        WHERE 1 = 1
        AND (:search IS NULL OR LOWER(i.email) LIKE LOWER(CONCAT('%', STR(:search), '%')) 
            OR LOWER(i.name) LIKE LOWER(CONCAT('%', STR(:search), '%')) 
            OR LOWER(i.surname) LIKE LOWER(CONCAT('%', STR(:search), '%')))
        AND (:statuses IS NULL OR i.status IN :statuses)
        """,
    )
    fun findAllByParamsForAdmin(
        @Param("search") search: String?,
        @Param("statuses") statuses: List<InquiryStatus>?,
        pageable: Pageable,
    ): Page<Inquiry>
}
