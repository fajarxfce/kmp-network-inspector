# Repository instructions

Read README.md, docs/design.md and docs/agent-handoff.md before implementation.
Keep accepted work, exact verification, limitations and next steps in Git.

- This is a reusable library. No HRIS business policy, endpoints, credentials or app state.
- Keep core models/store, transport adapters and optional Compose UI in separate modules.
- Capture is observation only: never modify auth, retries, delivery, TLS or response semantics.
- Bound retained events, queued work, fields and body previews before allocation/retention.
- Redact credentials and private key material before buffering. Never print raw failures/bodies.
- The host owns every scope/listener/native handle. Closing must release callbacks and jobs.
- Preserve cancellation and distinguish pending, response, transport failure and incomplete capture.
- Use a version catalog and explicit Kotlin Multiplatform targets. Do not claim an untested target.
- Test malformed/large inputs, streaming, concurrency, disposal and drop behavior where affected.
- Keep the UI state-driven and reusable; no DI/service locator or transport calls in composables.
- Atomic implementation/test/docs commits, no co-author trailers. Push each accepted commit.
- HRIS Mobile validates the consumer first; port reusable integration to the starter afterward.
- Coordinate heavy builds with the parent workspace: one heavy job, no emulator/compiler overlap.
