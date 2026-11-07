# Continuation — 7 November 2026, 17:22 UTC

## Android binding candidate — 7 November 2026, 20:14 UTC

The separate native-binding branch contains matrix-rust-android. It maps the actual
observer records, retains native timing/attempts, rejects incompatible unsigned ranges
and bounds, and owns stop-before-disposal. Concurrent readers are rejected. Closing
wakes the read before disposing the handle; diagnostic collection never owns the client.

Local host13 cases pass in19.312s on36 stable inputs (strict-3/77851, REAPED). Strict-1
missing inherited checksums and strict-2 fixture exception-copy failure are preserved.
The Android instrumentation fixture compiles:device-2/32912 passed16.104s/38 stable inputs.
It has five real JNI/loopback scenarios but NONE have executed yet. Device-3/27627
passed17.211s/38 stable inputs after keepDebugSymbols. The rebuilt APK contains the
exact accepted native hash. All local compiler jobs are reaped; no emulator is running.
Device-4/42013 is also REAPED:19.273s/38 stable inputs. The fixture now explicitly
calls Instrumentation.start from onCreate and reports a controlled failed scenario/type.
Its APK still contains the exact ARM64 native hash; no installation or execution yet.
[Exact evidence](measurements/matrix-android-binding-local-2026-11-07.json).

The module uses an explicitly unpublished ARM64 candidate coordinate. Its local mirror
is .work/matrix-android-2026-11-07/sdk-repository, supplied with -PmatrixSdkRepository.
Do not merge into library main/publish before replacing it with the accepted paired SDK.
One native fixture APK is compiled only; it has not been installed anywhere. The actual
JNI test needs the x86_64 ABI from original Android CI42408652180 (all four jobs started).
Apple CI42408920984 at81a82bf9 is also building; no target acceptance claimed. Sources and
build drivers live in Mobile branch feat/matrix-http-observer-20261107, current2f45785f.

Inspector main388a1cb remains the accepted portable base. Its first CI42407765072 ran
zero steps due to billing; evidence is included here. Visibility question remains pending.
No private source may be copied into public Mobile to bypass billing. Phone/stopped
original Bima emulator remain55. Sourcebb106154 viewport fix is accepted but uninstalled;
composer/latency and original54 PRD groups remain active. Next build four-ABI SDK, execute
actual observer fixture, integrate the viewer/transport into Mobile, then port to Starter.

## Current continuation — 7 November 2026, 19:44 UTC

The portable Matrix subscription/collector is locally accepted in this commit. It
preserves actual transport IDs/attempts/times, bounds correlation/replay, reports
loss and closes the exact source on stop/cancellation/failure. Unfinished observation
is unavailable, never a fabricated request cancellation. Actual generated SDK adapters
are still required. Keep the diagnostic job supervised separately from delivery.

Lock-1 (80797) REAPED:19.568s, intentional two npm workspace links only. Strict-1
(70948) REAPED:161.923s on33 unchanged inputs, all168 cases fresh, zero failures/errors/
skips: core69/Ktor39/Compose21/Matrix39. Android AAR and Apple main/test klibs compile.
No actual Apple or native/browser UI acceptance. [Evidence](measurements/matrix-bridge-local-2026-11-07.json).
First external-time CI42405812195 ondfebf666 ran zero steps due to GitHub billing.
[Annotations](measurements/observed-time-first-ci-2026-11-07.json). Repo remains private;
the public-visibility question is unanswered. No release or application integration.

Rust ARM64 first generation (93078) REAPED:1085.305s,836 unchanged inputs. Native ELF
139025528B SHA256582925c360212fdbcea69ec8499395f43d676cc8e8d8f25fc07edfe45a9f6609,
four16KiB LOAD alignments, expected native entry points. Nine Kotlin files generated,
eight identical toSDK4. The actual subscription class has two close() declarations
(UniFFI AutoCloseable and exported native close), so this pair is REJECTED before
Kotlin compilation or runtime. Do not package it or edit generated code.

