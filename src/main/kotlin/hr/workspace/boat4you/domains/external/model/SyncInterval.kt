package hr.workspace.boat4you.domains.external.model

import java.time.LocalDate

data class SyncInterval(
    val start: LocalDate,
    val end: LocalDate,
)
