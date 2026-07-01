package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface YachtImageRepository : JpaRepository<YachtImage, Long> {
    fun countYachtImageBySyncedFalse(): Long

    fun findBySyncedFalseOrderByIdDesc(pageable: Pageable): List<YachtImage>

    // Partner-sourced images of taken-back yachts. The main image is kept:
    // already-sent reservation emails hotlink /public/image/{mainImageId}.
    @Query(
        "select yi from YachtImage yi where yi.yacht.sysActive = false and yi.externalUrl is not null " +
            "and (yi.yacht.mainImageId is null or yi.id <> yi.yacht.mainImageId)",
    )
    fun findPurgeableImagesOfInactiveYachts(pageable: Pageable): List<YachtImage>
}
