package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.common.services.toLanguageEnum
import org.springframework.context.i18n.LocaleContextHolder
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Extra
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.PriceCalculationService
import hr.workspace.boat4you.domains.reservation.dto.CreateFictitiousReservationDto
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.ReservationDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationFlowStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationUserNotExists
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtraRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.mapper.ReservationMappers
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRegistrationStatusEnum
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.domains.users.services.UserInviteService
import hr.workspace.boat4you.domains.users.services.UserMutationService
import org.openapitools.model.RoleEnum
import org.openapitools.model.User
import org.openapitools.model.UserRole
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ReservationFlowMutationService(
    private val userRepository: UserRepository,
    private val yachtRepository: YachtRepository,
    private val offerRepository: OfferRepository,
    private val externalReservationRepository: ExternalReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val userMutationService: UserMutationService,
    private val extraRepository: ExtraRepository,
    private val reservationExtraRepository: ReservationExtraRepository,
    private val priceCalculationService: PriceCalculationService,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val inviteService: UserInviteService,
    private val reservationRepository: ReservationRepository,
    private val reservationMappers: ReservationMappers,
    private val bookingNumberService: BookingNumberService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional
    fun createReservationFlow(createReservationDto: CreateReservationDto): Long {
        // Guest checkout support: authentication is OPTIONAL. When called from
        // the public endpoint (/public/reservations), there is no authenticated
        // principal — the user is identified solely by the email in the DTO.
        val loggedUser =
            SecurityContextHolder.getContext().authentication?.principal as? UserDomainEntity
        val createdByUser = loggedUser?.let { userRepository.findByEmail(it.email) }

        val yacht = yachtRepository.findById(createReservationDto.yachtId).get()
        // Pessimistic-write lock (Deploy 3) serialises concurrent booking attempts on
        // the same offer so a TOCTOU race cannot double-book a slot.
        val offer =
            offerRepository.findByIdForUpdate(createReservationDto.offerId)
                ?: throw IllegalArgumentException("Offer not found")

        if (yacht.isInquireOnly()) {
            throw IllegalArgumentException("Yacht is inquire only, reservation cannot be created")
        }

        if (offer.status != OfferStatus.FREE) {
            throw IllegalArgumentException("Offer is not available for reservation")
        }

        // Re-assert against the live partner busy intervals (Deploy 3): even if the offer
        // row is a stale FREE (sync lag), reject when a real RESERVATION/SERVICE overlaps
        // the offer's own window, so we never option a slot that is actually taken.
        val hardBlocked =
            externalReservationRepository.existsBlockingOverlap(
                yacht.id!!,
                listOf(ExternalReservationStatus.RESERVATION, ExternalReservationStatus.SERVICE),
                offer.dateFrom!!,
                offer.dateTo!!,
            )
        if (hardBlocked) {
            throw IllegalArgumentException("Offer is not available for reservation")
        }

        if (offer.yacht!!.id != yacht.id) {
            throw IllegalArgumentException("Offer does not belong to the selected yacht")
        }

        val agency = yacht.agency
        if (yacht.entryType == EntryType.EXTERNAL && (agency == null || !agency.active!!)) {
            throw AgencyNotActiveException()
        }

        if (yacht.entryType != EntryType.EXTERNAL){
            throw IllegalArgumentException("Yacht is not external, and is not available for reservation over external service")
        }

        val reservationUser =
            getUser(
                loggedUser = loggedUser,
                createdByUser = createdByUser,
                createReservationDto = createReservationDto,
            ) ?: createUser(createReservationDto)

        val priceCalculated =
            priceCalculationService.calculatePrice(
                yacht,
                offer,
                null,
                createReservationDto.selectedExtras ?: emptySet(),
            )

        val reservationFlow = ReservationFlow()
        reservationFlow.yacht = yacht
        reservationFlow.offer = offer
        reservationFlow.user = reservationUser
        // Guest bookings: the user creates the flow for themselves.
        reservationFlow.createdBy = createdByUser ?: reservationUser
        reservationFlow.createdAt = Instant.now()
        reservationFlow.status = ReservationFlowStatus.IN_PROGRESS
        reservationFlow.email = createReservationDto.email
        reservationFlow.name = createReservationDto.name
        reservationFlow.surname = createReservationDto.surname
        reservationFlow.phoneNumber = createReservationDto.phoneNumber
        reservationFlow.specialRequest = createReservationDto.specialRequest
        reservationFlow.calculatedTotalPrice = priceCalculated.totalPriceEur
        reservationFlow.agencyCommission = offer.agencyCommission

        reservationFlowRepository.save(reservationFlow)

        priceCalculated.selectedExtrasInPrice.forEach {
            val extra = ReservationExtra()
            extra.reservationFlow = reservationFlow
            extra.price = it.priceEur
            extra.yachtExtrasKey = it.key
            extra.extras = it.extrasId?.let { id -> getExtras(id) }
            extra.name = it.name
            extra.obligatory = it.obligatory
            extra.payableAtBase = it.payableInBase
            extra.unitPrice = it.unitPriceEur
            extra.unit = it.unit
            extra.sourceId = it.id
            extra.externalId = it.externalId
            reservationExtraRepository.save(extra)
            reservationFlow.reservationExtras.add(extra)
        }

        priceCalculated.selectedExtrasAtBase.forEach {
            val extra = ReservationExtra()
            extra.reservationFlow = reservationFlow
            extra.price = it.priceEur
            extra.yachtExtrasKey = it.key
            extra.extras = it.extrasId?.let { id -> getExtras(id) }
            extra.name = it.name
            extra.obligatory = it.obligatory
            extra.payableAtBase = it.payableInBase
            extra.unitPrice = it.unitPriceEur
            extra.unit = it.unit
            extra.sourceId = it.id
            extra.externalId = it.externalId
            reservationExtraRepository.save(extra)
            reservationFlow.reservationExtras.add(extra)
        }

        // Prefer partner-side payment plan from the offer (each agency configures
        // its own 1/2/3-instalment schedule); fall back to B4Y A/B/C rules when
        // partner sync didn't ship one. See ReservationPaymentPhasesService kdoc.
        val paymentPhases =
            paymentPhasesService
                .calculatePaymentPhases(
                    offer = offer,
                    clientTotalPrice = reservationFlow.calculatedTotalPrice!!.toDouble(),
                ).map {
                    ReservationPaymentPhase().apply {
                        deadline = it.first
                        amount = it.second.toBigDecimal()
                        this.reservationFlow = reservationFlow
                    }
                }
        paymentPhaseRepository.saveAll(paymentPhases)
        reservationFlow.paymentPhases.addAll(paymentPhases)

        reservationFlowRepository.saveAndFlush(reservationFlow)

        return reservationFlow.id!!
    }

    private fun getUser(
        loggedUser: UserDomainEntity?,
        createdByUser: UserEntity?,
        createReservationDto: CreateReservationDto,
    ): UserEntity? {
        // Guest booking: no authenticated principal — resolve by email from DTO
        // (find existing user or let the caller fall through to createUser()).
        if (loggedUser == null) {
            if (createReservationDto.email.isBlank()) {
                throw ReservationUserNotExists("Email is required for guest bookings.")
            }
            return userRepository.findByEmail(createReservationDto.email)
        }
        // admin creates reservationFlow for a user
        return if (!loggedUser.isSystemAdmin()) {
            createdByUser
        } else {
            if (createReservationDto.email.isBlank()) {
                throw ReservationUserNotExists("User email must be provided when creating a reservation flow as a system admin.")
            }
            userRepository.findByEmail(createReservationDto.email)
        }
    }

    private fun createUser(createReservationDto: CreateReservationDto): UserEntity {
        val newUser =
            User(
                email = createReservationDto.email,
                name = createReservationDto.name ?: "",
                surname = createReservationDto.surname ?: "",
                roles = listOf(UserRole(RoleEnum.USER)),
            )
        val newUserEntity = userMutationService.createUser(newUser)

        // `User.toJpaUserEntity()` forces `registrationStatus = REGISTERED` for
        // every new user — that's fine for admin-created users, but for a guest
        // it blocks `/public/users/set-password-for-reservation` (which rejects
        // if already REGISTERED). Flip this guest back to STARTED so they can
        // set their own password after payment.
        val guestUser =
            userRepository
                .findById(newUserEntity.id!!)
                .orElseThrow { UserDoesNotExistException() }
        guestUser.registrationStatus = UserRegistrationStatusEnum.STARTED
        // Stamp the front-end locale (resolved by `next-intl` and forwarded as
        // Accept-Language by the booking action) onto the user record. This
        // drives the language for every transactional email that follows —
        // user invite, booking confirmed, payment pending, option expiry, etc.
        // Mario rule (3.5.2026): "ako je klijent iz DE i bio je na DE stranici,
        // onda mu svi emailovi idu u tom jeziku".
        if (guestUser.language == null) {
            guestUser.language = LocaleContextHolder.getLocale().toLanguageEnum()
        }
        userRepository.save(guestUser)

        inviteService.inviteUsers(listOf(newUserEntity.id!!))

        return userRepository
            .findById(newUserEntity.id!!)
            .orElseThrow { UserDoesNotExistException() }
    }

    private fun getExtras(extraId: Long): Extra {
        return extraRepository.getReferenceById(extraId)
    }

    /**
     * Admin-created replacement reservation (original yacht cancelled — agency
     * offers a different boat). Differences from the public flow:
     *
     *   1. Customer is looked up by id, not email. No create-user, no invite.
     *   2. Offer `status` is NOT checked — admin may re-use an offer attached
     *      to a yacht whose previous reservation just got cancelled (it can
     *      momentarily sit in OPTION/RESERVATION state depending on timing).
     *      Admin is the authority here; the UI restricts which offers they
     *      can pick by searching against `/public/yachts`.
     *   3. Total price is admin-supplied, not derived from the offer.
     *   4. Payment phases are admin-supplied: explicit deadline + amount per
     *      row, with an optional `markPaid` flag that carries over the
     *      installment the customer already paid on the cancelled booking.
     *   5. No extras step (admin can add them later via a separate flow if
     *      needed — keeps this MVP tight).
     *
     * Still enforced: yacht must be EXTERNAL (so partner integration path is
     * well-defined downstream) and the agency must be active. Agency check
     * short-circuits if the yacht has no agency (custom yachts).
     */
    @Transactional
    fun createAdminReservationFlow(dto: hr.workspace.boat4you.domains.reservation.dto.AdminCreateReservationDto): Long {
        val adminPrincipal =
            SecurityContextHolder.getContext().authentication?.principal as? UserDomainEntity
        val adminUser = adminPrincipal?.let { userRepository.findByEmail(it.email) }

        val yacht = yachtRepository.findById(dto.yachtId).orElseThrow {
            IllegalArgumentException("Yacht ${dto.yachtId} not found")
        }
        val offer = offerRepository.findById(dto.offerId).orElseThrow {
            IllegalArgumentException("Offer ${dto.offerId} not found")
        }

        if (offer.yacht!!.id != yacht.id) {
            throw IllegalArgumentException("Offer ${dto.offerId} does not belong to yacht ${dto.yachtId}")
        }

        val agency = yacht.agency
        if (yacht.entryType == EntryType.EXTERNAL && (agency == null || !agency.active!!)) {
            throw AgencyNotActiveException()
        }
        if (yacht.entryType != EntryType.EXTERNAL) {
            throw IllegalArgumentException("Only externally-sourced yachts can be reserved via admin create")
        }

        val customer = userRepository.findById(dto.userId).orElseThrow {
            ReservationUserNotExists("Customer ${dto.userId} not found")
        }

        // Validate: sum of phase amounts matches the admin-typed total (±1 cent).
        val phasesSum = dto.paymentPhases.fold(java.math.BigDecimal.ZERO) { acc, p -> acc + p.amount }
        if ((phasesSum - dto.totalPrice).abs() > java.math.BigDecimal("0.01")) {
            throw IllegalArgumentException(
                "Payment phases sum ($phasesSum) does not match total price (${dto.totalPrice})",
            )
        }

        val reservationFlow = ReservationFlow()
        reservationFlow.yacht = yacht
        reservationFlow.offer = offer
        reservationFlow.user = customer
        reservationFlow.createdBy = adminUser ?: customer
        reservationFlow.createdAt = Instant.now()
        reservationFlow.status = ReservationFlowStatus.IN_PROGRESS
        // Persist the customer's contact details from the User entity so the
        // flow renders consistently with the guest/customer flow (which reads
        // these fields off the flow, not the user).
        reservationFlow.email = customer.email
        reservationFlow.name = customer.name
        reservationFlow.surname = customer.surname
        reservationFlow.phoneNumber = customer.phoneNumber
        reservationFlow.specialRequest = dto.specialRequest
        reservationFlow.calculatedTotalPrice = dto.totalPrice
        reservationFlow.agencyCommission = offer.agencyCommission

        reservationFlowRepository.save(reservationFlow)

        // Admin-supplied phases (no calculatePaymentPhases call).
        val paymentPhases = dto.paymentPhases.map { p ->
            ReservationPaymentPhase().apply {
                deadline = p.deadline
                amount = p.amount
                this.reservationFlow = reservationFlow
                if (p.markPaid) {
                    // Carry-over from the cancelled reservation's installment.
                    // No stripe session id — this wasn't paid through Stripe on
                    // THIS reservation; the reference payment lives on the
                    // prior (cancelled) reservation's payment history.
                    paidOn = Instant.now()
                }
            }
        }
        paymentPhaseRepository.saveAll(paymentPhases)
        reservationFlow.paymentPhases.addAll(paymentPhases)

        reservationFlowRepository.saveAndFlush(reservationFlow)

        return reservationFlow.id!!
    }

    /**
     * Admin-only "fictitious" reservation flow. Agency swapped the customer
     * onto a different yacht entirely in the partner system and we just need
     * to surface the new yacht in the customer's /my-bookings page. NO
     * partner API call; NO offer row required; `external_id` stays null.
     *
     * Creates ReservationFlow + Reservation + payment phases in one
     * transaction. Reservation lands as RESERVATION (confirmed) — admin is
     * the authority, customer is already aware of the swap out-of-band.
     */
    @Transactional
    fun createFictitiousReservation(dto: CreateFictitiousReservationDto): ReservationDto {
        if (!dto.dateFrom.isBefore(dto.dateTo)) {
            throw IllegalArgumentException("dateFrom must be before dateTo")
        }
        // Phases must sum to the admin total (±1 cent).
        val phasesSum = dto.paymentPhases.fold(java.math.BigDecimal.ZERO) { acc, p -> acc + p.amount }
        if ((phasesSum - dto.totalPrice).abs() > java.math.BigDecimal("0.01")) {
            throw IllegalArgumentException(
                "Payment phases sum ($phasesSum) does not match total price (${dto.totalPrice})",
            )
        }

        val adminPrincipal =
            SecurityContextHolder.getContext().authentication?.principal as? UserDomainEntity
        val adminUser = adminPrincipal?.let { userRepository.findByEmail(it.email) }

        val yacht = yachtRepository.findById(dto.yachtId).orElseThrow {
            IllegalArgumentException("Yacht ${dto.yachtId} not found")
        }
        val customer = userRepository.findById(dto.userId).orElseThrow {
            ReservationUserNotExists("Customer ${dto.userId} not found")
        }

        // Flow: minimal, no offer reference.
        val reservationFlow = ReservationFlow()
        reservationFlow.yacht = yacht
        reservationFlow.offer = null
        reservationFlow.user = customer
        reservationFlow.createdBy = adminUser ?: customer
        reservationFlow.createdAt = Instant.now()
        reservationFlow.status = ReservationFlowStatus.IN_PROGRESS
        reservationFlow.email = customer.email
        reservationFlow.name = customer.name
        reservationFlow.surname = customer.surname
        reservationFlow.phoneNumber = customer.phoneNumber
        reservationFlow.specialRequest = dto.specialRequest
        reservationFlow.calculatedTotalPrice = dto.totalPrice
        // Fictitious reservations don't carry a partner commission — admin
        // may track the broker margin in adminNotes if needed.
        reservationFlow.agencyCommission = java.math.BigDecimal.ZERO
        reservationFlowRepository.save(reservationFlow)

        // Phases — mirror AdminCreateReservation: `markPaid` carries over the
        // customer's installment from the cancelled original reservation.
        val phases = dto.paymentPhases.map { p ->
            ReservationPaymentPhase().apply {
                deadline = p.deadline
                amount = p.amount
                this.reservationFlow = reservationFlow
                if (p.markPaid) {
                    paidOn = Instant.now()
                }
            }
        }
        paymentPhaseRepository.saveAll(phases)
        reservationFlow.paymentPhases.addAll(phases)

        // Reservation — direct persist, no external_id, sys_status=RESERVATION.
        val reservationNumber = bookingNumberService.next(dto.dateFrom.year)
        val reservation = Reservation().apply {
            this.reservationFlow = reservationFlow
            this.dateFrom = dto.dateFrom.atStartOfDay()
            this.dateTo = dto.dateTo.atStartOfDay()
            this.externalId = null
            this.externalReservationCode = null
            this.externalCreatedAt = null
            this.createdAt = Instant.now()
            this.optionExpiresAt = null
            this.status = OfferStatus.RESERVED
            this.externalStatus = "FICTITIOUS"
            this.sysStatus = hr.workspace.boat4you.domains.reservation.enums.ReservationStatus.RESERVATION
            this.response = null
            this.basePrice = dto.totalPrice
            this.totalPrice = dto.totalPrice
            this.clientPrice = dto.totalPrice
            this.discount = null
            this.commission = null
            this.agencyPrice = null
            this.deposit = null
            this.currency = "EUR"
            this.paymentNote = null
            this.bankDetails = null
            this.note = null
            this.locationFrom = yacht.location
            this.locationTo = yacht.location
            this.product = null
            this.reservationNumber = reservationNumber
            this.adminNotes = dto.adminNotes?.takeIf { it.isNotBlank() }
        }
        reservationRepository.save(reservation)

        reservationFlow.status = ReservationFlowStatus.DONE
        reservationFlowRepository.saveAndFlush(reservationFlow)

        return reservationMappers.toReservationDto(reservation)
    }
}
