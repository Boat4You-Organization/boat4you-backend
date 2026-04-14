package hr.workspace.boat4you.domains.reservation.jpa

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ReservationFlowRepository : JpaRepository<ReservationFlow, Long> {
    @Query(
        value = """
        WITH RECURSIVE to_head AS (
          SELECT r.id, r.previous_flow_id, 0 AS steps
          FROM reservation_flow r
          WHERE r.id = :reservationFlowId
          UNION ALL
          SELECT p.id, p.previous_flow_id, to_head.steps + 1
          FROM reservation_flow p
          JOIN to_head ON to_head.previous_flow_id = p.id
        ),
        head AS (
          SELECT to_head.id
          FROM to_head
          WHERE to_head.previous_flow_id IS NULL
          ORDER BY to_head.steps DESC
          LIMIT 1
        ),
        chain AS (
          SELECT head.id, 1 AS depth
          FROM head
          UNION ALL
          SELECT n.id, chain.depth + 1
          FROM reservation_flow n
          JOIN chain ON n.previous_flow_id = chain.id
        )
        SELECT id
        FROM chain
        ORDER BY depth
        """,
        nativeQuery = true,
    )
    fun findIdsInReservationFlowChain(reservationFlowId: Long): Set<Long>

    fun findByIdIn(ids: Set<Long>): Set<ReservationFlow>
}
