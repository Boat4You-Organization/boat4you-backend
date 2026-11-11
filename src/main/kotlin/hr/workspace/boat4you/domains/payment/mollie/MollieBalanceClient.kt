package hr.workspace.boat4you.domains.payment.mollie

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * Mollie Balances API v2, read-only: the transactions of the organisation's primary balance, where Mollie books each
 * payment together with the fee it deducted. Needs the organisation access token (`balances.read`); the payments API
 * key is refused here (403). Same body-free error handling as [MollieClient]; the token is never logged.
 */
@Component
class MollieBalanceClient internal constructor(
    private val restClient: RestClient,
    private val properties: MollieProperties,
) {
    @Autowired
    constructor(restClientBuilder: RestClient.Builder, properties: MollieProperties) : this(
        MollieClient.configure(restClientBuilder.requestFactory(MollieClient.timeoutRequestFactory()), properties) { properties.orgToken },
        properties,
    )

    /**
     * `GET /v2/balances/primary/transactions`, newest first, [PAGE_SIZE] per page. [from] = the cursor of the previous
     * page ([MollieBalanceTransactionPage.nextFrom]); `null` = the newest page.
     */
    fun listTransactions(from: String? = null): MollieBalanceTransactionPage {
        check(properties.feesEnabled) { "Mollie organisation token is not configured" }
        require(from == null || isValidTransactionId(from)) { "Malformed Mollie balance transaction id" }
        return restClient
            .get()
            .uri { uri ->
                uri.path("/v2/balances/primary/transactions").queryParam("limit", PAGE_SIZE)
                if (from != null) uri.queryParam("from", from)
                if (properties.testMode) uri.queryParam("testmode", "true")
                uri.build()
            }.retrieve()
            .body(MollieBalanceTransactionPage::class.java)
            ?: throw IllegalStateException("Mollie balance transactions: empty response body")
    }

    companion object {
        /** Mollie's maximum page size. */
        const val PAGE_SIZE = 250

        private val TRANSACTION_ID = Regex("^baltr_[A-Za-z0-9]{1,58}$")

        fun isValidTransactionId(id: String?): Boolean = id != null && TRANSACTION_ID.matches(id)

        /**
         * The `from` cursor inside Mollie's `_links.next.href`. Only the id is taken, and only when well-formed: the
         * URL itself is never followed.
         */
        fun cursorOf(nextHref: String?): String? {
            if (nextHref.isNullOrBlank()) return null
            val from = runCatching { UriComponentsBuilder.fromUriString(nextHref).build().queryParams.getFirst("from") }.getOrNull()
            return from?.takeIf { isValidTransactionId(it) }
        }
    }
}
