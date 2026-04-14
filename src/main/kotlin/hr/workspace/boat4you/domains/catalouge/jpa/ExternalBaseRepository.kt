package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface ExternalBaseRepository : JpaRepository<ExternalBase, Long> {
    fun findByAgencyIdAndLocationId(
        agencyId: Long,
        locationId: Long,
    ): List<ExternalBase>
}
