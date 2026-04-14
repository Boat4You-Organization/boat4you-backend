package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface ExternalSystemRepository : JpaRepository<ExternalSystem, Long>
