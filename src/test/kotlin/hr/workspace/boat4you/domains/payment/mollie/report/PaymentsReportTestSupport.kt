package hr.workspace.boat4you.domains.payment.mollie.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hr.workspace.boat4you.domains.payment.mollie.MollieAmount
import hr.workspace.boat4you.domains.payment.mollie.MollieBalanceTransaction
import java.time.Instant

private val MAPPER = jacksonObjectMapper()
private var sequence = 0

/** One Mollie balance transaction; amounts as Mollie's decimal strings (`"-50.25"`), [context] as its JSON object. */
internal fun balanceTransaction(
    type: String,
    at: String,
    result: String,
    initial: String? = null,
    deductions: String? = null,
    context: String? = null,
): MollieBalanceTransaction =
    MollieBalanceTransaction(
        id = "baltr_t${++sequence}",
        type = type,
        resultAmount = MollieAmount("EUR", result),
        initialAmount = initial?.let { MollieAmount("EUR", it) },
        deductions = deductions?.let { MollieAmount("EUR", it) },
        context = context?.let { MAPPER.readTree(it) },
        createdAt = Instant.parse(at),
    )

internal fun paymentContext(
    paymentId: String,
    description: String = "Boat4You",
) = """{"paymentId": "$paymentId", "paymentDescription": "$description"}"""
