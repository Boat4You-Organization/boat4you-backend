package hr.workspace.boat4you

import hr.workspace.boat4you.domains.catalouge.config.TwinCanonicalProperties
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableScheduling

@EnableConfigurationProperties(
    SyncConfigurationProperties::class,
    TwinCanonicalProperties::class,
)
@SpringBootApplication
@EnableScheduling
@EnableRetry
class Boat4youWsApplication

fun main(args: Array<String>) {
    runApplication<Boat4youWsApplication>(*args)
}
