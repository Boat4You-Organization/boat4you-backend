package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface InquiryRepository : JpaRepository<Inquiry, Long> {
    // `CAST(:search AS string)` is REQUIRED, not cosmetic. When :search is
    // null the `:search IS NULL OR ...` branch short-circuits logically, but
    // PostgreSQL still TYPES the `'%'||?||'%'` expression at plan time; an
    // untyped null param is inferred as `bytea`, so `lower(bytea)` throws
    // "function lower(bytea) does not exist" (seen on PG18) and the whole
    // admin list query 500s. The cast pins the param to varchar. (This is the
    // bug a previous "F2-029 drop redundant STR()" change introduced.)
    @Query(
        """
        SELECT i FROM Inquiry i
        WHERE 1 = 1
        AND (:search IS NULL OR LOWER(i.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
            OR LOWER(i.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
            OR LOWER(i.surname) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
        AND (:statuses IS NULL OR i.status IN :statuses)
        """,
    )
    fun findAllByParamsForAdmin(
        @Param("search") search: String?,
        @Param("statuses") statuses: List<InquiryStatus>?,
        pageable: Pageable,
    ): Page<Inquiry>

    /** Used by the new-inquiry email to flip the "NEW CLIENT" pill — true
     *  when this is the first inquiry under a given email. */
    @Query(
        """
        SELECT COUNT(i) FROM Inquiry i
        WHERE LOWER(i.email) = LOWER(:email)
          AND i.id <> :idNot
        """,
    )
    fun countByEmailIgnoreCaseAndIdNot(
        @Param("email") email: String,
        @Param("idNot") idNot: Long,
    ): Long

    /** Returns this inquiry's per-year sequence number — i.e. how many
     *  inquiries (including this one) were received in the same calendar
     *  year up to this row's id. Powers the "INQ-{yy}-{seq}" display
     *  format which resets each new year. */
    @Query(
        """
        SELECT COUNT(i) FROM Inquiry i
        WHERE i.createdAt >= :startOfYear
          AND i.createdAt < :startOfNextYear
          AND i.id <= :id
        """,
    )
    fun countInYearUpToId(
        @Param("startOfYear") startOfYear: java.time.LocalDateTime,
        @Param("startOfNextYear") startOfNextYear: java.time.LocalDateTime,
        @Param("id") id: Long,
    ): Long
}
