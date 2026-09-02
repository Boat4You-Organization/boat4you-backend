package hr.workspace.boat4you.common.services

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * PII-masking helpers for log lines. Logs are shipped to operator
 * dashboards and ELK-style aggregation tools, both of which can fall
 * outside the same GDPR scope as the live DB — masking PII at the
 * log boundary keeps the data minimisation principle honest.
 *
 * F5-006 fix point: failed-login + reset-password + invite flows
 * previously logged raw `e.email` strings; routed through these
 * helpers they now show `j***@example.com` instead.
 */
object LogMasking {
    /**
     * Client (end-customer) fields stripped from the partner request body before it is stored
     * in `service_call.request_body` (60-day retention, outside the GDPR Art.17 delete flow).
     * `name`, `surname` and `countryId` are deliberately KEPT so a disputed partner booking
     * can still be matched to the customer. Covers both NauSys client shapes
     * (`RestClient.zip` and `RestClient2.zipCode`).
     */
    val CLIENT_PII_FIELDS: List<String> =
        listOf("email", "phone", "mobile", "passportNumber", "birthday", "address", "zip", "zipCode", "skype", "instagram")

    /**
     * Mask an email for log emission. Keeps the first character of
     * the local part (for manual correlation within a day) and the
     * full domain (for fraud-trend analysis at the operator side).
     * Examples:
     *   - "jana@example.com"    → "j***@example.com"
     *   - "x@example.com"       → "x***@example.com"
     *   - "no-at-sign"          → "***"
     *   - null / blank          → "***"
     *
     * Deterministic and stateless. Safe to call on every log line
     * because it never allocates beyond the result string.
     */
    fun maskEmail(email: String?): String {
        if (email.isNullOrBlank()) return "***"
        val atIndex = email.indexOf('@')
        if (atIndex <= 0) return "***"
        val firstChar = email[0]
        val domain = email.substring(atIndex)
        return "$firstChar***$domain"
    }

    /**
     * Removes [CLIENT_PII_FIELDS] from the `client` subtree of a serialized partner request
     * (in place). No-op when there is no `client` object. Returns [node] for chaining.
     */
    fun redactClientPii(node: ObjectNode): ObjectNode {
        (node.get("client") as? ObjectNode)?.remove(CLIENT_PII_FIELDS)
        return node
    }
}
