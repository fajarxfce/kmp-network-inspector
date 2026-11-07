# KMP Network Inspector

A transport-independent network inspector for Kotlin Multiplatform applications.

**Status: implementation in progress. No library release or application integration yet.**

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
