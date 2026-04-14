package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.models.UserDomainEntity
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.Extra
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.PriceCalculationService
import hr.workspace.boat4you.domains.reservation.dto.CreateReservationDto
import hr.workspace.boat4you.domains.reservation.dto.UserExtReservationDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationFlowStatus
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationNotExistException
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationUserNotExists
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtraRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import hr.workspace.boat4you.domains.users.exceptions.UserDoesNotExistException
import hr.workspace.boat4you.domains.users.jpa.UserEntity
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.jvm.optionals.getOrElse

@Service
class ReservationFlowMutationService(
    private val userRepository: UserRepository,
    private val yachtRepository: YachtRepository,
    private val offerRepository: OfferRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    private val userMutationService: UserMutationService,
    private val extraRepository: ExtraRepository,
    private val reservationExtraRepository: ReservationExtraRepository,
    private val priceCalculationService: PriceCalculationService,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
    private val paymentPhasesService: ReservationPaymentPhasesService,
    private val inviteService: UserInviteService,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional
    fun createReservationFlow(createReservationDto: CreateReservationDto): Long {
        val createdByUserDomainEntity = SecurityContextHolder.getContext().authentication.principal as UserDomainEntity
        val createdByUser = userRepository.findByEmail(createdByUserDomainEntity.email)!!

        val yacht = yachtRepository.findById(createReservationDto.yachtId).get()
        val offer = offerRepository.findById(createReservationDto.offerId).get()

        if (yacht.isInquireOnly()) {
            throw IllegalArgumentException("Yacht is inquire only, reservation cannot be created")
        }

        if (offer.status != OfferStatus.FREE) {
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
                loggedUser = createdByUserDomainEntity,
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
        reservationFlow.createdBy = createdByUser
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

        val paymentPhases =
            paymentPhasesService
                .calculatePaymentPhases(
                    reservationStartDate = offer.dateFrom!!,
                    totalPrice = reservationFlow.calculatedTotalPrice!!,
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
        loggedUser: UserDomainEntity,
        createdByUser: UserEntity,
        createReservationDto: CreateReservationDto,
    ): UserEntity? {
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
        inviteService.inviteUsers(listOf(newUserEntity.id!!))

        return userRepository
            .findById(newUserEntity.id!!)
            .orElseThrow { UserDoesNotExistException() }
    }

    @Transactional
    fun createReservationFlowFromExternalReservation(
        request: UserExtReservationDto,
        externalReservation: ReservationResponseWrapper,
    ): Long {
        val createdByUserDomainEntity = SecurityContextHolder.getContext().authentication.principal as UserDomainEntity
        val createdByUser = userRepository.findByEmail(createdByUserDomainEntity.email)!!

        val yacht =
            yachtRepository.findByExternalIdAndExternalSystemId(
                externalReservation.yachtId,
                request.externalSystem.value.toLong(),
            ) ?: throw YachtDoesNotExistException()
        val offers =
            offerRepository.findAllByYachtAndDateFromAndDateTo(
                yacht,
                externalReservation.dateFrom.toLocalDate(),
                externalReservation.dateTo.toLocalDate(),
            )
        val offer =
            if (offers.size == 1) {
                offers[0]
            } else {
                // Try to find by product or take first if we cannot find
                offers.firstOrNull { it.product == externalReservation.product } ?: offers[0]
            }

        val reservationUser = userRepository.findByEmail(request.email) ?: throw UserDoesNotExistException()

        val reservationFlow = ReservationFlow()
        reservationFlow.yacht = yacht
        reservationFlow.offer = offer
        reservationFlow.user = reservationUser
        reservationFlow.createdBy = createdByUser
        reservationFlow.createdAt = Instant.now()
        reservationFlow.status = ReservationFlowStatus.IN_PROGRESS
        reservationFlow.email = reservationUser.email
        reservationFlow.name = reservationUser.name
        reservationFlow.surname = reservationUser.surname
        reservationFlow.phoneNumber = reservationUser.phoneNumber
        reservationFlow.specialRequest = null
        reservationFlow.calculatedTotalPrice = externalReservation.totalPrice
        reservationFlow.agencyCommission = BigDecimal.ZERO
        if (request.previousReservationFlowId != null) {
            reservationFlow.previousFlow =
                setPaymentPhasesAndLinkPreviousFlow(
                    reservationFlow,
                    request.previousReservationFlowId,
                    offer.dateFrom!!,
                )
            reservationFlow.previousFlow = reservationFlowRepository.getReferenceById(request.previousReservationFlowId)
        }

        reservationFlowRepository.saveAndFlush(reservationFlow)

        return reservationFlow.id!!
    }

    private fun setPaymentPhasesAndLinkPreviousFlow(
        reservationFlow: ReservationFlow,
        previousReservationFlowId: Long,
        reservationDateFrom: LocalDate,
    ): ReservationFlow {
        val previousFlow =
            reservationFlowRepository
                .findById(previousReservationFlowId)
                .getOrElse { throw ReservationNotExistException() }
        val reservationFlowIdsInChain =
            reservationFlowRepository.findIdsInReservationFlowChain(previousReservationFlowId)
        val totalPaidByNow = paymentPhaseRepository.calculateTotalPaid(reservationFlowIdsInChain).toDouble()

        val tempPaymentPhases =
            paymentPhasesService
                .calculatePaymentPhases(
                    reservationStartDate = reservationDateFrom,
                    totalPrice = reservationFlow.calculatedTotalPrice!!,
                ).map {
                    ReservationPaymentPhase().apply {
                        deadline = it.first
                        amount = it.second.toBigDecimal()
                        this.reservationFlow = reservationFlow
                    }
                }.toMutableList()

        val finalPaymentPhases = offsetPaidAmountsFromFront(tempPaymentPhases, totalPaidByNow)

        paymentPhaseRepository.saveAll(finalPaymentPhases)
        reservationFlow.paymentPhases.addAll(finalPaymentPhases)

        return previousFlow
    }

    fun offsetPaidAmountsFromFront(
        tempPaymentPhases: List<ReservationPaymentPhase>,
        totalPaidByNow: Double,
    ): List<ReservationPaymentPhase> {
        if (totalPaidByNow == 0.0) return tempPaymentPhases
        if (tempPaymentPhases.isEmpty()) return tempPaymentPhases

        var remaining = totalPaidByNow
        var index = 0

        while (index < tempPaymentPhases.size && remaining >= tempPaymentPhases[index].amount.toDouble()) {
            remaining -= tempPaymentPhases[index].amount.toDouble()
            index++
        }

        if (index == tempPaymentPhases.size) {
            if (remaining > 0) {
                // TODO Send an email, can this happen?
                log.error("More money paid by Customer than owed ($remaining EUR) for reservation flow id:${tempPaymentPhases.first().reservationFlow.id}")
            }
            return emptyList()
        }

        val result = ArrayList<ReservationPaymentPhase>(tempPaymentPhases.size - index)
        if (remaining > 0) {
            result.add(tempPaymentPhases[index].apply { this.amount -= remaining.toBigDecimal() })
            index++
        }

        while (index < tempPaymentPhases.size) {
            result.add(tempPaymentPhases[index])
            index++
        }

        result.forEach { paymentPhase ->
            paymentPhase.amount = paymentPhase.amount.setScale(2, RoundingMode.UP)
        }

        return result
    }

    private fun getExtras(extraId: Long): Extra {
        return extraRepository.getReferenceById(extraId)
    }
}
