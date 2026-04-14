package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface AgencySourceRepository : JpaRepository<AgencySource, AgencySourceId>
