# Design

The host installs an explicitly owned bounded recorder. Adapters submit transport
facts to that recorder; the viewer observes immutable snapshots. Capture must never
wait for the viewer and must not extend the lifetime of a response, Activity, SDK
client or authentication session. Queue overflow is reported as incomplete capture.

## Modules

- Core: portable event identity, lifecycle, source, bounded sanitized fields, immutable
  snapshots, store capacity and lifecycle. No networking or Compose dependency.
- Ktor: client plugin, per-attempt request/response/failure timing, safe field capture,
  bounded streaming observation that preserves the original consumer's bytes/errors.
- Matrix: bridge from the real Rust SDK HTTP observer. Keep request attempts, backoff
  and app queue wait distinct. Adapter API must not depend on HRIS room ownership.
- Compose: list/detail, source/status/search filters, pause/clear and explicit local
  export. Android/iOS/JVM/Wasm surfaces share state; platform sharing stays optional.

Opt-in dev builds may include the viewer even when Android debuggable is false.
The HRIS host will put access in its developer/settings surface, not in chat headers.
No database browser, query editor, analytics or crash dashboard is part of this library.

## Evaluation before choosing a standalone implementation

Reviewed public sources on7 November2026:

- [KtorScope](https://github.com/mahmoud947/KtorScope): Ktor network tooling with
  Android/iOS targets; no current JVM/Wasm or Matrix Rust observer.
- [KmpInspector](https://github.com/waqas028/kmp-inspector): client-agnostic external
  records and Compose UI for Android/iOS/JVM; no current Wasm target.
- [Kick](https://github.com/bartwell/kick): includes Wasm and modular tooling, but the
  Ktor network record sink is internal. Its reviewed plugin retains raw headers and
  reads a full text response before applying a size cutoff. Adapting it would require
  a separate capture path and changes to the reusable API/buffering policy.

These are observations of the reviewed sources, not claims that the libraries cannot
be extended. A narrow reusable module keeps the requested Matrix adapter and all host
targets under one observable contract without adding unrelated inspection features.
No third-party implementation has been copied into this repository.

## Matrix boundary

The current HRIS SDK4 is immutable. It has HTTP tracing spans with method, URI,
request ID, status, duration and byte sizes plus per-attempt sending events, but no
exposed FFI HTTP observer. Do not rebuild or republish that release. A new observer
must ship as a separate SDK version with tested Android ABIs and Apple bindings.
Ktor/OkHttp capture alone must never be labelled Matrix capture. Streaming/body
support is declared per adapter; unsupported content stays unavailable.

## Delivery sequence

1. Implement/test portable bounded recorder and sanitization contracts.
2. Add tested Ktor instrumentation and a state-driven viewer across all targets.
3. Add actual Matrix observer/FFI lifecycle in a new immutable SDK release; verify
   real requests, retry, cancellation, overflow and removal without content/key leaks.
4. Integrate in HRIS Mobile dev builds, measure overhead and inspect a real chat send.
5. Install the validated mobile build; port reusable host integration to the starter.

Do not publish a release until source/artifact provenance and actual target results
are recorded. Each stage remains open until its implementation and checks exist.
