package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface ReservationOptionRepository : JpaRepository<ReservationOption, Long> {
    fun findAllByYacht(yacht: Yacht): List<ReservationOption>

    fun findOneByYacht(yacht: Yacht): ReservationOption?
}
