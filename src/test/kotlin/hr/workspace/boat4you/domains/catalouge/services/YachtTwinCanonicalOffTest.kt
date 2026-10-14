package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.config.TwinCanonicalProperties
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTwinRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Re-audit R09 step 0 (14.10.2026): the twin detail resolver is OFF in prod. The 23.6. pilot (6047/7576/9431 margin-first,
 * manual group [481, 13163] coverage-first) disagreed with the listing twin matview and made a redirect/canonical loop
 * (/boat/...-6047 308 -> -7576, whose canonical named -6047). Off = every detail request is served as asked, no twin
 * query runs; the listing twin table (V9_69/V9_70) keeps naming the canonical copy.
 */
class YachtTwinCanonicalOffTest {
    private fun prodProperties(): TwinCanonicalProperties {
        val loader = YamlPropertySourceLoader()
        val sources = MutablePropertySources()
        // Profile yml first: it overrides the base file, as in the running app with spring.profiles.active=prod.
        loader.load("prod", ClassPathResource("application-prod.yml")).forEach { sources.addLast(it) }
        loader.load("base", ClassPathResource("application.yml")).forEach { sources.addLast(it) }
        return Binder(ConfigurationPropertySources.from(sources))
            .bind("application.twin-canonical", Bindable.of(TwinCanonicalProperties::class.java))
            .get()
    }

    @Test
    fun `prod profile - resolver off, no pilot ids, no manual groups`() {
        val props = prodProperties()
        assertFalse(props.enabled)
        // An empty pilot list means "canonicalise every group" once enabled; the lists must stay empty while it is off,
        // and nobody may switch it back on with the old pilot ids still configured.
        assertTrue(props.pilotYachtIds.isEmpty(), "${props.pilotYachtIds}")
        assertTrue(props.manualGroups.isEmpty(), "${props.manualGroups}")
    }

    @Test
    fun `off - the La Vita E Bella and Desafinado ids are served as asked, the matcher is never queried`() {
        val repository = mock(YachtTwinRepository::class.java)
        // Even with the old pilot lists present, enabled=false wins.
        val service = YachtTwinCanonicalService(repository, TwinCanonicalProperties(false, listOf(6047, 7576, 9431), listOf(listOf(481, 13163))))

        listOf(6047L, 7576L, 9431L, 481L, 13163L, 42L).forEach { assertEquals(it, service.resolve(it)) }
        assertEquals(null, service.resolve(null))
        verifyNoInteractions(repository)
    }

    @Test
    fun `off with the prod properties`() {
        val repository = mock(YachtTwinRepository::class.java)
        val service = YachtTwinCanonicalService(repository, prodProperties())
        assertEquals(7576L, service.resolve(7576L))
        assertEquals(13163L, service.resolve(13163L))
        verifyNoInteractions(repository)
    }
}
