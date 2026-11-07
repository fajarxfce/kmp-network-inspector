# KMP Network Inspector

A transport-independent network inspector for Kotlin Multiplatform applications.

**Status: metadata core, Ktor adapter, Compose viewer and Android Matrix binding locally verified. Apple Matrix binding and application integration remain in progress. No library release yet.**

The intended modules are a bounded event/store API, Ktor instrumentation, a Matrix
Rust SDK observer adapter and an optional Compose Multiplatform viewer. Target
Android, iOS, desktop JVM and Web/Wasm. The core must not depend on an HRIS account,
company, server, DI container or application navigation graph.

The viewer will show request lifecycle, source, method, endpoint, status, elapsed
time, retry attempts and bounded sanitized headers/body previews where the transport
can supply them. A Matrix SDK operation log is not evidence of HTTP capture; real
Rust requests need an actual observer at the HTTP/tracing boundary. Unsupported
fields must remain explicitly unavailable, never synthesized from app-level success.

Capture must be opt-in, bounded and independent of request delivery. Credentials,
authentication bodies and private encryption keys must be removed before recording.
No TLS interception, certificate bypass, unbounded body reads or background export.
The host owns the recorder and callbacks and can clear/stop them on account change.

See [design and integration sequence](docs/design.md) and [continuation](docs/agent-handoff.md).

## Implemented core

`core` provides `NetworkTraceStore`, opaque attempt handles and a `StateFlow` of
bounded metadata snapshots. The host owns the store and its lifetime. Capture has
no I/O, background scope, SDK/client dependency or request retry policy.

```kotlin
val recorder = NetworkTraceStore(capacity = 200)
val attempt = recorder.begin(
    NetworkRequest(
        NetworkSource.KTOR,
        "GET",
        NetworkEndpoint("https", "example.test", path = "/items"),
    )
)
// The adapter observes the real transport result; the store never sends a request.
if (attempt != null) recorder.finish(attempt, NetworkOutcome.Response(200))
// On account replacement/disposal, erase metadata and reject every late finish.
recorder.close()
```

The example is a suspending caller; the optional Ktor adapter is described below.
Each attempt remains one row, with most recently received starts first. Completion
does not move a row. Durations use a monotonic clock.
Clear fences old handles; close cannot reopen. Capacity is 1–500 requests. Completed
rows are evicted first, with an eviction count; late completion cannot resurrect them.
Hosts must bound callback ingress when implementing an adapter.

This first core retains **no bodies or query values**. Header values are redacted
except validated content type, length and numeric retry-after. Paths, query names and
header counts are bounded before retention; endpoint hosts must be parsed host names
or IPv6 literals, not credential-bearing authorities. Path segments remain visible,
so an adapter must remove any endpoint-specific secret path segments before calling it.
No request is captured just by constructing a store.

Initial core validation:14 tests each on JVM, Android host and an actual Wasm browser,
zero failures/errors/skips; Android AAR and multiplatform assembly pass with strict
checksums. Apple simulator acceptance is blocked: the first private-repository CI could not
start because GitHub reported an account billing/spending-limit problem. No CI tests
ran. See the [CI evidence](docs/measurements/core-first-ci-2026-11-07.json) and the
[exact checks and initial build failures](docs/measurements/core-local-2026-11-07.json).
Run `./gradlew :core:jvmTest :core:testAndroidHostTest :core:wasmJsBrowserTest :core:assemble`
on Linux with JDK21, Android SDK36, Node24 and Chrome. Apple tests use macOS/Xcode26.3.

## Ktor metadata adapter

```kotlin
val recorder = NetworkTraceStore()
val client = HttpClient(yourEngine) {
    install(KtorNetworkInspector) {
        this.recorder = recorder
        // Optional: redact endpoint-specific secret path segments, or return null to exclude.
        sanitizePath = { encodedPath -> encodedPath }
    }
}
```

Imports are `dev.fajar.inspector.NetworkTraceStore` and
`dev.fajar.inspector.ktor.KtorNetworkInspector`. Depend on the optional `ktor` module.
The host owns client/store disposal; closing the store erases metadata and rejects
late completion without canceling delivery. The plugin allocates no background job
or monitor subscription and never reads either body.

Each Ktor engine attempt has its own row, including retries and redirects. Timing
ends at response headers, before validation/body consumption. A401 is retained as
an HTTP response even if the caller rejects it; a later failed body does not overwrite
that status. Query values and URL credentials are excluded. Names, paths and headers
are bounded before retention; header values are redacted by core. Engine-generated
headers after the observation point may be absent. Body size remains unavailable,
not inferred from Content-Length. Typed timeout/cancellation are distinguished; other
engine errors currently use UNKNOWN without retaining exception messages.

