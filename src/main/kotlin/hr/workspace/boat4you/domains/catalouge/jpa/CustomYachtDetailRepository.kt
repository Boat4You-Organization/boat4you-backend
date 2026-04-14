package hr.workspace.boat4you.domains.catalouge.jpa

import org.springframework.data.jpa.repository.JpaRepository

interface CustomYachtDetailRepository : JpaRepository<CustomYachtDetail, Long> {
    fun findByYachtId(yachtId: Long): CustomYachtDetail?

    fun deleteByYachtId(yachtId: Long)
}
