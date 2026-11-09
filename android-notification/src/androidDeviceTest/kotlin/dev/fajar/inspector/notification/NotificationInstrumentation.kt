package dev.fajar.inspector.notification

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.fajar.inspector.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/** Real OS checks. The external runner grants notification permission to this fixture only. */
class NotificationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                resetAndCloseFenceLateCapture()
                parentCancellationRemovesShortcut()
                cancelledParentCannotPostShortcut()
                closedCaptureRemovesShortcutWithoutCancellingParent()
                lateCompletionCannotRemoveReplacementShortcut()
                emptyStartupRetainsPriorShortcutUntilExplicitReset()
            }
            result.putString("stream", "NetworkInspectorNotification: 6 passed\n")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString(
                "stream",
                "NetworkInspectorNotification failed: ${failure.stackTraceToString()}\n",
            )
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private suspend fun resetAndCloseFenceLateCapture() {
        val manager = context.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled())
        val inspection = NetworkInspection()
        val job = SupervisorJob()
        val notification = shortcut(inspection, CoroutineScope(job + Dispatchers.Default))
        try {
            check(!notification.refresh())
            val original = requireNotNull(inspection.recorders.value)
            val attempt = requireNotNull(original.begin(request()))
            await { active() != null }
            val shown = requireNotNull(active())
            check(shown.extras.getString(Notification.EXTRA_TITLE) == "Fixture inspector")
            check(shown.extras.getString(Notification.EXTRA_TEXT) == "Open captured requests")
            check(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
            check(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = manager.getNotificationChannel("network-inspector-fixture")
                check(channel.importance == NotificationManager.IMPORTANCE_LOW)
                check(channel.sound == null && !channel.canShowBadge())
            }
            if (Build.VERSION.SDK_INT >= 31) check(shown.contentIntent.isImmutable)
            inspection.reset()
            await { active() == null }
            original.finish(attempt, NetworkOutcome.Response(200))
            check(!notification.refresh())
            requireNotNull(inspection.recorders.value).begin(request())
            await { active() != null }
            notification.close()
            requireNotNull(inspection.recorders.value).begin(request())
            check(!notification.refresh())
            await { active() == null }
        } finally {
            notification.close()
            inspection.close()
            job.cancelAndJoin()
        }
    }

    private suspend fun parentCancellationRemovesShortcut() {
        val inspection = NetworkInspection()
        val job = SupervisorJob()
        val notification = shortcut(inspection, CoroutineScope(job + Dispatchers.Default))
        try {
            requireNotNull(inspection.recorders.value).begin(request())
            await { active() != null }
            job.cancelAndJoin()
            await { active() == null }
            check(!notification.refresh())
        } finally {
            notification.close()
            inspection.close()
            job.cancelAndJoin()
        }
    }

    private suspend fun cancelledParentCannotPostShortcut() {
        val inspection = NetworkInspection()
        requireNotNull(inspection.recorders.value).begin(request())
        val job = SupervisorJob()
        job.cancelAndJoin()
        val notification = shortcut(inspection, CoroutineScope(job + Dispatchers.Default))
        try {
            check(!notification.refresh())
            check(active() == null)
        } finally {
            notification.close()
            inspection.close()
        }
    }

    private suspend fun closedCaptureRemovesShortcutWithoutCancellingParent() {
        val inspection = NetworkInspection()
        val job = SupervisorJob()
        val notification = shortcut(inspection, CoroutineScope(job + Dispatchers.Default))
        try {
            requireNotNull(inspection.recorders.value).begin(request())
            await { active() != null }
            inspection.close()
            await { active() == null }
            check(!notification.refresh())
            check(job.isActive)
        } finally {
            notification.close()
            inspection.close()
            job.cancelAndJoin()
        }
    }

    private fun request() =
        NetworkRequest(
            NetworkSource.KTOR,
            "GET",
            NetworkEndpoint("https", "fixture.invalid", path = "/private-path"),
        )

    private suspend fun emptyStartupRetainsPriorShortcutUntilExplicitReset() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "network-inspector-fixture"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                        channelId,
                        "Fixture inspector",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        setSound(null, null)
                        setShowBadge(false)
                    }
            )
        }
        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId)
            else Notification.Builder(context)
        // Seed the OS-owned notification left by a previous process, without a second live owner.
        manager.notify(
            channelId,
            1,
            builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Prior process")
                .setOngoing(true)
                .build(),
        )
        await { active()?.extras?.getString(Notification.EXTRA_TITLE) == "Prior process" }
        val inspection = NetworkInspection()
        val job = SupervisorJob()
        // Run the initial empty publication synchronously so the retention check has no race.
        val notification = shortcut(inspection, CoroutineScope(job + Dispatchers.Unconfined))
        try {
            check(!notification.refresh())
            check(active()?.extras?.getString(Notification.EXTRA_TITLE) == "Prior process")
            inspection.reset()
            await { active() == null }
            requireNotNull(inspection.recorders.value).begin(request())
            await { active()?.extras?.getString(Notification.EXTRA_TITLE) == "Fixture inspector" }
            notification.close()
            await { active() == null }
        } finally {
            notification.close()
            inspection.close()
            job.cancelAndJoin()
        }
    }

    private suspend fun lateCompletionCannotRemoveReplacementShortcut() {
        val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val deferredDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    pending.add(block)
                }
            }
        val inspection = NetworkInspection()
        val oldJob = SupervisorJob()
        val nextJob = SupervisorJob()
        val old = shortcut(inspection, CoroutineScope(oldJob + deferredDispatcher))
        var replacement: NetworkInspectorNotification? = null
        try {
            requireNotNull(inspection.recorders.value).begin(request())
            check(old.refresh())
            await { active() != null }
            old.close()
            await { active() == null }
            replacement = shortcut(inspection, CoroutineScope(nextJob + Dispatchers.Default))
            check(replacement.refresh())
            await { active() != null }
            // Finish the old cancelled launch only after the next owner has posted.
            requireNotNull(pending.poll()).run()
            oldJob.cancelAndJoin()
            old.close()
            check(!old.refresh())
            delay(100)
            check(active() != null)
        } finally {
            old.close()
            repeat(20) { pending.poll()?.run() }
            replacement?.close()
            inspection.close()
            oldJob.cancelAndJoin()
            nextJob.cancelAndJoin()
        }
    }

    private fun shortcut(inspection: NetworkInspection, scope: CoroutineScope) =
        NetworkInspectorNotification(
            context,
            inspection,
            scope,
            PendingIntent.getActivity(
                context,
                0,
                Intent().setPackage(context.packageName).setAction("fixture.inspector"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            android.R.drawable.stat_notify_sync,
            "Fixture inspector",
            "Open captured requests",
            "network-inspector-fixture",
        )

    private fun active(): Notification? =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .singleOrNull { it.tag == "network-inspector-fixture" }
            ?.notification

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(20) }
    }
}
