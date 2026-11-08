# Android notification shortcut

The optional adapter observes `NetworkInspection` in the host-owned scope. It
posts a silent ongoing notification only while the current capture has requests.
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

Five instrumentation scenarios exercise the real OS: capture/reset/late results,
parent cancellation, construction in a cancelled scope, and capture closure while
the parent remains live, and a late old completion after owner replacement. The HRIS consumer separately exercises background and
cold-process notification taps. Compilation is not proof those runtime checks
have passed; exact acceptance is recorded alongside their actual results.

Core, Ktor, Matrix contracts and viewer remain multiplatform. This module is an
Android OS adapter; Apple/desktop/browser notification shortcuts are separate work.
