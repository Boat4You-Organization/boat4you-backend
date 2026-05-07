package hr.workspace.boat4you.domains.reservation.controllers

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.services.CharterAgreementService
import hr.workspace.boat4you.domains.reservation.dto.DashboardMetricsDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.dto.PaymentPhaseDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDocumentDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDetailsDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationViewDto
import hr.workspace.boat4you.domains.reservation.enums.PaymentType
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.dto.AdminCreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.CreateFictitiousReservationDto
import hr.workspace.boat4you.domains.reservation.dto.YachtSwapInfoDto
import hr.workspace.boat4you.domains.reservation.mapper.ReservationMappers
import hr.workspace.boat4you.domains.reservation.service.ReservationDocumentService
import hr.workspace.boat4you.domains.reservation.service.ReservationEmailService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationFlowQueryingService
import hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService
import hr.workspace.boat4you.domains.reservation.service.ReservationMutationService
import hr.workspace.boat4you.domains.reservation.service.ReservationPaymentPhasesService
import hr.workspace.boat4you.domains.reservation.service.ReservationSyncService
import jakarta.validation.Valid
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.data.web.PagedModel
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "Reservation Management", description = "Manage Reservations for Administrators")
@RestController
@RequestMapping("/admin/reservations")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
internal class AdminReservationController(
    private val reservationMutationService: ReservationMutationService,
    private val reservationIntegrationService: ReservationIntegrationService,
    private val reservationEmailService: ReservationEmailService,
    private val reservationFlowQueryingService: ReservationFlowQueryingService,
    private val reservationFlowMutationService: ReservationFlowMutationService,
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val userRepository: UserRepository,
    private val reservationSyncService: ReservationSyncService,
    private val charterAgreementService: CharterAgreementService,
    private val reservationRepository: ReservationRepository,
    private val reservationDocumentService: ReservationDocumentService,
    private val reservationMappers: ReservationMappers,
) {
    private val log = LoggerFactory.getLogger(AdminReservationController::class.java)

    @Operation(summary = "Aggregates for the admin dashboard (KPI cards + weekly chart).")
    @GetMapping("/dashboard-metrics")
    fun getDashboardMetrics(): ResponseEntity<DashboardMetricsDto> {
        return ResponseEntity.ok(reservationFlowQueryingService.getDashboardMetrics())
    }

    @Operation(summary = "Get all reservations")
    @GetMapping()
    fun getReservations(
        @RequestParam(name = "status", required = false) status: ReservationStatus?,
        @RequestParam(name = "userId", required = false) userId: Long?,
        @RequestParam(name = "dateFrom", required = false) dateFrom: LocalDate?,
        @RequestParam(name = "dateTo", required = false) dateTo: LocalDate?,
        @RequestParam(name = "reservationId", required = false) reservationId: Long?,
        // Free-text search across reservation number + client name +
        // email (case-insensitive LIKE %search%). Surfaced on the admin
        // Bookings list filter bar so brokers can type "100194/2026",
        // "Mario", "schmidt@…" and hit the right row without worrying
        // which column holds the value.
        @RequestParam(name = "search", required = false) search: String?,
        @PageableDefault(
            sort = ["reservationCreatedAt"],
            direction = Sort.Direction.DESC,
        ) pageable: Pageable,
    ): ResponseEntity<PagedModel<ReservationViewDto>> {
        val reservations =
            reservationFlowQueryingService.getAllForAdmin(status, userId, dateFrom, dateTo, reservationId, search, pageable)
        return ResponseEntity.ok(PagedModel(reservations))
    }

    @Operation(summary = "Get reservation details by ID")
    @GetMapping("/{id}")
    fun getReservationDetails(
        @PathVariable id: Long,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<ReservationViewDetailsDto> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val currency = CurrencyEnum.getCurrency(null, user)
        val language = LanguageEnum.getLanguage(lang, user)

        val reservation = reservationFlowQueryingService.getReservationByIdForAdmin(id, currency, language)
        return ResponseEntity.ok(reservation)
    }

    @Operation(summary = "Get reservation details by customer-facing Order No.")
    @GetMapping("/byNumber/{sequence}/{year}")
    fun getReservationDetailsByNumber(
        @PathVariable sequence: String,
        @PathVariable year: Int,
        @RequestHeader(name = "Accept-Language", required = false) lang: String? = null,
    ): ResponseEntity<ReservationViewDetailsDto> {
        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        val currency = CurrencyEnum.getCurrency(null, user)
        val language = LanguageEnum.getLanguage(lang, user)

        // Reservation numbers are stored as `{sequence}/{year}` — Spring won't
        // accept `/` in a single path variable, so the URL uses two segments
        // and we recompose the value here.
        val reservationNumber = "$sequence/$year"
        val reservation = reservationFlowQueryingService.getReservationByNumberForAdmin(reservationNumber, currency, language)
        return ResponseEntity.ok(reservation)
    }

    @Operation(summary = "Get latest yacht-swap event (acknowledged or not) for a reservation")
    @GetMapping("/{id}/yacht-swap")
    fun getYachtSwapForAdmin(
        @PathVariable id: Long,
    ): ResponseEntity<YachtSwapInfoDto> {
        val info = reservationSyncService.getLatestSwap(id, unacknowledgedOnly = false)
        return if (info != null) ResponseEntity.ok(info) else ResponseEntity.noContent().build()
    }

    @Operation(summary = "Manually trigger yacht-swap sync across all active reservations (INK1/INK2)")
    @PostMapping("/sync-yachts")
    fun triggerYachtSwapSync(): ResponseEntity<Unit> {
        reservationSyncService.syncActiveReservations()
        return ResponseEntity.ok().build()
    }

    @Operation(summary = "Update admin-only notes on a reservation")
    @PatchMapping("/{id}/adminNotes")
    fun updateAdminNotes(
        @PathVariable id: Long,
        @RequestBody body: AdminNotesBody,
    ): ResponseEntity<ReservationDto> {
        return ResponseEntity.ok(reservationMutationService.updateAdminNotes(id, body.notes))
    }

    @Operation(
        summary = "Set/clear the crew-list URL on a reservation",
        description = "Admin enters the partner-supplied crew-list link (or null to clear). " +
            "Customer renders it as 'Open crew list' CTA in /my-bookings/{id}.",
    )
    @PatchMapping("/{id}/crewListUrl")
    fun updateCrewListUrl(
        @PathVariable id: Long,
        @RequestBody body: CrewListUrlBody,
    ): ResponseEntity<ReservationDto> {
        return ResponseEntity.ok(reservationMutationService.updateCrewListUrl(id, body.crewListUrl))
    }

    @Operation(description = "Refresh reservation status from external service")
    @PostMapping("/{id}/refresh")
    fun refreshReservation(
        @PathVariable id: Long,
    ): ResponseEntity<ReservationDto> {
        val externalReservation =
            reservationIntegrationService.getExternalReservation(id)
        val reservation = reservationMutationService.refreshReservation(id, externalReservation)
        return ResponseEntity.ok(reservation)
    }

    @Operation(
        description = "Confirm reservation and promote it to booking. Manual flow: the broker has " +
            "already confirmed with the agency out-of-band (phone / email) and this endpoint just " +
            "records the confirmation in our DB. We do NOT call the partner (Nausys/MMK) — see " +
            "ReservationMutationService.confirmReservationManually for the rationale.",
    )
    @PutMapping("/{id}/{paymentPhaseIds}")
    fun confirmReservation(
        @PathVariable id: Long,
        @Parameter(description = "Payment phases which will be marked as paid") @PathVariable paymentPhaseIds: List<Long> = emptyList(),
    ): ResponseEntity<ReservationDto> {
        val reservationResponse =
            reservationMutationService.confirmReservationManually(id, paymentPhaseIds)

        reservationEmailService.sendConfirmationForReserved(reservationResponse, PaymentType.BANK_TRANSFER)

        return ResponseEntity.ok(reservationResponse)
    }

    @Operation(
        summary = "Download the charter agreement PDF",
        description = "Generates the per-reservation charter agreement on the fly (Page 1 = " +
            "booking data, Page 2+ = T&C with country-specific jurisdiction text) and returns " +
            "it as `application/pdf`. Same artefact that ships as an attachment on the " +
            "confirmation email — exposed here so admin can download / re-download from the " +
            "Bookings detail screen without resending the email.",
    )
    @GetMapping("/{id}/charter-agreement.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @Transactional(readOnly = true)
    fun downloadCharterAgreement(
        @PathVariable id: Long,
    ): ResponseEntity<ByteArray> {
        val reservation = reservationRepository.findById(id).orElseThrow()
        // PDF rendering walks lazy associations (reservationFlow → user / yacht
        // / model / paymentPhases / extras / location → country). Without
        // `@Transactional` the Hibernate session closes after `findById` and
        // every lazy proxy dereference inside the renderer throws
        // LazyInitializationException. Read-only is enough — we don't mutate.
        val pdfBytes = charterAgreementService.renderToPdf(reservation)
        val ref = reservation.reservationNumber ?: id.toString()
        val filename = "charter-agreement-${ref.replace('/', '-')}.pdf"
        // `inline` = browser opens it in the built-in PDF viewer; the user
        // can still hit the browser's download button. `attachment` would
        // force a save dialog. Mario asked "moze se skinut" — inline + the
        // viewer's download button covers both flows in one click.
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_PDF
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$filename\"")
            contentLength = pdfBytes.size.toLong()
        }
        return ResponseEntity.ok().headers(headers).body(pdfBytes)
    }

    @Operation(summary = "List documents attached to the reservation")
    @GetMapping("/{id}/documents")
    fun listDocuments(
        @PathVariable id: Long,
    ): ResponseEntity<List<ReservationDocumentDto>> =
        ResponseEntity.ok(reservationDocumentService.list(id))

    @Operation(
        summary = "Upload a PDF/DOC/DOCX document to the reservation",
        description = "Pass `internal=true` to mark the doc admin-only (hidden from " +
            "customer my-bookings sidebar). Default is customer-visible.",
    )
    @PostMapping("/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadDocument(
        @PathVariable id: Long,
        @org.springframework.web.bind.annotation.RequestParam("file") file: org.springframework.web.multipart.MultipartFile,
        @org.springframework.web.bind.annotation.RequestParam("internal", required = false, defaultValue = "false") internal: Boolean,
    ): ResponseEntity<ReservationDocumentDto> {
        val uploadedBy = getAuthenticatedUserId().takeIf { it != ANONYMOUS_USER_ID }
        return ResponseEntity.ok(reservationDocumentService.upload(id, file, uploadedBy, internal))
    }

    @Operation(summary = "Download a single attached document")
    @GetMapping("/{id}/documents/{documentId}")
    fun downloadDocument(
        @PathVariable id: Long,
        @PathVariable documentId: Long,
    ): ResponseEntity<ByteArray> {
        val doc = reservationDocumentService.download(documentId)
        val safeName = doc.filename.replace("\"", "")
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(doc.contentType)
            set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$safeName\"")
            contentLength = doc.data.size.toLong()
        }
        return ResponseEntity.ok().headers(headers).body(doc.data)
    }

    @Operation(summary = "Remove an attached document")
    @DeleteMapping("/{id}/documents/{documentId}")
    fun deleteDocument(
        @PathVariable id: Long,
        @PathVariable documentId: Long,
    ): ResponseEntity<Unit> {
        reservationDocumentService.delete(documentId)
        return ResponseEntity.noContent().build()
    }

    @Operation(description = "Mark payment phase(s) as paid. Does not promote a reservation to booking")
    @PostMapping("/{id}/markPaymentPhasePaid/{paymentPhaseIds}")
    fun markPaymentPhasePaid(
        @PathVariable id: Long,
        @Parameter(description = "Payment phases which will be marked as paid") @PathVariable paymentPhaseIds: List<Long> = emptyList(),
    ): ResponseEntity<List<PaymentPhaseDto>> {
        return ResponseEntity.ok(paymentPhasesService.markPaymentPhasePaid(id, paymentPhaseIds))
    }

    @Operation(
        summary = "Admin create replacement reservation",
        description = "Create a new reservation for an existing customer. Used when the original " +
            "yacht is cancelled and the agency offers a different boat. Admin sets total price " +
            "and payment phases explicitly; first phase can be marked paid to carry over the " +
            "customer's pre-existing payment on the cancelled reservation. Generates a fresh " +
            "booking number — never reuses the cancelled one (matches MMK / NauSYS convention).",
    )
    @PostMapping
    fun adminCreateReservation(
        @RequestBody @Valid body: AdminCreateReservationDto,
    ): ResponseEntity<ReservationDto> {
        // Step 1: create the reservation flow with admin-supplied details.
        // Flow owner = target customer (not the admin); admin is recorded
        // separately as `createdBy` so audit trail tells them apart.
        val flowId = reservationFlowMutationService.createAdminReservationFlow(body)

        // Step 2: go through the same external-reservation path the customer
        // flow uses — this respects DEV BYPASS (test.enabled=true => synthetic
        // mock response) and in prod it actually hits MMK/NauSys to hold the
        // option on the partner side.
        val externalReservation = reservationIntegrationService.createExternalReservation(flowId)

        // Step 3: persist the Reservation entity with admin's price override
        // and admin-only notes. Payment phase rows are already in place from
        // step 1 (flow creation), so `createPaymentPlans` is skipped inside
        // the mutation service when `adminOverridePrice` is non-null.
        val reservation = reservationMutationService.createReservation(
            reservationFlowId = flowId,
            externalReservation = externalReservation,
            adminOverridePrice = body.totalPrice,
            adminNotes = body.adminNotes,
        )

        // Step 4: optional option-created email to the customer. Default off
        // so admin controls the moment the customer is notified (e.g. they
        // want to call first before an email drops in their inbox). Email
        // failure must not roll back the reservation, but it MUST be logged
        // so support can chase it up.
        if (body.sendOptionEmail) {
            runCatching { reservationEmailService.sendOptionCreatedEmail(reservation.id!!) }
                .onFailure { e -> log.error("Failed to send option-created email for reservation ${reservation.id}", e) }
        }

        return ResponseEntity.ok(reservation)
    }

    @Operation(
        summary = "Admin fictitious replacement reservation",
        description = "Creates a reservation that lives ONLY in our DB — used when the agency " +
            "moved the customer onto a different yacht outside our system (broken-boat swap they " +
            "handled directly in Nausys/MMK). No partner API call, no offer row required. " +
            "Reservation lands as RESERVATION (confirmed) with external_id=null; sync jobs " +
            "cannot pull partner-side updates for it.",
    )
    @PostMapping("/fictitious")
    fun createFictitiousReservation(
        @RequestBody @Valid body: CreateFictitiousReservationDto,
    ): ResponseEntity<ReservationDto> {
        val reservation = reservationFlowMutationService.createFictitiousReservation(body)
        return ResponseEntity.ok(reservation)
    }

    @Operation(description = "Cancel reservation")
    @DeleteMapping("/{id}")
    fun cancelReservation(
        @PathVariable id: Long,
        // Optional reason typed by the admin at cancel time. When present it
        // is surfaced to the customer on /my-bookings/{id} so they understand
        // why the booking was cancelled without needing a follow-up call.
        @RequestBody(required = false) body: CancelBody? = null,
    ): ResponseEntity<ReservationDto> {
        val externalReservation = reservationIntegrationService.deleteExternalReservation(id)
        val reservationResponse = reservationMutationService.cancelReservation(id, externalReservation, body?.reason)
        // Mario rule (May 2026): every cancellation decision triggers an
        // email to the customer — rejected has its own send, approved goes
        // here. Wrapped in runCatching so a mail-side hiccup doesn't fail
        // the API response after the booking is already cancelled in DB.
        // onFailure logs so support can spot mail-side regressions.
        runCatching { reservationEmailService.sendCancellationApproved(id) }
            .onFailure { e -> log.error("Failed to send cancellation-approved email for reservation $id", e) }
        return ResponseEntity.ok(reservationResponse)
    }

    @Operation(
        summary = "Reject the customer's cancellation request",
        description = "Used when the partner agency refuses the cancellation. Stamps " +
            "`cancelationRejectedAt` + `cancelationRejectedReason` on the flow, leaves " +
            "the reservation in BOOKING/CONFIRMED, and emails the customer with the " +
            "admin's explanation. The original `cancelationRequest` is preserved as history.",
    )
    @PostMapping("/{id}/cancellation/reject")
    fun rejectCancellationRequest(
        @PathVariable id: Long,
        @RequestBody dto: RejectCancellationRequestDto,
    ): ResponseEntity<ReservationDto> {
        val reservation = reservationMutationService.rejectCancellationRequest(id, dto.reason)
        reservationEmailService.sendCancellationRejected(id)
        return ResponseEntity.ok(reservationMappers.toReservationDto(reservation))
    }

}

/** Body for PATCH /{id}/adminNotes — null/empty string clears the notes. */
data class AdminNotesBody(val notes: String?)

/** Body for PATCH /{id}/crewListUrl — null/empty clears the URL. */
data class CrewListUrlBody(val crewListUrl: String?)

/** Optional body for DELETE /{id} — admin-provided reason shown to the customer. */
data class CancelBody(val reason: String?)

/** Body for POST /{id}/cancellation/reject — admin's explanation of why the
 *  cancellation cannot be approved (typically: agency policy doesn't allow it). */
data class RejectCancellationRequestDto(val reason: String)
