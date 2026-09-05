package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.exceptions.NauSysRateLimitedException
import hr.workspace.boat4you.domains.external.sync.jpa.NausysSearchSyncRetry
import hr.workspace.boat4you.domains.external.sync.jpa.NausysSearchSyncRetryRepository
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NausysSearchSyncRetryQueueTests {
    // Mockito matchers return null; Kotlin inserts a null-check when a platform value meets a
    // non-null parameter, so route them through generic helpers (no mockito-kotlin on the classpath).
    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> eq(value: T): T = ArgumentMatchers.eq(value) ?: value

    @Suppress("UNCHECKED_CAST")
    private fun <T> ArgumentCaptor<T>.captureK(): T = capture() ?: null as T

    private val repository: NausysSearchSyncRetryRepository = mock(NausysSearchSyncRetryRepository::class.java)
    private val queue = NausysSearchSyncRetryQueue(repository)

    private val from = LocalDate.of(2027, 6, 5)
    private val to = from.plusDays(10)

    private fun row(attempts: Int) =
        NausysSearchSyncRetry().apply {
            id = 42
            periodFrom = from
            periodTo = to
            countries = "115"
            this.attempts = attempts
            createdAt = Instant.now()
            nextAttemptAt = Instant.now()
        }

    private fun within(
        expected: Instant,
        actual: Instant,
        tolerance: Duration = Duration.ofSeconds(5),
    ) = Duration.between(expected, actual).abs() <= tolerance

    @Test
    fun `enqueue serializes sorted ids, empty string for no filter, and schedules the first retry 15 min out`() {
        val now: ArgumentCaptor<Instant> = ArgumentCaptor.forClass(Instant::class.java)
        val next: ArgumentCaptor<Instant> = ArgumentCaptor.forClass(Instant::class.java)
        val error = NauSysRateLimitedException("/freeYachtsSearch", 6)

        queue.enqueue(from, to, listOf(115L, 7L), null, emptyList(), error)

        verify(repository).upsert(eq(from), eq(to), eq("7,115"), eq(""), eq(""), eq(error.toString()), now.captureK(), next.captureK())
        assertTrue(within(Instant.now(), now.value))
        assertTrue(within(now.value.plus(NausysSearchSyncRetryQueue.RETRY_STEP), next.value))
    }

    @Test
    fun `markFailure backs off 15 min times attempts and keeps the row`() {
        val row = row(attempts = 2)
        val gaveUp = queue.markFailure(row, IllegalStateException("boom"))

        assertFalse(gaveUp)
        assertEquals(3, row.attempts)
        assertEquals("java.lang.IllegalStateException: boom", row.lastError)
        assertTrue(within(Instant.now().plus(Duration.ofMinutes(45)), row.nextAttemptAt!!))
        verify(repository).save(row)
        verify(repository, never()).deleteById(any())
    }

    @Test
    fun `markFailure gives up and deletes the row on the sixth attempt`() {
        val row = row(attempts = 5)
        val gaveUp = queue.markFailure(row, IllegalStateException("boom"))

        assertTrue(gaveUp)
        verify(repository).deleteById(42L)
        verify(repository, never()).save(any<NausysSearchSyncRetry>())
    }

    @Test
    fun `markSuccess deletes the row`() {
        queue.markSuccess(row(attempts = 1))
        verify(repository).deleteById(42L)
    }

    @Test
    fun `error descriptions are capped at the column length`() {
        val row = row(attempts = 0)
        queue.markFailure(row, IllegalStateException("x".repeat(2000)))
        assertEquals(500, row.lastError!!.length)
    }
}
