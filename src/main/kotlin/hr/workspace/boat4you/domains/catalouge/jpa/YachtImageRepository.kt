package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface YachtImageRepository : JpaRepository<YachtImage, Long> {
    fun countYachtImageBySyncedFalse(): Long

    fun findBySyncedFalseOrderByIdDesc(pageable: Pageable): List<YachtImage>

    fun findBySyncedFalseOrderByIdAsc(pageable: Pageable): List<YachtImage>

    // Partner-sourced images of taken-back yachts, PLUS yachts of departed
    // EXTERNAL agencies (agency.active = false). Those yachts keep
    // sysActive = true — an empty partner company list aborts the yacht
    // take-back — so their images were never purged and pile up on /mnt/data
    // (Kiriacoulis alone: 171 yachts). Safe: the catalogue sync re-creates
    // images if the agency ever returns. The main image is kept in both
    // branches: already-sent reservation emails hotlink /public/image/{mainImageId}.
    @Query(
        "select yi from YachtImage yi where yi.externalUrl is not null " +
            "and (yi.yacht.sysActive = false " +
            "or (yi.yacht.entryType = :externalType and yi.yacht.agency.active = false)) " +
            "and (yi.yacht.mainImageId is null or yi.id <> yi.yacht.mainImageId)",
    )
    fun findPurgeableImagesOfInactiveYachts(
        externalType: EntryType,
        pageable: Pageable,
    ): List<YachtImage>
}
