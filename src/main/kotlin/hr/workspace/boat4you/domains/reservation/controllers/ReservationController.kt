package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.reservation.dto.CancelReservationDto
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.MyReservationDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.MyReservationsDto
import hr.workspace.boat4you.domains.reservation.dto.PaymentPhaseDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.dto.YachtSwapInfoDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowQueryingService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationPaymentPhasesService
import hr.workspace.boat4you.domains.reservation.service.ReservationSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

@Tag(name = "Reservations")
@Validated
@RestController
@RequestMapping("/secured/reservations")
class ReservationController(
    private val reservationFlowMutationService: ReservationFlowMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationMutationService: ReservationMutationService,
    private val reservationEmailService: ReservationEmailService,
    private val reservationFlowQueryingService: ReservationFlowQueryingService,
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val userRepository: UserRepository,
    private val reservationRepository: ReservationRepository,
    private val reservationSyncService: ReservationSyncService,
    private val reservationDocumentService: hr.workspace.boat4you.domains.reservation.service.ReservationDocumentService,
) {
    @Operation(summary = "Get reservations for the authenticated user")
    @GetMapping("/my-reservations")
    fun getMyReservations(
        @RequestParam(name = "currency", required = false) curr: String?,
    ): ResponseEntity<List<MyReservationsDto>> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }

        if (user == null) {
            throw AccessDeniedException("User is not authenticated")
        }
        val currency = CurrencyEnum.getCurrency(curr, user)

        val reservations = reservationFlowQueryingService.getMyReservations(user.id!!, currency)
        return ResponseEntity.ok(reservations)
    }

    @Operation(summary = "Get reservation details for the authenticated user and reservation ID")
    @GetMapping("/my-reservations/{id}")
    fun getMyReservations(
        @PathVariable id: Long,
        @RequestParam(name = "currency", required = false) curr: String?,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<MyReservationDetailsDto> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }

        if (user == null) {
            throw AccessDeniedException("User is not authenticated")
        }

        val language = LanguageEnum.getLanguage(lang, user)
        val currency = CurrencyEnum.getCurrency(curr, user)

        val reservations = reservationFlowQueryingService.getMyReservationDetails(id, user.id!!, language, currency)
        return ResponseEntity.ok(reservations)
    }

    @Operation(summary = "Download a document attached to MY reservation. Authorisation: customer must own the reservation.")
    @GetMapping("/my-reservations/{id}/documents/{documentId}")
    @Transactional(readOnly = true)
    fun downloadMyReservationDocument(
        @PathVariable id: Long,
        @PathVariable documentId: Long,
    ): ResponseEntity<ByteArray> {
        val userId = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
            ?: throw AccessDeniedException("User is not authenticated")

        // Ownership guard — only the booking's user can download.
        val reservation = reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        if (reservation.reservationFlow?.user?.id != userId) {
            throw AccessDeniedException("Reservation $id does not belong to user $userId")
        }

        val doc = reservationDocumentService.download(documentId)
        if (doc.reservationId != id) {
            throw AccessDeniedException("Document $documentId does not belong to reservation $id")
        }
        // Internal admin uploads (handover notes, agency back-office scans)
        // must NEVER reach the customer — even if they guess the document id.
        if (doc.isInternal) {
            throw AccessDeniedException("Document $documentId is internal-only")
        }

        val safeName = doc.filename.replace("\"", "")
        val headers = org.springframework.http.HttpHeaders().apply {
            contentType = org.springframework.http.MediaType.parseMediaType(doc.contentType)
            set(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$safeName\"")
            contentLength = doc.data.size.toLong()
        }
        return ResponseEntity.ok().headers(headers).body(doc.data)
    }

    @Operation(description = "Create a new reservation as a user or administrator")
    @PostMapping
    fun create(
        @RequestBody @Valid createReservationDto: CreateReservationDto,
    ): ResponseEntity<ReservationDto> {
        val reservationFlowId = reservationFlowMutationService.createReservationFlow(createReservationDto)

        val externalReservation =
            reservationIntegrationService.createExternalReservation(reservationFlowId)

        val reservation = reservationMutationService.createReservation(reservationFlowId, externalReservation)

        if (externalReservation.calculatedSysStatus == ReservationStatus.OPTION) {
            reservationEmailService.sendOptionCreatedEmail(reservation.id)
        } else {
            throw IllegalStateException(
                "External reservation status is not OPTION, but ${externalReservation.calculatedSysStatus}",
            )
        }

        return ResponseEntity.ok(reservation)
    }

    @Operation(description = "Send cancellation request")
    @PostMapping("/{id}/cancel")
    // Needs an open Hibernate session for the ownership check — it walks
    // `reservation.reservationFlow.user.id` (both relations are LAZY), so
    // without a transaction the proxy resolves against a closed session and
    // throws `Could not initialize proxy - no session`.
    //
    // NOT readOnly — the inner `createCancellationRequest.save()` call runs in
    // the SAME propagated transaction (Propagation.REQUIRED default) and a
    // readOnly outer tx makes Hibernate skip the flush → UPDATE never hits
    // the DB even though the endpoint returns 200.
    @Transactional
    fun cancelReservationRequest(
        @PathVariable id: Long,
        @RequestBody @Valid cancelReservationDto: CancelReservationDto,
    ): ResponseEntity<Unit> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }

        if (user == null) {
            throw AccessDeniedException("User is not authenticated")
        }

        val reservation = reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        if (reservation.reservationFlow!!.user!!.id != user.id) {
            throw AccessDeniedException("User is not authenticated")
        }

        reservationMutationService.createCancellationRequest(id, cancelReservationDto)
        reservationEmailService.sendRequestCancellation(id)
        return ResponseEntity.ok().build()
    }

    @Operation(
        summary = "Calculate payment phases for any given price",
        description = "Calculate Reservation payment phases for the given price (regardless of the currency) and reservation startDate. User needs to be authenticated.",
    )
    @GetMapping("/paymentPhases")
    fun calculateReservationPaymentPhases(
        @Parameter(description = "Date in ISO 8601 format (YYYY-MM-DD)") @RequestParam(
            name = "currentDate",
            required = false,
        ) currentDate: LocalDate = LocalDate.now(),
        @Parameter(description = "Date in ISO 8601 format (YYYY-MM-DD)") @RequestParam(
            name = "reservationDateFrom",
            required = true,
        ) reservationDateFrom: LocalDate,
        @RequestParam(name = "price", required = true) price: BigDecimal,
    ): ResponseEntity<List<PaymentPhaseDto>> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }

        if (user == null) {
            throw AccessDeniedException("User is not authenticated")
        }

        val paymentPhases =
            paymentPhasesService.calculatePaymentPhases(
                now = currentDate,
                reservationStartDate = reservationDateFrom,
                totalPrice = price,
            )

        return ResponseEntity.ok(
            paymentPhases.map {
                PaymentPhaseDto(
                    deadline = it.first,
                    amount = it.second.toBigDecimal(),
                )
            },
        )
    }

    @Operation(summary = "Get latest unacknowledged yacht-swap event for a reservation")
    @GetMapping("/{id}/yacht-swap")
    @Transactional(readOnly = true)
    fun getYachtSwap(
        @PathVariable id: Long,
    ): ResponseEntity<YachtSwapInfoDto> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }
                ?: throw AccessDeniedException("User is not authenticated")

        val reservation =
            reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        if (reservation.reservationFlow!!.user!!.id != user.id) {
            throw AccessDeniedException("Reservation does not belong to authenticated user")
        }

        val info = reservationSyncService.getLatestSwap(id, unacknowledgedOnly = true)
        return if (info != null) ResponseEntity.ok(info) else ResponseEntity.noContent().build()
    }

    @Operation(summary = "Acknowledge (dismiss) the latest yacht-swap banner for a reservation")
    @PostMapping("/{id}/yacht-swap/acknowledge")
    @Transactional
    fun acknowledgeYachtSwap(
        @PathVariable id: Long,
    ): ResponseEntity<Unit> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).getOrNull() }
                ?: throw AccessDeniedException("User is not authenticated")

        val reservation =
            reservationRepository.findById(id).getOrElse { throw ReservationNotExistException() }
        if (reservation.reservationFlow!!.user!!.id != user.id) {
            throw AccessDeniedException("Reservation does not belong to authenticated user")
        }

        reservationSyncService.acknowledgeLatestSwap(id)
        return ResponseEntity.ok().build()
    }
}
