package hr.workspace.boat4you.domains.invoice.services

import hr.workspace.boat4you.domains.catalouge.enums.CountryIsoEnum
import hr.workspace.boat4you.common.services.ifNotNull
import hr.workspace.boat4you.common.services.initSpecification
import hr.workspace.boat4you.common.services.nonBlankOrNull
import hr.workspace.boat4you.domains.invoice.dto.CreateInvoiceDto
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
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
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

    companion object {
        /** First number of every year in the `NNNNNN/GGGG` scheme (Mario, 26.8.2026). */
        const val YEARLY_SEQUENCE_START = 100101L
    }

    @Transactional(readOnly = true)
    fun getAllForAdmin(
        reservationId: Long?,
        recipientType: InvoiceRecipientType?,
        recipientName: String?,
        search: String?,
        language: LanguageEnum?,
        departureDate: LocalDate?,
        agencyId: Long?,
        invoiceStatus: InvoiceStatus?,
        year: Int?,
        pageable: Pageable,
    ): Page<InvoiceDto> {
        val pagedInvoices = findAllWithCriteria(reservationId, recipientType, recipientName, search, language, departureDate, agencyId, invoiceStatus, year, remapSort(pageable))
        return pagedInvoices.map { invoiceMappers.toInvoiceDto(it) }
    }

    /** Distinct invoice years (by invoice date), newest first — admin year tabs. */
    @Transactional(readOnly = true)
    fun getInvoiceYears(): List<Int> = invoiceRepository.findDistinctYears()

    /**
     * Manual invoice creation (admin). Numbering: blank number → next in the
     * per-year sequence, guarded by a Postgres advisory lock so the scheduler
     * job (cusma3) and admin API (cusma2) can't mint the same number.
     */
    @Transactional(readOnly = false)
    fun createInvoice(model: CreateInvoiceDto): InvoiceDto {
        val flow =
            model.reservationId?.let { resId ->
                val view =
                    reservationViewRepository.findById(resId).getOrElse {
                        throw InvoiceNotExistException()
                    }
                reservationFlowRepository.findById(view.reservationFlowId!!).getOrElse { null }
            }

        val number =
            model.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }
                ?: run {
                    invoiceRepository.lockInvoiceNumbering()
                    nextInvoiceNumber(model.invoiceDate.year)
                }

        val entity =
            Invoice().apply {
                reservationFlow = flow
                recipientType = model.recipientType
                recipientName = model.recipientName
                recipientCity = model.recipientCity
                recipientStreet = model.recipientStreet
                recipientZipCode = model.recipientZipCode
                recipientCountry = model.recipientCountry.englishName
                recipientVatCode = model.recipientVatCode
                invoiceNumber = number
                contractNumber = model.contractNumber?.trim()?.takeIf { it.isNotEmpty() }
                invoiceDate = model.invoiceDate
                invoiceLanguage = model.invoiceLanguage
                invoiceStatus = InvoiceStatus.DRAFT
                invoiceItem = model.invoiceItem
                charterDateFrom = model.charterDateFrom
                charterDateTo = model.charterDateTo
                charterCountry = model.charterCountry?.trim()?.takeIf { it.isNotEmpty() }
                includeVat = model.includeVat
                vatPercentage = model.vatPercentage
                priceWithoutVat = model.priceWithoutVat
                vatAmount = model.vatAmount
                totalPrice = model.totalPrice
            }

        // Linked invoices default the charter period to the booking's own dates
        // when the creator didn't type them explicitly.
        if (entity.charterDateFrom == null && flow?.id != null) {
            reservationViewRepository.findByReservationFlowId(flow.id!!)?.let { v ->
                entity.charterDateFrom = v.reservationDateFrom?.toLocalDate()
                entity.charterDateTo = v.reservationDateTo?.toLocalDate()
            }
        }

        return invoiceRepository.save(entity).let { invoiceMappers.toInvoiceDto(it) }
    }

    /** Next free `NNNNNN/GGGG` for the year; caller must hold the numbering lock. */
    private fun nextInvoiceNumber(year: Int): String {
        val maxSequence = invoiceRepository.findMaxSequenceForYear(year) ?: (YEARLY_SEQUENCE_START - 1)
        return "${maxSequence + 1}/$year"
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

        // Manual invoices (reservationFlow == null) are admin-only — no public
        // user may ever match, so the null-safe chain below correctly denies.
        if (invoice.reservationFlow?.user?.id != currentUser.id) {
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

        // Auto-translate the item description when the admin switches the
        // invoice language (HR ↔ EN). Otherwise we'd freeze a HR sentence on
        // an English-language PDF. We regenerate from booking data only on
        // the language flip — manual edits the admin made under the same
        // language are preserved.
        val resolvedInvoiceItem =
            if (model.invoiceLanguage != invoice.invoiceLanguage) {
                // Manual invoices (no reservation) keep the admin's own text on
                // a language flip — there's no booking to regenerate from.
                invoice.reservationFlow?.id
                    ?.let { reservationViewRepository.findByReservationFlowId(it) }
                    ?.let { buildInvoiceItem(it, model.invoiceLanguage) }
                    ?: model.invoiceItem
            } else {
                model.invoiceItem
            }

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
                model.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { invoiceNumber = it }
                // Absent field keeps the stored number (stale admin bundles /
                // partial PUTs must not wipe it); an explicit blank clears it.
                model.contractNumber?.let { contractNumber = it.trim().takeIf { s -> s.isNotEmpty() } }
                model.charterDateFrom?.let { charterDateFrom = it }
                model.charterDateTo?.let { charterDateTo = it }
                model.charterCountry?.let { charterCountry = it.trim().takeIf { c -> c.isNotEmpty() } }
                invoiceItem = resolvedInvoiceItem
                includeVat = model.includeVat
                vatPercentage = model.vatPercentage
                priceWithoutVat = model.priceWithoutVat
                vatAmount = model.vatAmount
                totalPrice = model.totalPrice
            }

        return invoiceRepository.save(entity).let { invoiceMappers.toInvoiceDto(it) }
    }

    private fun buildInvoiceItem(
        view: ReservationView,
        language: InvoiceLanguageEnum,
    ): String {
        // Long-form date in English month-name style ("25 Apr 2026"). Used
        // INSIDE the item-description sentence so the line reads naturally
        // in either language. The verbose `DATE_FORMATTER` ("25.04.2026.")
        // is kept for `invoiceDate` rendering in the PDF header — that
        // stays HR-style there because it's a date stamp, not prose.
        val itemDateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
        val dateFrom = view.reservationDateFrom?.format(itemDateFormat).orEmpty()
        val dateTo = view.reservationDateTo?.format(itemDateFormat).orEmpty()
        // Use the booking's actual client (reservation.created_for, which is
        // also what the Bookings table renders in its CLIENT column) — NOT
        // `reservation_flow_name/surname`. The flow record carries whoever
        // *placed* the booking (often the agent themselves), while
        // created_for is the end customer the booking is FOR. Falls back to
        // flow name/surname when created_for is empty (rare; e.g. manual
        // bookings entered before the createdFor field was wired up).
        val clientName = listOfNotNull(
            view.createdForName?.takeIf { it.isNotBlank() } ?: view.reservationFlowName,
            view.createdForSurname?.takeIf { it.isNotBlank() } ?: view.reservationFlowSurname,
        ).joinToString(" ")
        val yachtLine = listOfNotNull(view.modelName, view.yachtName)
            .joinToString(" - ")
        val locationFrom = view.locationFromName.orEmpty()
        val reservationNumber = view.reservationNumber.orEmpty()

        return when (language) {
            InvoiceLanguageEnum.HR ->
                "Agencijska provizija po ugovoru $reservationNumber za klijenta $clientName " +
                    "najam plovila $yachtLine iz $locationFrom " +
                    "u periodu od $dateFrom do $dateTo"

            InvoiceLanguageEnum.EN ->
                "Agency commission for booking $reservationNumber for client $clientName, " +
                    "charter of $yachtLine from $locationFrom " +
                    "for the period $dateFrom to $dateTo"
        }
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
        if (reservationsWithoutInvoices.isEmpty()) return 0
        val reservationFlowsMap = reservationFlowRepository.findByIdIn(reservationsWithoutInvoices.map { it.reservationFlowId!! }.toSet()).associateBy { it.id!! }

        val invoiceEntities = reservationsWithoutInvoices.map { it.toInvoiceEntity(reservationFlowsMap) }

        // Yearly `NNNNNN/GGGG` numbering (start 100101 each year). One advisory
        // lock for the whole batch; per-year counters continue from the DB max
        // so job-generated and manually created invoices share one sequence.
        invoiceRepository.lockInvoiceNumbering()
        val yearCounters = mutableMapOf<Int, Long>()
        invoiceEntities.forEach {
            val year = it.invoiceDate.year
            val next = yearCounters.getOrPut(year) { invoiceRepository.findMaxSequenceForYear(year) ?: (YEARLY_SEQUENCE_START - 1) } + 1
            yearCounters[year] = next
            it.invoiceNumber = "$next/$year"
        }

        val result = invoiceRepository.saveAll(invoiceEntities)
        return result.size
    }

    private fun ReservationView.toInvoiceEntity(reservationFlowsMap: Map<Long, ReservationFlow>): Invoice {
        val view = this
        val reservationFlow = reservationFlowsMap[view.reservationFlowId!!]!!
        val isAgencyInCroatia = view.agencyCountry?.lowercase() in listOf("croatia", "hr", "hrv", "hrvatska")
        val language = if (isAgencyInCroatia) InvoiceLanguageEnum.HR else InvoiceLanguageEnum.EN
        val invoiceItem = buildInvoiceItem(view, language)

        // Source of truth = `reservation.commission` (mirrored in
        // `reservation_view.reservation_commission`, which is also what the
        // Bookings listing's COMMISSION column shows). `reservation_flow.
        // agency_commission` is a separate, often-zero field tracking what
        // we owe the agency — using it produced invoices with €0.00.
        // Fall back to `reservationFlow.agencyCommission` for backwards
        // safety when the view hasn't materialized the figure (extremely
        // rare; would mean the reservation row exists with no commission
        // populated).
        val agencyCommission =
            (view.reservationCommission ?: reservationFlow.agencyCommission ?: BigDecimal.ZERO)
                .roundDecimals()
        // Use BigDecimal throughout — Float mixing (vatPercentage / (vat+100))
        // loses precision on totals like 1234.56 × 0.2 and Stripe charges a
        // fraction-of-a-cent mismatch vs what the invoice printed. Keep the
        // decimal field as Float (it's what the entity stores) but compute
        // the amount with exact arithmetic.
        val vatPercentage: Float = if (isAgencyInCroatia) 25.0f else 0.0f
        val vatRate = BigDecimal(vatPercentage.toString())
        val hundredPlusRate = BigDecimal("100").add(vatRate)
        val vatAmountIfApplicable =
            if (hundredPlusRate.signum() == 0) {
                BigDecimal.ZERO
            } else {
                vatRate
                    .divide(hundredPlusRate, 10, java.math.RoundingMode.HALF_UP)
                    .multiply(agencyCommission)
                    .roundDecimals()
            }
        val priceWithoutVat = (agencyCommission - vatAmountIfApplicable).roundDecimals()

        return Invoice().apply {
            this.reservationFlow = reservationFlow
            // Auto invoices file under the reservation number — that IS the
            // contract number for bookings made through the platform.
            contractNumber = view.reservationNumber
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
            charterDateFrom = view.reservationDateFrom!!.toLocalDate()
            charterDateTo = view.reservationDateTo?.toLocalDate()
            charterCountry =
                view.locationFromCountry?.let { code ->
                    runCatching { CountryIsoEnum.valueOf(code).englishName }.getOrNull() ?: code
                }
            invoiceLanguage = language
            includeVat = isAgencyInCroatia
            this.vatPercentage = vatPercentage
            this.priceWithoutVat = priceWithoutVat
            vatAmount = if (isAgencyInCroatia) vatAmountIfApplicable else BigDecimal.ZERO
            totalPrice = agencyCommission
        }
    }

    /**
     * The Booking column sorts NUMERICALLY: contract numbers mix 6- and
     * 7-digit forms (100198/2026 vs 1001089/2026 — straight off Mario's paper
     * contracts), so the raw string order is wrong. The entity exposes a
     * zero-padded @Formula sort key; swap it in transparently whenever the
     * client asks to sort by contractNumber.
     */
    private fun remapSort(pageable: Pageable): Pageable {
        if (pageable.sort.none { it.property == Invoice::contractNumber.name }) return pageable
        val mapped =
            Sort.by(
                pageable.sort.map { order ->
                    if (order.property == Invoice::contractNumber.name) order.withProperty(Invoice::contractNumberSort.name) else order
                }.toList(),
            )
        return PageRequest.of(pageable.pageNumber, pageable.pageSize, mapped)
    }

    private fun findAllWithCriteria(
        reservationId: Long?,
        recipientType: InvoiceRecipientType?,
        recipientName: String?,
        search: String?,
        language: LanguageEnum?,
        departureDate: LocalDate?,
        agencyId: Long?,
        invoiceStatus: InvoiceStatus?,
        year: Int?,
        pageable: Pageable,
    ): Page<Invoice> =
        invoiceRepository.findAll(
            initSpecification(reservationIdCriteria(reservationId))
                .and(recipientTypeCriteria(recipientType))
                .and(recipientNameCriteria(recipientName))
                .and(searchCriteria(search))
                .and(languageCriteria(language))
                .and(departureDateCriteria(departureDate))
                .and(agencyIdCriteria(agencyId))
                .and(invoiceStatusCriteria(invoiceStatus))
                .and(yearCriteria(year)),
            pageable,
        )

    private fun yearCriteria(year: Int?): Specification<Invoice>? =
        year?.let {
            Specification { root, _, cb ->
                cb.between(root.get(Invoice::invoiceDate.name), LocalDate.of(it, 1, 1), LocalDate.of(it, 12, 31))
            }
        }

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

    /**
     * One search box for the admin listing: matches the recipient (agency)
     * name, Mario's contract number, or the invoice number — case-insensitive
     * substring on any of the three.
     */
    private fun searchCriteria(search: String?): Specification<Invoice>? =
        search.nonBlankOrNull()?.let {
            val pattern = "%${it.uppercase(Locale.getDefault())}%"
            Specification { root, _, cb ->
                cb.or(
                    cb.like(cb.upper(root.get(Invoice::recipientName.name)), pattern),
                    cb.like(cb.upper(root.get(Invoice::contractNumber.name)), pattern),
                    cb.like(cb.upper(root.get(Invoice::invoiceNumber.name)), pattern),
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
                // Direct charter_date_from match covers standalone invoices;
                // the reservation-view EXISTS keeps legacy linked rows that
                // predate the charter_date columns.
                cb.or(cb.equal(root.get<LocalDate>(Invoice::charterDateFrom.name), departureDate), cb.exists(subquery))
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
