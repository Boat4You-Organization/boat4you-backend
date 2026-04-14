package hr.workspace.boat4you.domains.catalouge.jpa

import hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface YachtRepository : JpaRepository<Yacht, Long> {
    fun findAllByAgencyAndIdNotIn(
        agency: Agency,
        ids: List<Long>,
    ): List<Yacht>

    @Query(
        """
        SELECT y
        FROM Yacht y
        JOIN ExternalMapping em
            ON em.systemId = y.id
        WHERE y.agency = :agency
        AND em.type = 'Yacht'
        AND em.externalId NOT IN :externalIds
    """,
    )
    fun findAllByAgencyAndExternalIdNotIn(
        agency: Agency,
        externalIds: List<Long>,
    ): List<Yacht>

    fun findAllByAgencyId(agencyId: Long): List<Yacht>

    @Query(
        """
        SELECT new hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto(y.id, y.name, y.excludeDiscount)
        FROM Yacht y 
        WHERE y.agency.id = :agencyId
        ORDER BY y.name
    """,
    )
    fun findAllByAgencyIdToDto(agencyId: Long): List<AgencyYachtDto>

    @Query(
        """
        SELECT y
        FROM Yacht y
        JOIN FETCH y.reservationOptions ro
        JOIN FETCH y.agency a
        WHERE y.agency = :agency
        AND y.sysActive = true
        AND ro.dateTo > CURRENT_DATE
    """,
    )
    fun findWithReservationOptionsByAgency(agency: Agency): List<Yacht>

    @Query(
        """
        SELECT DISTINCT y.vesselType
        FROM Yacht y
        WHERE y.sysActive = true AND y.agency.active = true
        """,
    )
    fun getUsedVesselTypes(): List<Int>

    @Query(
        """
        SELECT y.vesselType AS vesselType, COUNT(*) AS count
        FROM Yacht y
        WHERE y.sysActive = true AND y.agency.active = true
        GROUP BY y.vesselType
        """,
    )
    fun getVesselTypeYachtCount(): List<Array<Any>>

    @Query(
        """
        SELECT DISTINCT yct.type
        FROM YachtCharterType yct
        JOIN Yacht y ON yct.yacht.id = y.id
        WHERE y.sysActive = true AND y.agency.active = true
        """,
    )
    fun getUsedCharterTypes(): List<Int>

    @Query(
        """
        SELECT y
        FROM Yacht y 
        JOIN ExternalMapping em ON y.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId
        AND em.externalId = :externalId
        AND em.type = 'Yacht'
    """,
    )
    fun findByExternalIdAndExternalSystemId(
        externalId: Long,
        externalSystemId: Long,
    ): Yacht?

    @Query(
        """
        SELECT y
        FROM Yacht y 
        JOIN ExternalMapping em ON y.id = em.systemId
        WHERE em.externalSystem.id = :externalSystemId
        AND em.externalId IN :externalIds
        AND em.type = 'Yacht'
    """,
    )
    fun findByExternalIdsAndExternalSystemId(
        externalIds: List<Long>,
        externalSystemId: Long,
    ): List<Yacht>

    fun countYachtsByModelId(modelId: Long): Int

    fun findByModelId(modelId: Long): List<Yacht>
}
