package hr.workspace.boat4you.domains.catalouge.equipment

import hr.workspace.boat4you.domains.catalouge.jpa.EquipmentRepository
import hr.workspace.boat4you.domains.catalouge.jpa.PartnerEquipmentMappingRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * All 2,778 distinct partner equipment names of the prod snapshot (8.10.2026; equipment/partner_names_8_10.tsv from
 * sim/Sim.java of the audit, which ran commons-text on the same keys): the Kotlin matcher on the R__1_05 keys gives the
 * simulated code for every one, and the resolver (explicit V9_74 links first) gives the final link that V9_75 writes.
 * A key or matcher change changes this fixture on purpose: re-run sim/run.sh, regenerate it and show the diff.
 */
class EquipmentMatcherGoldenTest {
    private val catalogue = EquipmentCatalogueFixture.catalogue()

    private fun pass(): EquipmentLinkResolver.Pass {
        val equipment = mock(EquipmentRepository::class.java)
        `when`(equipment.findAllByOrderByIdAsc()).thenReturn(catalogue)
        val mappings = mock(PartnerEquipmentMappingRepository::class.java)
        `when`(mappings.findAllForResolver()).thenReturn(EquipmentCatalogueFixture.mappings(catalogue))
        return EquipmentLinkResolver(equipment, mappings).newPass()
    }

    @Test
    fun `the matcher reproduces the simulation for every partner name of the snapshot`() {
        val names = EquipmentCatalogueFixture.partnerNames
        assertEquals(2778, names.size)
        val matcher = EquipmentMatcher(catalogue)
        val wrong =
            names.mapNotNull { n ->
                val got = matcher.best(n.name)?.labelCode ?: "NONE"
                if (got == n.matcher) null else "${n.systemId}/${n.partnerItemId} '${n.name}': expected ${n.matcher}, got $got"
            }
        assertTrue(wrong.isEmpty(), "${wrong.size} names differ:\n" + wrong.take(40).joinToString("\n"))
    }

    @Test
    fun `the resolver gives the final link V9_75 writes - explicit mapping first, aliases never`() {
        val pass = pass()
        val names = EquipmentCatalogueFixture.partnerNames
        val wrong =
            names.mapNotNull { n ->
                val got = pass.resolve(n.systemId, n.partnerItemId, n.name)
                val label = got?.labelCode ?: "NONE"
                when {
                    got?.mergedIntoId != null -> "'${n.name}': alias $label"
                    label != n.final -> "${n.systemId}/${n.partnerItemId} '${n.name}': expected ${n.final}, got $label"
                    else -> null
                }
            }
        assertTrue(wrong.isEmpty(), "${wrong.size} names differ:\n" + wrong.take(40).joinToString("\n"))
        // the seed is what makes the difference for these names
        assertTrue(names.count { it.matcher != it.final } >= 60)
    }

    @Test
    fun `every seeded free-text name normalises to the stored key, and explicit wins over the matcher`() {
        val pass = pass()
        val mmk = ExternalSystemEnum.MMK.value
        val seeded = EquipmentCatalogueFixture.seedMappings.map { it.nameNorm }.toSet()
        assertEquals(80, EquipmentCatalogueFixture.seedMappings.size)
        // each seed key is the normal form of at least one snapshot name
        val normalised = EquipmentCatalogueFixture.partnerNames.filter { it.partnerItemId == -1L }.map { EquipmentNames.normalize(it.name) }.toSet()
        assertEquals(emptySet(), seeded - normalised)
        assertEquals("audio-system", pass.resolve(mmk, -1, "Radio on the flybridge")?.labelCode, "matcher alone: flybridge")
        assertEquals("audio-system", pass.resolve(mmk, -1, "RADIO ON THE FLYBRIDGE")?.labelCode, "same normal form")
        assertEquals("water-toys", pass.resolve(mmk, -1, "Banana")?.labelCode, "matcher alone: nothing")
        assertEquals("generator", pass.resolve(mmk, -1, "Jeneratör")?.labelCode)
        // a catalogue item never reads the free-text links
        assertEquals(null, pass.resolve(mmk, 999_999, "Banana"))
    }
}
