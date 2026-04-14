package hr.workspace.boat4you.domains.external.mmk.client

import org.openapitools.client.mmk.api.BookingApi
import org.openapitools.client.mmk.api.GeneralApi
import org.openapitools.client.mmk.api.InvoiceApi
import org.openapitools.client.mmk.api.UserApi
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class MmkClient(
    @Qualifier("mmkRestClient") private val restClient: RestClient,
) {
    val generalApi = GeneralApi(restClient)
    val bookingApi = BookingApi(restClient)
    val userApi = UserApi(restClient)
    val invoiceApi = InvoiceApi(restClient)
}