Only exported FFI close was renamed to stop; native subscription close is unchanged.
FFI-stop-1 (16230) passed18.950s on unchanged inputs and is REAPED. Sole heavy job22819
now rebuilds/regenerates ARM64 in
`/home/fajar/hris/.worktrees/matrix-sdk-http-observer-2026-11-07/.work/http-observer-2026-11-07/android-arm64-2`.
Rust source is FROZEN until reaped. Its current FFI file differs from accepted patch
bce66311; regenerate/verify the patch and record the collision/fix on the isolated SDK
branch. No SDK5 release. The fresh Kotlin wrapper is
`/home/fajar/hris/.worktrees/matrix-kotlin-http-observer-2026-11-07` atc1664f7, currently
clean. Preserve both original frozen inventories and every original Bima key/queue ID.

Mobile recoverye5eb5c6a and primarybb106154 are pushed. Phone and stopped original
emulator remain55; viewport fix awaits56, composer keyboard/latency remain open.
95 first CI control and both native artifacts are audited (native measurement awaits
its next Mobile commit). Bothbb106 first runs have now started; original records are
in Mobile `.work/chat-new-message-position-2026-11-07/bb-first-start.json`.
Main/Group0f496a11/e626f773 are pushed. Continue native adapters/paired target checks,
real capture/overhead, validated install, Starter integration and original54-group
communications scope. Earlier running-job statements below are historical.

## Current continuation — 7 November2026,19:20UTC

External observed-time/transfer API is locally accepted in this commit. A generation
session fences delayed buffered starts as well as response/completion/loss, preserving
native timestamps and separate header/body timing. Missing headers remain unavailable;
body failure keeps actual HTTP status. Loss is explicit, bounded and conservative.
Viewer/filter distinguish these states. No native adapter or app integration yet.

Strict-1 (37446) REAPED:56.266s,29 unchanged inputs, all66 core cases passed. Android
Ktor failed its empty-stream readRemaining fixture. Corrected fixture performs actual
byte reads and verifies capture enabled/disabled. Strict-2 (27288) REAPED:104.013s,
29 unchanged inputs,60 fresh Ktor/Compose cases; same-source core66 reports reused.
All126 reports have zero failures/errors/skips. Android AAR and Apple main/test klibs
compile; two synthetic screenshots were reopened. [Evidence](measurements/observed-time-local-2026-11-07.json).
No Apple/runtime/browser UI claim; private CI billing and visibility question remain.

Rust observer source bce66311 is accepted/pushed on Mobile's isolated SDK branch.
15 Rust cases, native FFI and Wasm checks pass. Sole heavy job93078 now builds the
first ARM64 candidate/generated binding in
`/home/fajar/hris/.worktrees/matrix-sdk-http-observer-2026-11-07/.work/http-observer-2026-11-07/android-arm64-1`.
Rust inputs are FROZEN until reaped; no other compiler/emulator may overlap. No binding
or ABI result yet. SDK4 and frozen inventories remain unchanged. No SDK5 publication.

Next: bounded Matrix adapter preserving native IDs/attempts, timestamp/loss/close
semantics; generated binding and paired native target verification; actual Mobile
capture and overhead, then validated phone update and reusable Starter integration.
Phone/original stopped Bima emulator remain55. Mobile bb106154 bubble first-layout
fix is accepted but not installed; composer keyboard and latency remain open.
Continue the original54-group communications scope. Earlier job statuses below are
historical. Do not rerun unchanged blocked CI or change visibility without an answer.

## First Compose CI — 7 November2026,18:58UTC

Viewer implementationf71f308c9530657c393514e2b930c6015c3a737a is pushed. First run
42403768313 failed before either job ran any step; GitHub reported the same billing/
spending-limit block. No CI or Apple runtime tests executed. [Annotations](measurements/compose-first-ci-2026-11-07.json).
The repository remains private and the visibility question is pending. Local evidence
is unchanged; do not retry unchanged blocked CI or infer visibility approval.

Rust core-1 (75358) is REAPED:14/15 tests passed in180.080s including fresh compilation;
one body-cancellation fixture dropped its only client owner and therefore correctly
cleared the observation channel. The fixture now retains a separate client clone.
Sole heavy core-2 is running; no SDK acceptance or second compiler/emulator yet.
Mobile first3308 CI artifacts are downloaded/hash-verified, awaiting proof review;
primarybb106154 first control42403266288/native42403266310 are pending.

