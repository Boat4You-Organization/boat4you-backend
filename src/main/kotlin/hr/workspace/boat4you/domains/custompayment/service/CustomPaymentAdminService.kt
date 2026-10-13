package hr.workspace.boat4you.domains.custompayment.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.custompayment.dto.AdminCustomPaymentDto
import hr.workspace.boat4you.domains.custompayment.dto.CustomPaymentPageDto
import hr.workspace.boat4you.domains.custompayment.exceptions.CustomPaymentNotFoundException
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPayment
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentRepository
import hr.workspace.boat4you.domains.custompayment.jpa.CustomPaymentStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.PagedModel
import org.springframework.stereotype.Service

/** Admin list, note and Refresh of the custom payments. */
@Service
class CustomPaymentAdminService(
    private val repository: CustomPaymentRepository,
    private val reservationRepository: ReservationRepository,
    private val tx: CustomPaymentTx,
    private val customPaymentService: CustomPaymentService,
) {
    fun list(
        status: CustomPaymentStatus?,
        site: String?,
        q: String?,
        page: Int,
        size: Int,
    ): CustomPaymentPageDto {
        val siteFilter = site?.trim()?.ifEmpty { null }
        val search = q?.trim()?.ifEmpty { null }
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        val result = repository.searchForAdmin(status, siteFilter, search, pageable)
        val numbers = reservationNumbers(result.content)
        return CustomPaymentPageDto(
            content = result.content.map { it.toAdminDto(numbers) },
            page = PagedModel.PageMetadata(result.size.toLong(), result.number.toLong(), result.totalElements, result.totalPages.toLong()),
            totalReceivedMinor = repository.sumCompletedNet(status, siteFilter, search, CustomPaymentStatus.COMPLETED),
        )
    }

    fun updateNote(
        id: Long,
        note: String?,
    ): AdminCustomPaymentDto {
        val cleaned = note?.trim()?.ifEmpty { null }
        if (cleaned != null && cleaned.length > MAX_NOTE_LENGTH) {
            throw ParameterValidationException(mapOf("note" to "at most $MAX_NOTE_LENGTH characters"))
        }
        val row = tx.updateNote(id, cleaned) ?: throw CustomPaymentNotFoundException()
        return row.toAdminDto(reservationNumbers(listOf(row)))
    }

    fun refresh(id: Long): AdminCustomPaymentDto {
        val row = customPaymentService.reconcile(id)
        return row.toAdminDto(reservationNumbers(listOf(row)))
    }

    private fun reservationNumbers(rows: List<CustomPayment>): Map<Long, String?> {
        val ids = rows.mapNotNull { it.reservationId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return reservationRepository.findReservationNumbersByIdIn(ids).associate { (it[0] as Number).toLong() to it[1] as String? }
    }

    private fun CustomPayment.toAdminDto(numbers: Map<Long, String?>) =
        AdminCustomPaymentDto(
            id = id!!,
            site = site,
            createdAt = createdAt,
            completedAt = completedAt,
            reference = reference,
            amountMinor = amountMinor,
            refundedAmountMinor = refundedAmountMinor,
            currency = currency,
            status = status,
            payerEmail = payerEmail,
            providerOrderId = providerOrderId,
            adminNote = adminNote,
            reservationId = reservationId,
            reservationNumber = reservationId?.let { numbers[it] },
        )

    private companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_NOTE_LENGTH = 2000
    }
}
