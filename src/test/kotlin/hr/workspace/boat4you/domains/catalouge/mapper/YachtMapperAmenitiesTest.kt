package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals

/**
 * Mario 8.10.2026 (equipment audit, decision b): the public yacht page shows ONLY equipment linked to our catalogue. An
 * unlinked partner row is free text the web would print raw - agency notes, "4 double cabins", a charter company's
 * wording - so it stays in the DB and reaches admins only. One row per code either way.
 */
class YachtMapperAmenitiesTest {
    private val mapper = YachtMapper(mock(ExchangeRateCalculationService::class.java), mock(YachtExtrasMapper::class.java), mock(YachtCapacityMapper::class.java))

    private fun row(
        id: Long,
        name: String,
        equipmentId: Long?,
    ) = YachtEquipment().apply {
        this.id = id
        this.name = name
        this.equipmentId = equipmentId
    }

    private val rows =
        listOf(
            row(1, "Refrigerator", 14),
            row(2, "Fridge on flybridge", 14),
            row(3, "4 double cabins", null),
            row(4, "Wi-Fi & Internet", 58),
            row(5, "Starter-pack by the operator", null),
            row(6, "4 double cabins", null),
        )

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `the public sees linked rows only, one per code`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        assertEquals(listOf(1L, 4L), mapper.amenityRows(rows).map { it.id })
        SecurityContextHolder.clearContext()
        assertEquals(listOf(1L, 4L), mapper.amenityRows(rows).map { it.id }, "no authentication at all")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("customer", null, listOf(SimpleGrantedAuthority("USER")))
        assertEquals(listOf(1L, 4L), mapper.amenityRows(rows).map { it.id }, "a signed-in customer")
    }

    @Test
    fun `an admin sees every row, unlinked rows one per name`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("admin", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
        assertEquals(listOf(1L, 3L, 4L, 5L), mapper.amenityRows(rows).map { it.id })
    }
}
