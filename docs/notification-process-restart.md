# Notification after a background process restart

Android can retain a silent inspector notification after process death, then start
an application service before the user taps it. The new process initially has an
empty in-memory capture. That initial absence is not an explicit Clear operation.

The adapter preserves the existing OS shortcut until new capture data appears or
an explicit lifecycle/capture boundary retires it. It never invents a notification
for a new empty capture. Replacing the recorder, clearing previously observed data,
closing capture, closing the adapter, or cancelling its owner still removes it.
The retained notification contains only the host's static title and instruction;
request metadata is not persisted in the notification. The host owns guarded
navigation after its Activity is created.

Six instrumentation cases now include a seeded prior OS notification observed by
a new adapter with an empty recorder. A synchronous dispatcher removes scheduling
ambiguity from the initial-state assertion; explicit reset, new capture replacement
and disposal then exercise the actual NotificationManager. Real application process
restart and notification taps remain a separate consumer acceptance step.

The correction follows a real consumer failure: an Android background service
restarted the application before its retained shortcut was tapped, and the initial
empty collector cancelled it. The prior five native lifecycle cases had passed.
See measurements/android-notification-startup-local-2026-11-08.json for compilation,
prior CI results, and the explicit boundary of runtime acceptance.