## Current increment — 7 November2026,18:56UTC

The optional Compose viewer is locally accepted in this implementation commit.
Five Kotlin source/test files plus Gradle/CI add list/detail, bounded source/status/
query filters, stable row identity, selection callbacks and host-owned action slots.
No transport or lifecycle work is in composables. The two synthetic screenshots in
`docs/images` were opened after correcting large-text search/row layout and the48dp
filter target. [Exact evidence](measurements/compose-local-2026-11-07.json).

Strict17 passed21.264s on28 unchanged inputs.16 current-source module cases pass:
JVM8 (four actual UI), Android host4, actual Wasm browser filter4. Reused core42 and
Ktor39 cases are verified,97 total. Android AAR65583B SHA256
0f035e72ce680ede49aa62526df34d7efe742fba5f9605675e2f8441b5c6135f;
Apple main/test klibs and Wasm compile, with no Apple/native-device/browser UI runtime
claim. All17 attempts, missing-checksum failures, the font failure,40dp control failure,
intentional lock update and official checksum provenance are retained. The final run
reused tests; it did not execute new test cases. All inspector jobs are REAPED.

Repo stays private; the visibility question remains unanswered and billing prevented
both earlier first CI runs from executing any steps. Do not change visibility or call
Apple runtime accepted. There is no release, body capture/export, Matrix adapter or
HRIS/starter integration. The next source push gets its own first CI; no blind rerun.

Matrix observer implementation is now UNACCEPTED in the isolated Rust worktree
`../.worktrees/matrix-sdk-http-observer-2026-11-07`. Its client-owned bounded pull
channel, actual Matrix SDK attempt hook, cancellation/body stages and FFI are written.
Sole heavy job75358 runs core-1 (15 proposed tests) in a fresh target directory; no
second compiler/emulator may overlap. No Rust test result, generated binding, SDK5
artifact or publication exists yet. SDK4 and frozen inventories are unchanged.

Mobile recovery and primary arebb106154, pushed. Both95e35b9d original CI runs started
before promotion; bb106154 first CI is next. Phone and stopped original Bima emulator
remain55. Pending/draft inbox fix is installed. New bubble first-layout fix is tested
but not yet built/installed; composer keyboard and latency remain open. Continue the
original54-group communications scope after these fixes; none of the PRD groups are
newly declared complete. Historical sections below are not current job status.


## Parent continuation — 7 November 2026, 18:14 UTC

Mobile recovery f7ba99f5 records validated/installed release55; primary95e35b9d has
pending first CI. Phone and original stopped Bima emulator are55, keys/data retained.
Offline pending/draft preview and automatic original-message reconnect pass. Keyboard
and initial bubble clipping remain open. Sole heavy local job9523 runs the Mobile UI
regression baseline: two new-message first-layout cases reproduce the old failure,
older viewport anchoring passes; remaining quality tasks still running. No inspector
compiler may overlap. Use the current Mobile handoff for subsequent job ownership;
earlier parent statuses below are historical. Main/Group4f53bcb6/183e3989 are pushed.
This library still needs its viewer and actual Matrix observer/adapter; no release or
app integration exists. The public-visibility question remains unanswered.


## First Ktor CI — 7 November 2026, 18:00 UTC

Implementation fadf22ed87783e4692d0b5289fab005b36982e17 is pushed. First CI42398651350
failed before either job executed any step: the same GitHub billing/spending-limit
restriction as core. Local39 adapter and42 core cases remain accepted; zero Apple
runtime/CI acceptance is added. [Exact annotations](measurements/ktor-first-ci-2026-11-07.json).
Repository remains private; visibility question is pending. No unchanged rerun.
SDK observer design84f60d4d is pushed on its isolated Mobile branch; no observer code.
Mobile APK55 remains sole heavy job7746; original emulator STOPPED54, phone53.


## Latest Ktor acceptance — 7 November 2026, 17:54 UTC

Three common Kotlin source/test files add the optional Ktor3.3.3 module. Metadata
is bounded/redacted before retained records. Public send-pipeline phases surround
the engine so retries/redirects remain separate regardless of plugin install order;
response timing stops before validation/body consumption. No body reads, UI wait,
background job or transport policy change. Host closes the shared store explicitly.

