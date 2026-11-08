# Android notification shortcut

The optional adapter observes `NetworkInspection` in the host-owned scope. It
posts a silent ongoing notification after the current capture has requests. After
process restart, initial empty capture preserves a prior OS shortcut until explicit
reset/clear/disposal or new capture; it never creates a new notification for empty data.
The host provides localized static copy, an icon and an immutable explicit
activity PendingIntent. The adapter does not choose navigation or prompt for
permission, start a foreground service, or change transport/session behavior.

Use one live adapter per channel ID. Clearing or replacing capture removes old
content; the next captured request can show the shortcut again. Closing capture,
closing the adapter, or cancelling its parent permanently retires that adapter,
including when the scope was already cancelled at construction. Late callbacks
and explicit refresh cannot revive it. The host refreshes after foreground entry
or a notification permission change. Android channel/permission blocks are honored.

The notification contains no URLs, headers, bodies, account data or message data.
The host owns viewer access and clears capture at its account boundary. Android
may retain the OS notification after process death; its PendingIntent must open
the host's activity and route to the shared viewer after navigation is available.
The library keeps no Activity reference and owns no global background lifetime.

Six instrumentation scenarios passed on the real Android OS: capture/reset/late
results, parent cancellation, construction in a cancelled scope, capture closure while
the parent remains live, late old completion after replacement, and retained notification
through initial empty capture after process restart. The consumer also passed actual
background and cold-process notification taps. See the
[exact runtime and independent public CI evidence](measurements/android-notification-runtime-2026-11-08.json).
Hosts whose background services restore identity should attach the adapter on Activity
resume so background-only session restoration cannot retire the retained shortcut.

Core, Ktor, Matrix contracts and viewer remain multiplatform. This module is an
Android OS adapter; Apple/desktop/browser notification shortcuts are separate work.
