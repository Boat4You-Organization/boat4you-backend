package hr.workspace.boat4you.domains.calls.job

import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class CallJobsTimerTest {
    @Test
    fun `a tick dials even when the previous one failed, and never throws`() {
        val dispatch = mock(CallDispatchJob::class.java)
        val reconcile = mock(CallReconcileJob::class.java)
        doThrow(IllegalStateException("db down")).`when`(dispatch).run()
        val timer = CallJobsTimer(dispatch, reconcile)
        timer.tick()
        timer.tick()
        verify(dispatch, org.mockito.Mockito.times(2)).run()
        timer.stop()
    }
}