Strict33192/strict-6 passed39 fresh adapter cases (13 each JVM/Android host/actual
Wasm browser) in51.993s on22 unchanged inputs. Verified42 core cases reuse earlier
same-source reports. AAR and Apple device/simulator klib compilation pass; no Apple
runtime test yet. All inspector jobs REAPED. Initial missing-checksum/lock failures,
ambiguous test alias and11 failing JVM cases are retained in the measurement.
URLBuilder default port0 needed resolution to the protocol port; a typed fixture
sentinel avoids coroutine stacktrace copies while testing original error propagation.
[Exact source/reports/artifacts](measurements/ktor-local-2026-11-07.json).

No library release, Matrix capture, viewer, body preview/export or app integration.
Private CI is billing-blocked; the question asking permission to make this new library
public remains unanswered. Do not change visibility without that answer. The next
implementation source push may have its own blocked first CI; never call it tested.

Mobile95e35b9d is accepted/pushed on recovery with offline authority renewal; primary
is3308 pending its original native CI. Sole heavy job7746 now builds Mobile55; do not
start any compiler/emulator alongside it. Original Bima emulator STOPPED54, phone53.
The new SDK observer has isolated source worktrees:
../.worktrees/mobile-matrix-http-observer-2026-11-07 (branch feat/matrix-http-observer-20261107)
and ../.worktrees/matrix-sdk-http-observer-2026-11-07 (SDK4 baseline reconstructed and
all19 patched hashes match, no observer code yet). Frozen SDK4 inventories are untouched.
Existing sections below retain earlier context; they are not active job status.


The metadata-only core is locally accepted in this commit. Five common Kotlin
source/test files, KMP Gradle scaffold, pinned wrapper/checksums, npm lock and first
portable/Apple CI are included. Core has14 actual passing cases on JVM,14 Android
host and14 Wasm browser, zero failures/errors/skips. First CI42394553310 on ac0fa6f failed before any portable/Apple steps ran. GitHub
reported failed account payments or an increased spending limit requirement. Zero
CI tests executed; declared/cross-compiled Apple targets are not runtime acceptance.
[First CI evidence](measurements/core-first-ci-2026-11-07.json). The user was asked
whether to make this new library public for free CI; the answer is still pending.
Do not change repository visibility without that answer or rerun unchanged blocked
CI. This documentation-only checkpoint skips CI; it does not change core acceptance.

Bootstrap56466 passed31.758s. First strict52497 failed before tests because default
Yarn setup tried adding a project repository under FAIL_ON_PROJECT_REPOS. Switched
to npm. Strict97570 ran all42 tests successfully but assemble lacked12 Android tool
verification components. Copied their exact previously verified hashes from Mobile;
strict58565 passed assembly21.065s on unchanged core, reusing those test reports.
All jobs are REAPED. No compiler or emulator is running. Exact evidence and original
failures are in docs/measurements/core-local-2026-11-07.json. No source tests rerun
merely to turn reused reports into new evidence. The final CI-only change selects
Xcode26.3; no Kotlin implementation change after acceptance.

No published artifact/release, Ktor adapter, Matrix Rust HTTP bridge, Compose viewer,
body capture, export or application integration yet. Add actual Ktor instrumentation
with bounded ingress and cancellation/streaming tests, then a state-driven viewer.
SDK4 has no FFI HTTP observer and is immutable: a real bridge needs a new separately
versioned SDK release, never a relabelled Ktor log. See design.md. The repository is
private; portable distribution to public consumer CI still needs a supported choice.

Parent HRIS Mobile recovery e936d052 records build54's failed device acceptance;
implementation700a8a21 is primary. Pending/draft previews survive cold offline restart,
but delivery does not resume after reconnection until foreground re-entry, recovery
shows a transient duplicate, and local save dismisses the keyboard. Original Bima
emulator is STOPPED with54 installed and both exact test sends retained. Do not reset,
restore a backup, re-enroll or recreate SDK keys/transaction IDs. Phone still53 and
must only receive validated builds with explicit serial and --install-only.

The original long-run communications/54-group PRD objective remains active. This
library supplements those fixes; it does not replace them. One heavy local job and
no compiler/emulator overlap. No proactive agent delegation is authorized.
