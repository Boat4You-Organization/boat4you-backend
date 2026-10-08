package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.enums.CategoryEnum
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.publicAmenities
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Mario 8.10.2026 (equipment audit, decision b): the public yacht page shows ONLY equipment linked to our catalogue. An
 * unlinked partner row is free text the web would print raw - agency notes, "4 double cabins", a charter company's
 * wording - so it stays in the DB and reaches admins only. One row per code either way. Public rows carry our catalogue
 * name, an MMK free-text row no comment (its value is an agency's own text), and a row the partner marks absent
 * ("false", "0", quantity 0) is left out (review 8.10.2026).
 */
class YachtMapperAmenitiesTest {
    private val mapper = YachtMapper(mock(ExchangeRateCalculationService::class.java), mock(YachtExtrasMapper::class.java), mock(YachtCapacityMapper::class.java))

    private fun equipment(
        id: Long,
        label: String,
        name: String,
    ) = Equipment().apply {
        this.id = id
        labelCode = label
        this.name = name
        category = CategoryEnum.GALLEY
        matchKeys = ""
    }

    private val fridge = equipment(14, "fridge", "Fridge")
    private val wifi = equipment(58, "wifi", "WiFi")
    private val radar = equipment(32, "radar", "Radar")
    private val generator = equipment(29, "generator", "Generator")

    private fun row(
        id: Long,
        name: String,
        equipment: Equipment?,
        externalId: Long? = 100L,
        comment: String? = null,
        quantity: BigDecimal? = null,
    ) = YachtEquipment().apply {
        this.id = id
        this.name = name
        this.equipment = equipment
        this.equipmentId = equipment?.id
        this.externalId = externalId
        this.comment = comment
        this.quantity = quantity
    }

    private val rows =
        listOf(
            row(1, "Refrigerator", fridge, comment = "130 L"),
            row(2, "Fridge on flybridge", fridge),
            row(3, "4 double cabins", null, externalId = -1),
            row(4, "Wi-Fi by the operator's office", wifi, externalId = -1, comment = "password at the base"),
            row(5, "Starter-pack by the operator", null, externalId = -1),
            row(6, "4 double cabins", null, externalId = -1),
        )

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `the public sees linked rows only, one per code`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        assertEquals(listOf(1L, 4L), mapper.amenities(rows).map { it.id })
        SecurityContextHolder.clearContext()
        assertEquals(listOf(1L, 4L), mapper.amenities(rows).map { it.id }, "no authentication at all")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("customer", null, listOf(SimpleGrantedAuthority("USER")))
        assertEquals(listOf(1L, 4L), mapper.amenities(rows).map { it.id }, "a signed-in customer")
    }

    @Test
    fun `public rows carry our catalogue name, free-text rows no comment`() {
        val public = mapper.amenities(rows)
        assertEquals(listOf("Fridge", "WiFi"), public.map { it.name }, "never the partner's wording")
        assertEquals(listOf("130 L", null), public.map { it.comment }, "a catalogue item keeps its value, MMK free text none")
        assertEquals(listOf("fridge", "wifi"), public.map { it.equipment?.labelCode })
    }

    @Test
    fun `a row the partner marks absent is not public`() {
        val absent =
            listOf(
                row(10, "Radar", radar, comment = "false"),
                row(11, "Generator", generator, externalId = -1, comment = " NO "),
                row(12, "Wi-Fi", wifi, comment = "0"),
                row(13, "Refrigerator", fridge, quantity = BigDecimal.ZERO),
                row(14, "Fridge", fridge, comment = "true"),
                row(15, "Generator 2", generator, comment = "n/a"),
            )
        assertEquals(listOf(14L), publicAmenities(absent).map { it.id }, "the present fridge row wins over the absent one")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("admin", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
        assertEquals(listOf(10L, 11L, 12L, 13L), mapper.amenities(absent).map { it.id }, "admins see every code, absent ones too")
    }

    @Test
    fun `an admin sees every row with the partner's wording, unlinked rows one per name`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("admin", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
        val admin = mapper.amenities(rows)
        assertEquals(listOf(1L, 3L, 4L, 5L), admin.map { it.id })
        assertEquals("Wi-Fi by the operator's office", admin.single { it.id == 4L }.name)
        assertEquals("password at the base", admin.single { it.id == 4L }.comment)
    }
}
