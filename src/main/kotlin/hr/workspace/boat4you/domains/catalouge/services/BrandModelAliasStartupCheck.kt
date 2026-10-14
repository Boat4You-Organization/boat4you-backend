package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.jpa.ModelRepository
import hr.workspace.boat4you.domains.catalouge.utils.BrandModelAliases
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * R02 guard G3: reports a `partner/brand_model_alias.txt` entry whose target model id is missing or renamed (log ERROR).
 * The syncs ignore such an entry anyway (they check the target on every hit) - this only makes it visible at start.
 */
@Component
class BrandModelAliasStartupCheck(
    private val modelRepository: ModelRepository,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        runCatching {
            val aliases = BrandModelAliases.aliases
            val names = modelRepository.findAllById(aliases.map { it.targetModelId }).associate { it.id to it.name }
            aliases
                .filterNot { BrandModelAliases.matchesTarget(it, names[it.targetModelId]) }
                .forEach {
                    log.error(
                        "R02 brand model alias ignored: ${it.system} '${it.label}' -> model ${it.targetModelId} " +
                            "'${it.targetModelName}', but the model row is '${names[it.targetModelId]}'",
                    )
                }
        }.onFailure { log.error("R02 brand model alias check failed", it) }
    }
}
