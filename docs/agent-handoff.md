# Inspector continuation

Updated 9 November 2026, 10:37 UTC. Historical checkpoints remain in
[Git at a6b8236](https://github.com/fajarxfce/kmp-network-inspector/blob/a6b8236/docs/agent-handoff.md).
The root feat/matrix-rust-bindings-20261107 checkout is now fast-forwarded to the
verified SDK6 source/docs. Mobile native86 completed before the original input freeze
was released; no active consumer inputs were changed during that proof.

## Verified source and consumer

- SDK/CI source742a2d1c2285c720cef80ea3913195f3ece388cc, evidencea6b8236.
  Public CI42591111996 passes226 portable/Android-host and69 actual Apple simulator
  cases; artifact digests and XML counts audited. Android native fixture compiled,
  not executed in that CI. [Evidence](measurements/published-sdk6-ci-success-2026-11-09.json).
- Uses published MatrixSDK26.10.23-hris.6 with immutable AAR/Ivy checksums; no adapter
  runtime changes. Public source resolution is accepted independently of prior local
  mirrors. Never replace the SDK release438835525 assets.
- Mobile2293ff05/APK86 has exact SDK6 native bytes, preserved account/key identity,
  four retained lifecycle phases and original messages. It passed strict traffic
  and paired-renewal gates and was installed without phone UI or data clearing.
  [Current Mobile checkpoint](https://github.com/fajarxfce/hris-mobile/blob/b0d4cd6e/docs/agent-handoff.md).
- Mobile consumer CI pins742a2d1 in nine jobs. First Apple job passes1777 tests plus
  Xcode build; Windows passes. Full Linux/web consumer CI remains in progress:
  cancellation-test finalization corrected after one observed race; a desktop abort
  remains unclassified with native diagnostics enabled. Web tracks resource usage.

## Working state and next actions

The isolated /home/fajar/hris/.worktrees/inspector-sdk6-2026-11-09 remainsa6b8236.
Its unrelated gradlew.bat line-ending change is NOT part of accepted work: preserve it.
An active Mobile CI-worktree build uses that isolated source; do not mutate it or start
another heavy compiler/emulator. The original checkout is no longer frozen atSDK5.
Check workspace owned-heavy-processes.py before any new heavy work; never kill live
API/worker JVMs. No local library compiler is running.

Finish Mobile consumer CI and then port reusable workflow integration/checksums to
Starter. Generic inspector behavior belongs here; HRIS identity/admission policy does
not. Native Apple Matrix binding, overhead measurement and published library binaries
remain open. Portable Apple tests do not imply native Matrix capture acceptance.
The user authorized atomic commits/pushes and the public repository. Capture remains
passive, bounded and redacted, with explicit host ownership and no auth/retry changes.