Thirteen adapter cases pass on JVM, Android host and an actual Wasm browser (39 total),
with strict dependencies and Android AAR assembly. Apple klibs compile locally;
Apple simulator and real HRIS capture/overhead still need acceptance. This adapter
captures Ktor only; it does not capture the Matrix Rust SDK automatically.
[Exact results and retained initial failures](docs/measurements/ktor-local-2026-11-07.json).

## Optional Compose viewer

The `compose` module exposes `NetworkTraceList`, `NetworkTraceDetail` and
`NetworkTraceFilter` in `dev.fajar.inspector.compose`. The host supplies a Material3
`MaterialTheme`, owns snapshot collection and navigation, and keeps selection as an
ID resolved against the latest snapshot. Pass null to detail after clear/eviction.
The components perform no networking, dependency injection or lifecycle collection.

```kotlin
NetworkTraceList(
    snapshot = snapshot,
    filter = filter,
    onFilterChange = onFilterChange,
    onTraceSelected = onTraceSelected,
    modifier = Modifier.fillMaxSize(),
)
NetworkTraceDetail(snapshot.traces.firstOrNull { it.id == selectedId })
```

Host controls such as Clear belong in the list's optional `actions` RowScope slot.
The host also owns stopping capture and clearing it on account change. Source/status
filters sit behind the icon beside search; the list keeps stable request IDs as
pending records become responses. Rows wrap for larger text without narrowing the
endpoint behind a status column. Details show actual status, timing, bounded metadata
and explicit unavailable fields. This version captures no body content or query values.

Initial checks passed16 viewer-module cases: four filter cases on each of JVM,
Android host and a Wasm browser, plus four desktop Compose interaction/layout cases.
Android AAR, Wasm code and Apple device/simulator main/test klibs compile. Android and
Apple device UI and browser UI runtime are **not yet verified**. Apple CI remains
blocked by the private repository's billing restriction. Linux desktop rendering
requires a font supported by Compose's generic aliases, such as DejaVu Sans/Mono
(`fonts-dejavu-core` on Ubuntu, `ttf-dejavu` on Arch).

[Exact checks, retained failures and synthetic screenshots](docs/measurements/compose-local-2026-11-07.json).
The Matrix observer and HRIS integration remain open; there is no published package yet.

## Buffered transport observations

An adapter receiving buffered native events uses `openObservation()` and
`beginObserved(session, request, startedAtEpochMillis)`. The resulting handle accepts
`observedResponse` and `observedComplete` with durations measured by the transport.
Queue/FFI delay therefore does not become request duration. Local `begin`/`finish`
handles cannot be mixed with this externally timed API.

Header arrival and body completion are separate. A real HTTP200 followed by a body
failure retains that200 and reports the transfer failure. Completion with missing
headers leaves status unavailable; it never fabricates success or remains pending.
`observedLoss(session, positiveDelta)` records a saturated loss count and conservatively
marks active external transfers incomplete. Clear fences buffered starts as well as
finishes/loss; the host must discard the old producer buffer and obtain a new session.
Close permanently erases the store. Native adapters must still bound their input queue
and attempt-handle map and explicitly close their native subscription.

Current local validation:126 cases across core66, Ktor39 and Compose21 on JVM,
Android host and Wasm. Six cases exercise actual desktop Compose UI. Android AARs
and Apple main/test klibs compile; Apple runtime remains blocked by CI billing.
The first streaming-fixture failure and corrected capture-on/off byte-read check are
retained in the [exact evidence](docs/measurements/observed-time-local-2026-11-07.json).
This API is ready for adapter implementation; it does not itself subscribe to Matrix.

## Portable Matrix collector

The optional `matrix` module translates `MatrixHttpSubscription` batches from an actual
SDK HTTP observer into the store. It is the portable collector and binding contract;
an implementation of that subscription must still connect it to the platform SDK.
Operation logs are not a substitute for HTTP events.

```kotlin
// diagnosticsScope is owned and supervised by the host, independently of message delivery.
val captureJob = diagnosticsScope.launch {
    captureMatrixHttp(subscription, recorder)
}
// Stop observation and release its native handle; retained partial requests remain explicit.
captureJob.cancelAndJoin()
// Clear before starting a fresh subscription. Never refeed the previous native buffer.
recorder.clear()
```

