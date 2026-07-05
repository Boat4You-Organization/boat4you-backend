package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.utils.SlugUtils
import hr.workspace.boat4you.domains.reservation.dto.TripDto
import hr.workspace.boat4you.domains.reservation.dto.TripMarinaDto
import hr.workspace.boat4you.domains.reservation.dto.TripYachtDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationDocumentType
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Read side of the Boat4You Trip hub (/trip/{token} PWA). Transactional so the
 * lazy yacht-image / model / agency graphs resolve here, not in the controller.
 */
@Service
@Transactional(readOnly = true)
class TripQueryService(
    private val reservationRepository: ReservationRepository,
    private val reservationDocumentService: ReservationDocumentService,
) {
    /** Documents the CREW may see. Contracts / untyped uploads stay owner-only. */
    private val travelDocumentTypes = setOf(
        ReservationDocumentType.CREW_LIST,
        ReservationDocumentType.BOARDING_PASS,
        ReservationDocumentType.PREFERENCE_LIST,
    )

    fun getTrip(token: String): TripDto? {
        val reservation = reservationRepository.findByTripToken(token) ?: return null
        val flow = reservation.reservationFlow ?: return null
        val yacht = flow.yacht ?: return null

        val documents = reservationDocumentService
            .listCustomerVisible(reservation.id!!)
            .filter { it.documentType in travelDocumentTypes }

        // Main image first, then partner ordering — max 8 keeps payload small.
        val imageIds = yacht.yachtImages
            .sortedWith(
                compareByDescending<hr.workspace.boat4you.domains.catalouge.jpa.YachtImage> { it.mainImage == true }
                    .thenBy { it.position ?: Short.MAX_VALUE },
            )
            .mapNotNull { it.id }
            .take(8)

        val marina = reservation.locationFrom?.let {
            TripMarinaDto(
                name = it.name ?: "",
                countryCode = it.countryCode,
                lat = it.lat?.toDouble(),
                lon = it.lon?.toDouble(),
            )
        }

        return TripDto(
            reservationNumber = reservation.reservationNumber ?: "#${reservation.id}",
            status = (reservation.sysStatus ?: ReservationStatus.OPTION).name,
            dateFrom = reservation.dateFrom!!,
            dateTo = reservation.dateTo!!,
            ownerFirstName = flow.name?.takeIf { it.isNotBlank() },
            yacht = TripYachtDto(
                name = yacht.name ?: "",
                // Model names usually already carry the manufacturer prefix
                // ("Lagoon 450 F") — don't render "Lagoon Lagoon 450 F".
                fullLabel = run {
                    val manufacturer = yacht.model?.manufacturer?.name?.takeIf { it.isNotBlank() }
                    val model = yacht.model?.name?.takeIf { it.isNotBlank() }
                    val prefix = if (manufacturer != null && model?.startsWith(manufacturer, ignoreCase = true) != true) manufacturer else null
                    listOfNotNull(prefix, model, yacht.name?.takeIf { it.isNotBlank() })
                        .joinToString(" ")
                        .ifBlank { yacht.name ?: "" }
                },
                slug = SlugUtils.toSlugWithId(
                    yacht.model?.manufacturer?.name,
                    yacht.model?.name,
                    yacht.name,
                    yacht.id!!,
                ),
                buildYear = yacht.buildYear?.toInt(),
                cabins = yacht.cabins?.toInt(),
                berths = yacht.berths?.toInt(),
                wc = yacht.wc?.toInt(),
                lengthMeters = yacht.length?.toDouble(),
                mainImageId = yacht.mainImageId,
                imageIds = imageIds,
            ),
            marina = marina,
            crewListUrl = reservation.crewListUrl?.takeIf { it.isNotBlank() },
            agencyPhone = yacht.agency?.mobile?.takeIf { it.isNotBlank() }
                ?: yacht.agency?.phone?.takeIf { it.isNotBlank() },
            documents = documents,
        )
    }

    /** Travel-document download scoped by trip token: the document must belong
     *  to THIS reservation, be customer-visible and of a travel type. */
    fun getTravelDocument(token: String, documentId: Long): ReservationDocumentDownload? {
        val reservation = reservationRepository.findByTripToken(token) ?: return null
        val doc = try {
            reservationDocumentService.download(documentId)
        } catch (e: NoSuchElementException) {
            return null
        }
        if (doc.reservationId != reservation.id) return null
        if (doc.isInternal) return null
        if (doc.documentType !in travelDocumentTypes) return null
        return doc
    }
}
