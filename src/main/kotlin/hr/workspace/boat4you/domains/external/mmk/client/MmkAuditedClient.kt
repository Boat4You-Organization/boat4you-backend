package hr.workspace.boat4you.domains.external.mmk.client

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ServiceCallAuditService
import org.openapitools.client.mmk.api.BookingApi.InventoryGetYachts
import org.openapitools.client.mmk.model.AvailabilityResponse
import org.openapitools.client.mmk.model.Base
import org.openapitools.client.mmk.model.Company
import org.openapitools.client.mmk.model.Country
import org.openapitools.client.mmk.model.Equipment
import org.openapitools.client.mmk.model.KindEnum
import org.openapitools.client.mmk.model.SailingArea
import org.openapitools.client.mmk.model.Shipyard
import org.openapitools.client.mmk.model.SupportedLanguagesEnum
import org.openapitools.client.mmk.model.Yacht
import org.springframework.stereotype.Component

@Component
class MmkAuditedClient(
    private val mmkClient: MmkClient,
    private val serviceCallAuditService: ServiceCallAuditService,
) {
    fun getAvailability(
        year: Int,
        companyId: Long? = null,
    ): List<AvailabilityResponse> {
        val mmkResponse =
            runCatching {
                mmkClient.bookingApi.getAvailability(
                    year = year,
                    companyId = companyId,
                )
            }

        serviceCallAuditService.serviceCallAudit(
            "getAvailability",
            mmkResponse,
            null,
            ExternalSystemEnum.MMK,
            "year=$year, companyId=$companyId",
        )

        return mmkResponse.getOrThrow()
    }

    fun getYachts(
        language: SupportedLanguagesEnum? = null,
        companyId: Long? = null,
        currency: String? = null,
        inventory: InventoryGetYachts? = null,
        kind: List<KindEnum>? = null,
    ): List<Yacht> {
        val mmkResponse =
            runCatching {
                mmkClient.bookingApi.getYachts(
                    companyId = companyId,
                    language = language,
                    currency = currency,
                    inventory = inventory,
                    kind = kind,
                )
            }

        serviceCallAuditService.serviceCallAudit(
            "getYachts",
            mmkResponse,
            null,
            ExternalSystemEnum.MMK,
            "language=$language, companyId=$companyId, currency=$currency, inventory=$inventory, kind=$kind",
        )

        return mmkResponse.getOrThrow()
    }

    fun getCompanies(): List<Company> {
        val agencies =
            runCatching {
                mmkClient.bookingApi.getCompanies()
            }
        serviceCallAuditService.serviceCallAudit("getCompanies", agencies, null, ExternalSystemEnum.MMK)
        return agencies.getOrThrow()
    }

    fun getCountries(): List<Country> {
        val countries =
            runCatching {
                mmkClient.bookingApi.getCountries()
            }
        serviceCallAuditService.serviceCallAudit("getCountries", countries, null, ExternalSystemEnum.MMK)
        return countries.getOrThrow()
    }

    fun getSailingAreas(): List<SailingArea> {
        val sailingAreas =
            runCatching {
                mmkClient.bookingApi.getSailingAreas()
            }
        serviceCallAuditService.serviceCallAudit("getSailingAreas", sailingAreas, null, ExternalSystemEnum.MMK)
        return sailingAreas.getOrThrow()
    }

    fun getBases(): List<Base> {
        val locations =
            runCatching {
                mmkClient.bookingApi.getBases()
            }
        serviceCallAuditService.serviceCallAudit("getBases", locations, null, ExternalSystemEnum.MMK)
        return locations.getOrThrow()
    }

    fun getShipyards(): List<Shipyard> {
        val shipyards =
            runCatching {
                mmkClient.bookingApi.getShipyards()
            }
        serviceCallAuditService.serviceCallAudit("getShipyards", shipyards, null, ExternalSystemEnum.MMK)
        return shipyards.getOrThrow()
    }

    fun getEquipment(): List<Equipment> {
        val equipment =
            runCatching {
                mmkClient.bookingApi.getEquipment()
            }
        serviceCallAuditService.serviceCallAudit("getEquipment", equipment, null, ExternalSystemEnum.MMK)
        return equipment.getOrThrow()
    }
}
