# KMP Network Inspector

A transport-independent network inspector for Kotlin Multiplatform applications.

**Status: metadata core and Ktor adapter locally verified; Matrix bridge/viewer in progress. No library release or application integration yet.**

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
Each attempt remains one row, sorted by start time. Durations use a monotonic clock.
Clear fences old handles; close cannot reopen. Capacity is 1–500 requests. Completed
rows are evicted first, with an eviction count; late completion cannot resurrect them.
Hosts must bound callback ingress when implementing an adapter.

This first core retains **no bodies or query values**. Header values are redacted
except validated content type, length and numeric retry-after. Paths, query names and
header counts are bounded before retention; endpoint hosts must be parsed host names
or IPv6 literals, not credential-bearing authorities. Path segments remain visible,
so an adapter must remove any endpoint-specific secret path segments before calling it.
No request is captured just by constructing a store.

Local validation:14 tests each on JVM, Android host and an actual Wasm browser,
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
