package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface TripEventRepository : JpaRepository<TripEvent, Long>
