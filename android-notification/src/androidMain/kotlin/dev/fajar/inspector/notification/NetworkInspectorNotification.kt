package dev.fajar.inspector.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.fajar.inspector.NetworkInspection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Optional silent shortcut. The host supplies its own immutable Activity intent and permission UI.
 * No activity, service, request content, account identity, or transport work is owned here.
 */
class NetworkInspectorNotification(
    context: Context,
    private val inspection: NetworkInspection,
    scope: CoroutineScope,
    private val destination: PendingIntent,
    private val smallIcon: Int,
    private val title: String,
    private val text: String,
    private val channelId: String = "network-inspector",
) : AutoCloseable {
    private val application = context.applicationContext
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val guard = Any()
    private var closed = false
    private val observation: Job

    init {
        require(smallIcon != 0 && title.isNotBlank() && text.isNotBlank())
        require(channelId.isNotBlank())
        require(destination.creatorPackage == application.packageName)
        if (Build.VERSION.SDK_INT >= 31) require(destination.isImmutable)
        observation =
            scope.launch {
                inspection.recorders
                    .takeWhile { it != null }
                    .collectLatest { recorder ->
                        requireNotNull(recorder)
                            .snapshots
                            .map { it.traces.isNotEmpty() }
                            .distinctUntilChanged()
                            .collect { refresh() }
                    }
            }
        // Also retire when an already-cancelled owner prevented the body from starting.
        observation.invokeOnCompletion { closeNotification() }
        // A cancelled launch may still be waiting for dispatch before completion callbacks run.
        if (!observation.isActive) closeNotification()
    }

    /** Recheck after foreground entry or a permission change; never prompts for permission. */
    @SuppressLint("MissingPermission")
    fun refresh(): Boolean =
        synchronized(guard) {
            if (closed) return@synchronized false
            try {
                if (inspection.recorders.value?.snapshots?.value?.traces.isNullOrEmpty()) {
                    manager.cancel(channelId, NOTIFICATION_ID)
                    return@synchronized false
                }
                if (
                    !manager.areNotificationsEnabled() ||
                        (Build.VERSION.SDK_INT >= 33 &&
                            application.checkSelfPermission(
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED)
                )
                    return@synchronized false
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(
                        NotificationChannel(channelId, title, NotificationManager.IMPORTANCE_LOW)
                            .apply {
                                setShowBadge(false)
                                setSound(null, null)
                                enableVibration(false)
                            }
                    )
                    if (
                        manager.getNotificationChannel(channelId)?.importance ==
                            NotificationManager.IMPORTANCE_NONE
                    )
                        return@synchronized false
                }
                val builder =
                    if (Build.VERSION.SDK_INT >= 26) Notification.Builder(application, channelId)
                    else Notification.Builder(application)
                manager.notify(
                    channelId,
                    NOTIFICATION_ID,
                    builder
                        .setSmallIcon(smallIcon)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(destination)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setVisibility(Notification.VISIBILITY_SECRET)
                        .setLocalOnly(true)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(false)
                        .setPriority(Notification.PRIORITY_LOW)
                        .build(),
                )
                true
            } catch (_: RuntimeException) {
                // Optional observation must not fail app startup, permission changes, or delivery.
                false
            }
        }

    private fun closeNotification() =
        synchronized(guard) {
            if (closed) return@synchronized
            closed = true
            try {
                manager.cancel(channelId, NOTIFICATION_ID)
            } catch (_: RuntimeException) {}
        }

    override fun close() {
        closeNotification()
        observation.cancel()
    }

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}
