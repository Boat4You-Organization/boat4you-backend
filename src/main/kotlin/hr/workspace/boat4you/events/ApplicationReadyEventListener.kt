package hr.workspace.boat4you.events

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ApplicationReadyEventListener {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java.name)

    @EventListener(classes = [ApplicationReadyEvent::class])
    fun logStartup() {
        logger.info("Test data imported. Application ready.")
    }
}
