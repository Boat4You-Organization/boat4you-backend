package hr.workspace.boat4you.domains.invoice.enums

enum class InvoiceLanguageEnum(
    val langName: String,
    val locale: String,
) {
    EN("ENGLISH", "en"),
    HR("CROATIAN", "hr"),
}
