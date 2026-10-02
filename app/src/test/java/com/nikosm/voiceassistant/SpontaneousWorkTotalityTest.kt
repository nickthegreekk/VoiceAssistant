package com.nikosm.voiceassistant

import android.content.Context
import android.content.ContextWrapper
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4: `armSpontaneousWork` / `disarmSpontaneousWork` are TOTAL — a WorkManager failure is
 * logged and swallowed, never propagated — matching the pattern `isSpontaneouslyArmed` has
 * always used. This file proves exactly that, and the honesty of that claim matters more
 * than the claim itself, so the boundary is spelled out here rather than glossed.
 *
 * ## What is forced, and how honestly
 *
 * The failure worth testing is "the WorkManager API throws". On the JVM the REAL
 * androidx.work code runs — against the mockable android.jar, which has no Context
 * implementation for WorkManager to initialize its singleton and database against — so
 * `WorkManager.getInstance(context)` genuinely throws while these tests execute. That is a
 * real throw from the real API surface, and it is why the cases below are meaningful rather
 * than tautological. The first test pins that precondition explicitly: if WorkManager ever
 * stopped throwing on this JVM, that test fails loudly instead of the totality cases quietly
 * proving nothing.
 *
 * On-device this is **not** forceable, and this file does not pretend otherwise:
 * `WorkManagerTestInitHelper` installs the real WorkManagerImpl in test mode and exposes no
 * fault-injection seam on `enqueueUniqueWork` / `cancelUniqueWork`, and the singleton is
 * process-wide — a test cannot make it un-initialized from the inside. A mock WorkManager
 * would stop testing the production functions at all. So the honest split is: totality is
 * proven here against a genuine throw; the two production call sites are confirmed by review
 * (documented on [disarmSpontaneousWork]: the Main-dispatcher `refreshSpontaneousSchedule`
 * coroutine that would crash the app, and the worker's `rearm()` whose failure would silently
 * kill the chain), and the full device suite proves the success paths — arming, disarming,
 * the slot alternation, the import reconciliation — are untouched by the wrappers.
 */
class SpontaneousWorkTotalityTest {

    // ContextWrapper(null) keeps the abstract-Context surface minimal (the sibling
    // SettingsManagerCorruptionTest fake uses the same premise): only getApplicationContext
    // needs an answer, and everything past that point is WorkManager failing for real.
    private class MinimalContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val context: Context = MinimalContext()

    @Test fun `the WorkManager API really does throw on this JVM`() {
        val outcome = runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "f4-totality-probe",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SpontaneousDeliveryWorker>().build()
            )
        }
        assertTrue(
            "precondition: if the WorkManager API stopped throwing on the JVM, the totality " +
                "cases below would prove nothing — this failure is what they are built on " +
                "(got: ${outcome.exceptionOrNull()?.let { it::class.java.name + ": " + it.message } ?: "no throw"})",
            outcome.isFailure
        )
    }

    @Test fun `armSpontaneousWork swallows a WorkManager failure`() {
        // Pre-F4 this propagates: refreshSpontaneousSchedule's Main-dispatcher coroutine has
        // no CoroutineExceptionHandler, so the throw would take the process down.
        armSpontaneousWork(context, "F4ArmProbe", slot = 0, delayMillis = 60_000L)
    }

    @Test fun `disarmSpontaneousWork swallows a WorkManager failure`() {
        // Pre-F4 this propagates from the same coroutine, and from the worker's own rearm()
        // path where the throw would fail doWork() and end that persona's chain silently.
        disarmSpontaneousWork(context, "F4DisarmProbe")
    }
}