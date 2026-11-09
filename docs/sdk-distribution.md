# Published Matrix dependency

The Android adapter pins SDK26.10.23-hris.6. Its four ABI binaries and Kotlin
bindings come from one immutable release,438835525. Strict dependency verification
checks the AAR and both Gradle Ivy descriptor names against the accepted digests.
The default repository is public; hosts do not need credentials or a local mirror.
The SDK was exercised by the Mobile consumer before this catalog update. This
change neither modifies the observer API nor imports application retry/auth policy.

The portable CI job now also runs the13 Android host adapter cases and compiles
its native instrumentation fixture. Report auditing rejects a missing, empty,
skipped or failed adapter suite. Compiling that fixture does not execute JNI;
actual Android consumer/native evidence is recorded separately. The manual workflow
entry point lets a pinned source revision run both portable and Apple jobs.

The inspector remains0.1.0-SNAPSHOT as a source dependency, without a Maven release.
Applications should pin a verified repository revision when using a composite build.
Apple portable simulator tests do not establish an Apple Matrix native binding.
Independent capture overhead measurements and binary publication remain open.

XML, workflow structure and checksum provenance are verified; actual hosted
source/target validation is pending. See the [checkpoint](measurements/published-sdk6-ci-2026-11-09.json).
Keep the original root
inspector checkout unchanged while Mobile diagnostic85 is being validated.
