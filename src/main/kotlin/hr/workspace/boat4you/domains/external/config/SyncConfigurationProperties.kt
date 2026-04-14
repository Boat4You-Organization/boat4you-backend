package hr.workspace.boat4you.domains.external.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

@ConfigurationProperties(prefix = "application.external.sync")
data class SyncConfigurationProperties
    @ConstructorBinding
    constructor(
        val offerMaxYears: Int,
        val minDurationDays: Int,
    )
