package hr.workspace.boat4you.domains.external.nausys.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.service.ServiceCallAuditService
import org.openapitools.client.nausys.model.AllYachtsRequest
import org.openapitools.client.nausys.model.RestCharterBaseList
import org.openapitools.client.nausys.model.RestCharterCompanyList
import org.openapitools.client.nausys.model.RestCountryList
import org.openapitools.client.nausys.model.RestEquipmentList
import org.openapitools.client.nausys.model.RestLocationList
import org.openapitools.client.nausys.model.RestRegionList
import org.openapitools.client.nausys.model.RestSeasonList
import org.openapitools.client.nausys.model.RestServiceList
import org.openapitools.client.nausys.model.RestYachtBuilderList
import org.openapitools.client.nausys.model.RestYachtList
import org.openapitools.client.nausys.model.RestYachtModelList
import org.openapitools.client.nausys.model.RestYachtReservationOccupancyList
import org.openapitools.client.nausys.model.YachtCategoriesResponse
import org.springframework.stereotype.Component

@Component
class NauSysAuditedClient(
    private val nauSysClient: NauSysClient,
    private val serviceCallAuditService: ServiceCallAuditService,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val objectMapper: ObjectMapper,
) {
    fun allYachts(
        charterCompanyId: Long,
        allYachtsRequest: AllYachtsRequest,
    ): RestYachtList {
        val nausysResponse =
            runCatching {
                nauSysClient.defaultApi.allYachts(charterCompanyId, allYachtsRequest)
            }
        serviceCallAuditService.serviceCallAudit(
            "allYachts",
            nausysResponse,
            nausysResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            "charterCompanyId: $charterCompanyId " + serializeExcludingCredentials(allYachtsRequest),
        )
        return nausysResponse.getOrThrow()
    }

    fun getOccupancyByYear(
        companyId: Int,
        year: Int,
    ): RestYachtReservationOccupancyList {
        val nausysResponse =
            runCatching {
                nauSysClient.defaultApi.getOccupancyByYear(companyId, year, nauSysAuthProvider.auth)
            }
        serviceCallAuditService.serviceCallAudit(
            "getOccupancyByYear",
            nausysResponse,
            nausysResponse.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
            "companyId: $companyId, year: $year",
        )

        return nausysResponse.getOrThrow()
    }

    fun allCharterCompanies(): RestCharterCompanyList {
        val response =
            runCatching {
                nauSysClient.defaultApi.allCharterCompanies(nauSysAuthProvider.auth)
            }
        serviceCallAuditService.serviceCallAudit(
            "allCharterCompanies",
            response,
            response.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )

        return response.getOrThrow()
    }

    fun allCountries(): RestCountryList {
        val countries =
            runCatching {
                nauSysClient.defaultApi.allCountries(nauSysAuthProvider.auth)
            }
        serviceCallAuditService.serviceCallAudit(
            "allCountries",
            countries,
            countries.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return countries.getOrThrow()
    }

    fun allRegions(): RestRegionList {
        val regions = runCatching { nauSysClient.defaultApi.allRegions(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allRegions",
            regions,
            regions.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return regions.getOrThrow()
    }

    fun allLocations(): RestLocationList {
        val locations = runCatching { nauSysClient.defaultApi.allLocations(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allLocations",
            locations,
            locations.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return locations.getOrThrow()
    }

    fun allYachtCategories(): YachtCategoriesResponse {
        val yachtCategories = runCatching { nauSysClient.defaultApi.allYachtCategories(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allYachtCategories",
            yachtCategories,
            yachtCategories.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return yachtCategories.getOrThrow()
    }

    fun allYachtBuilders(): RestYachtBuilderList {
        val manufacturers = runCatching { nauSysClient.defaultApi.allYachtBuilders(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allYachtBuilders",
            manufacturers,
            manufacturers.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return manufacturers.getOrThrow()
    }

    fun allYachtModels(): RestYachtModelList {
        val models = runCatching { nauSysClient.defaultApi.allYachtModels(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allYachtModels",
            models,
            models.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return models.getOrThrow()
    }

    fun allEquipment(): RestEquipmentList {
        val equipment = runCatching { nauSysClient.defaultApi.allEquipment(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allEquipment",
            equipment,
            equipment.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return equipment.getOrThrow()
    }

    fun allServices(): RestServiceList {
        val services = runCatching { nauSysClient.defaultApi.allServices(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allServices",
            services,
            services.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return services.getOrThrow()
    }

    fun allSeasons(): RestSeasonList {
        val services = runCatching { nauSysClient.defaultApi.allSeasons(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allSeasons",
            services,
            services.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return services.getOrThrow()
    }

    fun allBases(): RestCharterBaseList {
        val bases = runCatching { nauSysClient.defaultApi.allCharterBases(nauSysAuthProvider.auth) }
        serviceCallAuditService.serviceCallAudit(
            "allCharterBases",
            bases,
            bases.getOrNull()?.status,
            ExternalSystemEnum.NAUSYS,
        )
        return bases.getOrThrow()
    }

    fun serializeExcludingCredentials(obj: Any): String {
        val node = objectMapper.valueToTree<ObjectNode>(obj)

        // Remove sensitive fields
        node.remove("username")
        node.remove("password")
        node.remove("credentials")

        return objectMapper.writeValueAsString(node)
    }
}
