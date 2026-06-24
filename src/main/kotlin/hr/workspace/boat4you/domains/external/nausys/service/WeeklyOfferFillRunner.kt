package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.nausys.config.WeeklyOfferFillProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Pilot runner for the NauSys weekly 7-day offer fill (see
 * [WeeklyOfferFillProperties]). `@Profile("data-sync")` so it only runs on the
 * sync node (cusma3), never on the API node. Fires once per startup, in a
 * background daemon thread so it never blocks boot, and only when enabled with a
 * non-empty pilot allow-list. Idempotent — `syncOffersForAsync` upserts.
 */
@Component
@Profile("data-sync")
class WeeklyOfferFillRunner(
    private val props: WeeklyOfferFillProperties,
    private val offerIntegrationService: NauSysYachtOfferIntegrationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.runAllOnStartup) {
            return
        }
        val horizonEnd = LocalDate.now().plusMonths(props.horizonMonths)
        log.info("weekly-offer-fill ALL: startup run requested, horizon {}, chunkSize {}", horizonEnd, props.chunkSize)
        Thread(
            {
                try {
                    val offerWeeks = offerIntegrationService.weeklyOfferFillAllNauSys(horizonEnd, props.chunkSize)
                    log.info("weekly-offer-fill ALL startup run: {} offer-weeks", offerWeeks)
                } catch (e: Exception) {
                    log.error("weekly-offer-fill ALL startup run failed: {}", e.message, e)
                }
            },
            "weekly-offer-fill-all",
        ).apply { isDaemon = true }.start()
    }
}
