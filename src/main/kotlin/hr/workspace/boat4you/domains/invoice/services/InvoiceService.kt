package hr.workspace.boat4you.domains.invoice.services

import hr.workspace.boat4you.common.services.ifNotNull
import hr.workspace.boat4you.common.services.initSpecification
import hr.workspace.boat4you.common.services.nonBlankOrNull
import hr.workspace.boat4you.domains.invoice.dto.InvoiceDto
import hr.workspace.boat4you.domains.invoice.dto.UpdateInvoiceDto
import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import hr.workspace.boat4you.domains.invoice.enums.InvoiceStatus
import hr.workspace.boat4you.domains.invoice.exceptions.InvoiceNotExistException
import hr.workspace.boat4you.domains.invoice.jpa.Invoice
import hr.workspace.boat4you.domains.invoice.jpa.InvoiceRepository
import hr.workspace.boat4you.domains.invoice.mapper.InvoiceMappers
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationView
import hr.workspace.boat4you.domains.reservation.jpa.ReservationViewRepository
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import jakarta.persistence.criteria.JoinType
import org.openapitools.model.LanguageEnum
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.jvm.optionals.getOrElse

@Service
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val reservationViewRepository: ReservationViewRepository,
    private val invoiceMappers: InvoiceMappers,
    private val userRepository: UserRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
) {
    val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy.")

    @Transactional(readOnly = true)
    fun getAllForAdmin(
        reservationId: Long?,
        recipientType: InvoiceRecipientType?,
        recipientName: String?,
        language: LanguageEnum?,
        departureDate: LocalDate?,
        agencyId: Long?,
        invoiceStatus: InvoiceStatus?,
        pageable: Pageable,
    ): Page<InvoiceDto> {
        val pagedInvoices = findAllWithCriteria(reservationId, recipientType, recipientName, language, departureDate, agencyId, invoiceStatus, pageable)
        return pagedInvoices.map { invoiceMappers.toInvoiceDto(it) }
    }

    @Transactional(readOnly = true)
    fun getByIdForAdmin(invoiceId: Long): InvoiceDto? {
        return invoiceRepository.findById(invoiceId).ifNotNull { invoiceMappers.toInvoiceDto(it) }
    }

    @Transactional(readOnly = true)
    fun getByIdForOtherUsers(invoiceId: Long): InvoiceDto? {
        val currentUser =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        if (currentUser == null) {
            throw AccessDeniedException("User is not authenticated")
        }

        val invoice = invoiceRepository.findById(invoiceId).getOrElse { throw InvoiceNotExistException() }

        if (invoice.reservationFlow.user!!.id != currentUser.id) {
            throw AccessDeniedException("User is not authenticated")
        }

        return invoiceMappers.toInvoiceDto(invoice)
    }

    @Transactional(readOnly = true)
    fun getByReservationIdForOtherUsers(reservationId: Long): InvoiceDto? {
        val currentUser =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }
        if (currentUser == null) {
            throw AccessDeniedException("User is not authenticated")
        }

        val invoices = invoiceRepository.findByReservationIdAndUserId(reservationId, currentUser.id!!)
        return if (invoices.isEmpty()) {
            null
        } else {
            invoiceMappers.toInvoiceDto(invoices.first())
        }
    }

    @Transactional(readOnly = false)
    fun updateInvoice(
        id: Long,
        model: UpdateInvoiceDto,
    ): InvoiceDto? {
        val invoice = invoiceRepository.findById(id).getOrElse { throw InvoiceNotExistException() }

        val entity =
            invoice.apply {
                recipientType = model.recipientType
                recipientName = model.recipientName
                recipientCity = model.recipientCity
                recipientStreet = model.recipientStreet
                recipientZipCode = model.recipientZipCode
                recipientCountry = model.recipientCountry.englishName
                recipientVatCode = model.recipientVatCode
                invoiceLanguage = model.invoiceLanguage
                invoiceStatus = model.invoiceStatus ?: this.invoiceStatus
                invoiceItem = model.invoiceItem
                includeVat = model.includeVat
                vatPercentage = model.vatPercentage
                priceWithoutVat = model.priceWithoutVat
                vatAmount = model.vatAmount
                totalPrice = model.totalPrice
            }

        return invoiceRepository.save(entity).let { invoiceMappers.toInvoiceDto(it) }
    }

    @Transactional(readOnly = false)
    fun markInvoiceAsSent(id: Long): InvoiceDto? {
        val invoice = invoiceRepository.findById(id).getOrElse { throw InvoiceNotExistException() }

        invoice.invoiceStatus = InvoiceStatus.SENT

        return invoiceRepository.save(invoice).let { invoiceMappers.toInvoiceDto(it) }
    }

    @Transactional(readOnly = false)
    fun deleteInvoice(id: Long) {
        val invoice = invoiceRepository.findById(id).getOrElse { throw InvoiceNotExistException() }
        invoiceRepository.delete(invoice)
    }

    @Transactional(readOnly = false)
    fun generateInvoicesFromJob(): Int {
        val reservationsWithoutInvoices = invoiceRepository.findReservationsWithoutInvoices(LocalDate.now().atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay())
        val lastInvoiceNumber = invoiceRepository.findLastInvoiceNumber(LocalDate.now())?.toLong() ?: 1
        var nextInvoiceNumber = lastInvoiceNumber + 1
        val reservationFlowsMap = reservationFlowRepository.findByIdIn(reservationsWithoutInvoices.map { it.reservationFlowId!! }.toSet()).associateBy { it.id!! }

        val invoiceEntities = reservationsWithoutInvoices.map { it.toInvoiceEntity(reservationFlowsMap) }

        invoiceEntities.forEach {
            it.invoiceNumber = nextInvoiceNumber.toString()
            nextInvoiceNumber++
        }

        val result = invoiceRepository.saveAll(invoiceEntities)
        return result.size
    }

    private fun ReservationView.toInvoiceEntity(reservationFlowsMap: Map<Long, ReservationFlow>): Invoice {
        val view = this
        val reservationFlow = reservationFlowsMap[view.reservationFlowId!!]!!
        val isAgencyInCroatia = view.agencyCountry?.lowercase() in listOf("croatia", "hr", "hrv", "hrvatska")
        val invoiceItem =
            if (isAgencyInCroatia) {
                "Najam plovila ${view.yachtName} za datum ${view.reservationDateFrom?.format(DATE_FORMATTER)} - ${view.reservationDateTo?.format(DATE_FORMATTER)}"
            } else {
                "${view.yachtName} charter for period ${view.reservationDateFrom?.format(DATE_FORMATTER)} - ${view.reservationDateTo?.format(DATE_FORMATTER)}"
            }

        val agencyCommission = reservationFlow.agencyCommission!!.roundDecimals()
        val vatPercentage = if (isAgencyInCroatia) 25.0f else 0.0f
        val vatAmountIfApplicable = ((vatPercentage) / (vatPercentage + 100) * (agencyCommission.toFloat())).roundDecimals()
        val priceWithoutVat = (agencyCommission - vatAmountIfApplicable).roundDecimals()

        return Invoice().apply {
            this.reservationFlow = reservationFlow
            // We currently have no way of knowing the intended recipient type when generating an invoice. Defaulting to COMPANY
            recipientType = InvoiceRecipientType.COMPANY
            recipientName = view.agencyName ?: ""
            recipientCity = view.agencyCity ?: ""
            recipientStreet = view.agencyAddress ?: ""
            recipientZipCode = view.agencyZip ?: ""
            this.recipientCountry = view.agencyCountry ?: "Croatia"
            recipientVatCode = view.agencyVatCode ?: ""
            this.invoiceItem = invoiceItem
            invoiceDate = view.reservationDateFrom!!.toLocalDate()
            invoiceLanguage = if (isAgencyInCroatia) InvoiceLanguageEnum.HR else InvoiceLanguageEnum.EN
            includeVat = isAgencyInCroatia
            this.vatPercentage = vatPercentage
            this.priceWithoutVat = priceWithoutVat
            vatAmount = if (isAgencyInCroatia) vatAmountIfApplicable else BigDecimal.ZERO
            totalPrice = agencyCommission
        }
    }

    private fun findAllWithCriteria(
        reservationId: Long?,
        recipientType: InvoiceRecipientType?,
        recipientName: String?,
        language: LanguageEnum?,
        departureDate: LocalDate?,
        agencyId: Long?,
        invoiceStatus: InvoiceStatus?,
        pageable: Pageable,
    ): Page<Invoice> =
        invoiceRepository.findAll(
            initSpecification(reservationIdCriteria(reservationId))
                .and(recipientTypeCriteria(recipientType))
                .and(recipientNameCriteria(recipientName))
                .and(languageCriteria(language))
                .and(departureDateCriteria(departureDate))
                .and(agencyIdCriteria(agencyId))
                .and(invoiceStatusCriteria(invoiceStatus)),
            pageable,
        )

    private fun reservationIdCriteria(reservationId: Long?): Specification<Invoice>? =
        reservationId?.let {
            val reservation = reservationViewRepository.findById(it).getOrElse { null }
            if (reservation == null) {
                return null
            }
            val reservationFlowId = reservation.reservationFlowId!!

            Specification { root, _, cb ->
                val joinReservationFlow = root.join<Invoice, ReservationFlow>(Invoice::reservationFlow.name)
                cb.and(
                    cb.equal(joinReservationFlow.get<Long>(ReservationFlow::id.name), reservationFlowId),
                )
            }
        }

    private fun recipientTypeCriteria(recipientType: InvoiceRecipientType?): Specification<Invoice>? =
        recipientType?.let {
            Specification { root, _, cb ->
                cb.and(cb.equal(root.get<InvoiceRecipientType>(Invoice::recipientType.name), recipientType))
            }
        }

    private fun recipientNameCriteria(recipientName: String?): Specification<Invoice>? =
        recipientName.nonBlankOrNull()?.let {
            Specification { root, _, cb ->
                cb.and(
                    cb.like(cb.upper(root.get(Invoice::recipientName.name)), "%${it.uppercase(Locale.getDefault())}%"),
                )
            }
        }

    private fun languageCriteria(language: LanguageEnum?): Specification<Invoice>? =
        language?.let {
            Specification { root, _, cb ->
                cb.and(cb.equal(root.get<InvoiceLanguageEnum>(Invoice::invoiceLanguage.name), language))
            }
        }

    private fun departureDateCriteria(departureDate: LocalDate?): Specification<Invoice>? =
        departureDate?.let {
            Specification { root, query, cb ->
                // EXISTS (select 1 from reservation_view v
                //         where v.reservationFlowId = invoice.reservationFlow.id
                //           and v.reservationDateFrom equals departureDate)
                val subquery = query!!.subquery(Int::class.java)
                val view = subquery.from(ReservationView::class.java)

                // Correlate Invoice and ReservationFlow
                val correlatedInvoice = subquery.correlate(root)
                val resJoin = correlatedInvoice.join<Invoice, ReservationFlow>(Invoice::reservationFlow.name, JoinType.INNER)

                subquery
                    .select(cb.literal(1))
                    .where(
                        cb.equal(view.get<Long>(ReservationView::reservationFlowId.name), resJoin.get<Long>(ReservationFlow::id.name)),
                        cb.equal(view.get<LocalDate>(ReservationView::reservationDateFrom.name), departureDate),
                    )

                query.distinct(true)
                cb.exists(subquery)
            }
        }

    private fun agencyIdCriteria(agencyId: Long?): Specification<Invoice>? =
        agencyId?.let {
            Specification { root, query, cb ->
                // EXISTS (select 1 from reservation_view v
                //         where v.reservationFlowId = invoice.reservationFlow.id
                //           and v.reservationDateFrom equals departureDate)
                val subquery = query!!.subquery(Int::class.java)
                val view = subquery.from(ReservationView::class.java)

                // Correlate Invoice and ReservationFlow
                val correlatedInvoice = subquery.correlate(root)
                val resJoin = correlatedInvoice.join<Invoice, ReservationFlow>(Invoice::reservationFlow.name, JoinType.INNER)

                subquery
                    .select(cb.literal(1))
                    .where(
                        cb.equal(view.get<Long>(ReservationView::reservationFlowId.name), resJoin.get<Long>(ReservationFlow::id.name)),
                        cb.equal(view.get<Long>(ReservationView::agencyId.name), agencyId),
                    )

                query.distinct(true)
                cb.exists(subquery)
            }
        }

    private fun invoiceStatusCriteria(invoiceStatus: InvoiceStatus?): Specification<Invoice>? =
        invoiceStatus?.let {
            Specification { root, _, cb ->
                cb.and(cb.equal(root.get<InvoiceStatus>(Invoice::invoiceStatus.name), invoiceStatus))
            }
        }

    private fun Float.roundDecimals(): BigDecimal = this.toBigDecimal().setScale(2, RoundingMode.UP)

    private fun BigDecimal.roundDecimals(): BigDecimal = this.setScale(2, RoundingMode.UP)
}