Imports are in `dev.fajar.inspector.matrix`. Each batch contains at most 32 observations.
Correlation retains 256 active handles by default, configurable from 1 to 500. A bounded
replay window of twice that capacity admits concurrent starts arriving out of order;
very old delayed starts are discarded and counted. Attempt numbers and transport times
are preserved. Cumulative native loss is counted by delta, and discarded observations
are also reported. Counters saturate instead of wrapping.

Stopping collection closes the exact subscription and marks unfinished captures
unavailable. It does not fabricate timeout, cancellation, HTTP success or chat delivery.
The binding implementation must stop native ingress and dispose its handle. Source
failures propagate to the host's diagnostics job; keep it separate from delivery jobs.
Foreign/late IDs, oversized batches, regressing counters and sources that return empty
non-progress batches cannot grow memory or create a busy read loop.

Current checks pass **168 fresh cases**: core69, Ktor39, Compose21 and Matrix39.
Matrix's 13 scenarios run on JVM, Android host and a real Wasm browser using bounded
subscription fixtures. Android AARs and Apple main/test klibs compile. Actual generated
native binding integration, Apple runtime, HRIS capture/overhead and publication remain.
[Exact evidence](docs/measurements/matrix-bridge-local-2026-11-07.json).

## Experimental generated Android binding

The `feat/matrix-rust-bindings-20261107` branch adds `matrix-rust-android` on top of
our portable Matrix collector. It currently requires the **unpublished four-ABI SDK
candidate 26.10.23-hris.5**. Local builds supply its verified artifact directory with
`-PmatrixSdkRepository=/absolute/path/to/candidate/repository`. Main remains portable
until distribution and cross-platform integration are ready.

Thirteen Android host cases and five real x86_64 JNI/loopback scenarios pass. They
cover native metadata/timing, stop/disposal, replacement, preserving an active request
when capture is cancelled, and preserving all100 requests when the capture queue fills.
[Evidence and limits](docs/measurements/matrix-android-native-2026-11-07.json).
No Apple native bridge or Mobile integration is accepted yet.

The host opens `client.observeNetworkRequests()` and passes it to `captureMatrixHttp`
in an owned, supervised diagnostics coroutine. Create the subscription inside that
coroutine so a never-started job cannot leak it. Cancel/join collection before clearing
the store or disposing the client. The adapter stops/disposes only its own subscription;
it does not authenticate, start sync, retry, or turn HTTP status into delivery receipts.

The device fixture is assembled with `:matrix-rust-android:assembleAndroidDeviceTest`.
`-PmatrixObserverTestAbi=x86_64` limits the test APK to the selected ABI. Read the package
from compiled output metadata; Android library tests use `dev.fajar.inspector.matrix.rust.test`.
Its runner is `dev.fajar.inspector.matrix.rust.ObserverInstrumentation`. It uses only an
owned loopback server and no HRIS account or data. Compile before starting the emulator.

## Reset and application ownership

`NetworkInspection` owns a replaceable recorder without allocating a coroutine or
transport. Use it when the host offers Clear or changes accounts. Reset closes and
erases the previous store before publishing a new one. Closing cannot be undone by
a late reset. Request IDs belong to a recorder: reset the viewer's selection when
the recorder changes.

```kotlin
val inspection = NetworkInspection(capacity = 200)
val client = HttpClient(yourEngine) {
    install(KtorNetworkInspector) {
        recorderProvider = { inspection.recorders.value }
    }
}
// Each actual Matrix client has its own supervised, client-owned lifetime.
val capture = sdkClientScope.launch {
    captureMatrixHttp(inspection) { matrixClient.observeNetworkRequests() }
}
// Clear or account replacement: buffered/late old events cannot populate the new store.
inspection.reset()
// Application disposal ends collection and releases the subscription; never the SDK client.
inspection.close()
capture.join()
```

The Ktor provider is a nonblocking lookup evaluated once per engine attempt. Each
attempt retains its original recorder through completion, retry and redirect. Null
skips capture; provider failures cannot alter the request. A fixed `recorder` remains
supported; configure exactly one option.

The Matrix overload cancels and joins the previous collector before creating a fresh
subscription. Source failure waits for a reset without a retry loop. Closing the
inspection ends collection. The host must still cancel and join a client's capture
before destroying that native client. Android `observeNetworkRequests` is in the
optional `matrix-rust-android` module on this candidate branch; SDK5 is not yet published.

All226 JVM/Android-host/Wasm cases pass freshly; Apple klibs and the six-case native
fixture compile. The new native reset case has not executed yet. [Exact evidence](docs/measurements/inspection-lifetime-local-2026-11-07.json).
