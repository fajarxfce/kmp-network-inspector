# Design

## Buffered observation timing — 7 November2026

The store now separates ingestion from transport time. A generation-scoped external
session fences delayed starts, responses, body completions and loss reports after clear
or close. Handles remain store-owned opaque identities; local-clock handles cannot be
finished through externally timed methods. Invalid/reversed time, foreign handles and
late stages do not overwrite retained observations.

Response status/time-to-headers and body-transfer result/duration are distinct facts.
Body failure preserves the actual HTTP response. A successful transfer without a
response observation has unavailable status and incomplete capture. Completed rows
are preferred for eviction; a response whose body is still in progress stays active.
Producer loss marks active external rows conservatively and increments a saturated
counter. It never changes the original transport outcome. Start receipt order remains
stable, including when producer timestamps or completion order differ.

The viewer/filter expose these distinctions. Core66, Ktor39 and Compose21 cases pass
locally, with six desktop UI cases. The initial Ktor streaming fixture exposed an empty
`readRemaining` race; the corrected fixture reads actual bytes with capture both off
and on before triggering the failure. All limitations and fresh/reused report counts
are in [the measurement](measurements/observed-time-local-2026-11-07.json).
The actual Matrix adapter, ABI/binding distribution and application integration remain.

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

## Compose increment — 7 November2026

The optional list/detail surface is implemented independently of capture. Immutable
snapshots and stable request IDs keep pending-to-response updates in one row. The
caller owns search/filter persistence, selection, navigation and Clear/Stop actions.
The transient filter menu is the only internal interaction state. Detail receives
null when the host can no longer resolve the selected ID, so cleared metadata is
not retained by the viewer. No new coroutine or transport callback belongs to this UI.

The first screenshot review exposed an oversized search label and a narrow status
column at font scale2. The final label is bounded to one line, request metadata has
the full row width, and status/duration flow beneath it. Dark320×640/font2 and
light720×375/font1 screenshots were inspected. The filter target is explicitly48dp.

Sixteen module tests and strict assembly pass locally. The4 Wasm cases verify filter
behavior, not canvas interactions. Apple main/test klibs compile; private CI is still
billing-blocked. No physical/native/browser UI acceptance, automatic sharing, export,
pause/resume session implementation, or application integration is implied by the
host-owned controls slot. [Evidence](measurements/compose-local-2026-11-07.json).
