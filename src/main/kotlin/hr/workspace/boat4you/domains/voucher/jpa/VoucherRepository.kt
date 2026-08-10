package hr.workspace.boat4you.domains.voucher.jpa

import hr.workspace.boat4you.domains.voucher.enums.VoucherStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface VoucherRepository : JpaRepository<Voucher, Long> {
    fun findByCode(code: String): Voucher?

    fun findByUsedOnReservationFlowId(flowId: Long): Voucher?

    fun existsByIssuedToUserIdAndStatusNot(userId: Long, status: VoucherStatus): Boolean
}
