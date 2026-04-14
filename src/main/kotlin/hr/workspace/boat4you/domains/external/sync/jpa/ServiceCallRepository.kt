package hr.workspace.boat4you.domains.external.sync.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface ServiceCallRepository : JpaRepository<ServiceCall, Long>
