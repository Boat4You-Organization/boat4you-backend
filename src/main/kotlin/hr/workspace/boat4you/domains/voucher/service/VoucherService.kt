package hr.workspace.boat4you.domains.voucher.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.services.toLocale
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.voucher.dto.VoucherValidationDto
import hr.workspace.boat4you.domains.voucher.enums.VoucherStatus
import hr.workspace.boat4you.domains.voucher.jpa.Voucher
import hr.workspace.boat4you.domains.voucher.jpa.VoucherRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * EUR 100 loyalty voucher (Mario 10.8.2026): issued ONCE per customer after
 * their FIRST confirmed booking, redeemable on any future booking with a
 * client total >= 1500 EUR within 18 months. Transferable — the code is the
 * credential. The discount is absorbed by the first payment phase (see
 * ReservationPaymentPhasesService.applyVoucherAbsorption), so Stripe amounts
 * and every wire-instruction email pick it up from the phase rows unchanged.
 */
@Service
class VoucherService(
    private val voucherRepository: VoucherRepository,
    private val reservationRepository: ReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val emailService: EmailService,
    private val messageSource: MessageSource,
    @Value("\${server.host-public}") private val serverHostPublic: String,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    companion object {
        val VOUCHER_VALUE: BigDecimal = BigDecimal("100.00")
        val MIN_CLIENT_TOTAL: BigDecimal = BigDecimal("1500.00")
        const val VALIDITY_MONTHS = 18L

        /** Ambiguity-free alphabet — no 0/O/1/I. */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val CODE_GROUP_LENGTH = 4
    }

    private val random = SecureRandom()

    /**
     * Issues the once-per-customer voucher after a booking is confirmed and
     * emails it. Idempotent and deliberately swallow-all: issuance must never
     * break a confirmation — call sites wrap it in runCatching anyway.
     * Returns null when the user already has one, the booking isn't a real
     * confirmed first booking, or the unique-index race is lost.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun issueForConfirmedReservation(reservationId: Long): Voucher? {
        val reservation = reservationRepository.findById(reservationId).orElse(null) ?: return null

        if (reservation.sysStatus != ReservationStatus.RESERVATION) return null

        if (reservation.externalStatus == "FICTITIOUS") return null

        val flow = reservation.reservationFlow ?: return null
        val user = flow.user ?: return null
        val userId = user.id ?: return null

        if (voucherRepository.existsByIssuedToUserIdAndStatusNot(userId, VoucherStatus.REVOKED)) return null

        if (!isFirstJourney(userId)) return null

        val voucher = Voucher().apply {
            code = generateUniqueCode()
            value = VOUCHER_VALUE
            currency = "EUR"
            status = VoucherStatus.ACTIVE
            validFrom = LocalDate.now()
            validTo = LocalDate.now().plusMonths(VALIDITY_MONTHS)
            issuedToUserId = userId
            issuedForReservationId = reservationId
        }

        val saved = try {
            voucherRepository.saveAndFlush(voucher)
        } catch (e: DataIntegrityViolationException) {
            // Concurrent confirmation won the unique index — fine, exactly once.
            log.info("Voucher issuance race lost for user {} — already issued", userId)
            return null
        }

        log.info(
            "Issued loyalty voucher {} to user {} for reservation {} (valid to {})",
            saved.code, userId, reservationId, saved.validTo,
        )
        sendVoucherIssuedEmail(saved, user.email, user.getFullName(), user.language?.toLocale() ?: Locale.ENGLISH)

        return saved
    }

    /**
     * Read-only preview for the checkout field. No ownership check — the
     * voucher is transferable, the code itself is the credential.
     */
    @Transactional(readOnly = true)
    fun validate(code: String, clientTotal: BigDecimal?): VoucherValidationDto {
        val voucher = voucherRepository.findByCode(normalize(code))
            ?: return VoucherValidationDto(valid = false, reason = "NOT_FOUND")

        if (voucher.status != VoucherStatus.ACTIVE) return VoucherValidationDto(valid = false, reason = "NOT_ACTIVE")

        if (voucher.validTo!!.isBefore(LocalDate.now())) return VoucherValidationDto(valid = false, reason = "EXPIRED")

        if (clientTotal != null && clientTotal < MIN_CLIENT_TOTAL) {
            return VoucherValidationDto(valid = false, reason = "MIN_TOTAL_NOT_MET")
        }

        return VoucherValidationDto(
            valid = true,
            value = voucher.value,
            currency = voucher.currency,
            validTo = voucher.validTo,
        )
    }

    /**
     * Atomically claims the voucher for a reservation flow — participates in
     * the caller's transaction, so a failed flow creation rolls the claim
     * back. Returns the voucher value to absorb into the payment phases.
     */
    fun redeem(code: String, flowId: Long, userId: Long, clientTotal: BigDecimal): BigDecimal {
        if (clientTotal < MIN_CLIENT_TOTAL) {
            throw ParameterValidationException(
                mapOf("voucherCode" to "Vouchers require a charter total of at least $MIN_CLIENT_TOTAL EUR"),
            )
        }

        val value = jdbcTemplate.query(
            """
            UPDATE voucher
               SET status = 'USED', used_on_reservation_flow_id = ?, used_by_user_id = ?,
                   used_at = now(), updated_at = now()
             WHERE code = ? AND status = 'ACTIVE' AND valid_to >= CURRENT_DATE
            RETURNING value
            """.trimIndent(),
            { rs, _ -> rs.getBigDecimal("value") },
            flowId,
            userId,
            normalize(code),
        ).firstOrNull()
            ?: throw ParameterValidationException(
                mapOf("voucherCode" to "Voucher code is invalid, expired or already used"),
            )

        log.info("Voucher {} redeemed on flow {} by user {} (-{} EUR)", normalize(code), flowId, userId, value)

        return value
    }

    /**
     * A booking that redeemed a voucher was cancelled, expired unpaid or its
     * partner createOption failed — the voucher returns to the customer.
     * Idempotent; validTo still bounds any reuse.
     */
    fun releaseForFlow(flowId: Long) {
        val released = jdbcTemplate.update(
            """
            UPDATE voucher
               SET status = 'ACTIVE', used_on_reservation_flow_id = NULL, used_by_user_id = NULL,
                   used_at = NULL, updated_at = now()
             WHERE used_on_reservation_flow_id = ? AND status = 'USED'
            """.trimIndent(),
            flowId,
        )

        if (released > 0) log.info("Voucher released back to ACTIVE for cancelled flow {}", flowId)
    }

    /**
     * The SOURCE first booking was cancelled before its voucher was spent —
     * revoke it (72h cooling-off abuse guard). An already-USED voucher stays
     * used: that charter was granted in good faith.
     */
    fun revokeUnusedForSourceReservation(reservationId: Long, reason: String) {
        val revoked = jdbcTemplate.update(
            """
            UPDATE voucher
               SET status = 'REVOKED', revoked_at = now(), revoked_reason = ?, updated_at = now()
             WHERE issued_for_reservation_id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            reason,
            reservationId,
        )

        if (revoked > 0) log.info("Voucher revoked for cancelled source reservation {} ({})", reservationId, reason)
    }

    /**
     * First-booking check with yacht-swap awareness: replacement flows chain
     * through previous_flow_id, so one customer journey can own several
     * RESERVATION rows. Count distinct chain heads, not rows.
     */
    private fun isFirstJourney(userId: Long): Boolean {
        val confirmed = reservationRepository.findAllByUserIdAndSysStatus(userId, ReservationStatus.RESERVATION)

        if (confirmed.size <= 1) return confirmed.size == 1

        val chainHeads = confirmed
            .mapNotNull { it.reservationFlow?.id }
            .map { flowId -> reservationFlowRepository.findIdsInReservationFlowChain(flowId).firstOrNull() ?: flowId }
            .toSet()

        return chainHeads.size == 1
    }

    private fun generateUniqueCode(): String {
        val attempts = generateSequence { randomCode() }
            .take(5)
            .firstOrNull { voucherRepository.findByCode(it) == null }

        return attempts ?: throw IllegalStateException("Could not generate a unique voucher code in 5 attempts")
    }

    private fun randomCode(): String {
        val group = { (1..CODE_GROUP_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("") }

        return "B4Y-${group()}-${group()}"
    }

    private fun normalize(code: String): String = code.trim().uppercase()

    private fun sendVoucherIssuedEmail(voucher: Voucher, email: String, fullName: String, locale: Locale) {
        runCatching {
            val name = fullName.trim().takeIf { it.isNotBlank() } ?: "there"
            val recipientAddress = if (name != "there") "$name <$email>" else email
            val validToLabel = voucher.validTo!!.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            val valueLabel = "${voucher.value!!.stripTrailingZeros().toPlainString()} €"
            val minTotalLabel = "${MIN_CLIENT_TOTAL.stripTrailingZeros().toPlainString()} €"

            emailService.sendEmail(
                recipients = listOf(recipientAddress),
                subject = messageSource.getMessage("voucherIssued.subject", arrayOf(valueLabel), locale),
                templateName = "email/voucherIssued",
                variables = mapOf(
                    "fullName" to name,
                    "voucherCode" to voucher.code!!,
                    "voucherValueLabel" to valueLabel,
                    "validToLabel" to validToLabel,
                    "minTotalLabel" to minTotalLabel,
                    "publicUrl" to serverHostPublic,
                    "currentYear" to LocalDate.now().year.toString(),
                ),
                locale = locale,
            )
        }.onFailure { log.error("Failed to send voucherIssued email for voucher {}", voucher.code, it) }
    }
}
