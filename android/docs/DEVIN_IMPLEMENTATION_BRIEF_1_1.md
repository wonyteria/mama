# Devin Implementation Brief — MAMA Android 1.1 Reliability Rebuild

`DESIGN.md` at repository root is the canonical product contract. Implement and
verify the P0 recovery slice before cosmetic work.

## Objective

Make the existing Android app complete one trustworthy flow from supported source
to evidence-backed Todo, without duplicates or silent data loss. Keep the current
Compose, Room, WorkManager, coroutines, and Keystore architecture unless a change
is required by a failing acceptance criterion.

## Required work, in order

1. Add characterization tests for current source-lane selection, notice identity,
   Todo creation/reconciliation, snooze, completion, and consent behavior.
2. Model one visible source service with multiple parallel internal lanes. A
   preferred notification-app mapping must not disable a verified web lane.
3. Add canonical cross-lane notice grouping. Prefer official IDs/URLs, otherwise
   use institution plus stable external ID or a conservative content fingerprint.
   Do not deduplicate on title alone.
4. Create required undated Todos instead of dropping candidate actions whose
   `dueAt` is null. Update the old test that encodes the discarded behavior.
5. Remove the three-snooze/60-minute cutoff. Verify four future snoozes and
   process-restart persistence without expiring the Todo.
6. Remove internal `sourceId` and package/adapter keys from normal user copy.
7. Default original-notification hiding OFF. Preserve the boundary but do not
   enable it by default until an instrumentation and physical-device safety suite
   proves replacement delivery and failure fallback.
8. Remove unreachable legacy chat/AgentActivity entry points.
9. Make production signing fail closed when release credentials are absent. Do
   not fall back to debug signing.
10. Remove production `NEIS_API_KEY` from the shipped APK boundary. If no secure
    proxy exists in this repository, disable that production lane with honest UI
    status and document the server-side follow-up.
11. Add CI that runs unit tests, lint, instrumentation-capable checks where
    available, and a release-assembly safety check.
12. Update version/docs only after behavior and evidence agree.

## Known starting points

- `CandidateActionPlanner.kt` currently returns no action when `dueAt` is null.
- `AssistantTaskStore.kt` currently enforces a fixed snooze limit.
- `SourceSyncScheduler.kt` and connector models conflate preferred app mapping
  with web-lane disablement.
- `ProbeRules.recordIdentity` and task reconciliation are source-item-specific,
  so the same notice can duplicate across lanes.
- `ConnectionsScreen.kt` exposes internal source IDs.
- `android/app/build.gradle.kts` can fall back to debug signing and compiles a
  NEIS key into `BuildConfig`.
- `AgentActivity` is legacy and not part of the current Today/Todo/News product.
- e알리미 DOM capture is not supported; do not fake support.

Confirm exact locations from the current tree before editing; do not assume line
numbers from older reviews are stable.

## Delivery rules

- Work on a dedicated branch.
- Keep changes reviewable and avoid unrelated refactors or new dependencies.
- Run targeted tests after each behavior change, then the full unit suite, lint,
  and build checks.
- Add migration coverage for every Room schema change.
- Preserve encrypted storage, backup-disabled, consent epoch/generation,
  tombstone, and uncertainty behavior unless a test proves a defect.
- Record commands and exact results. Never describe an unrun device scenario as
  verified.
- Open a pull request only when the automated suite passes. In the PR, map every
  `DESIGN.md` acceptance criterion to a test or an explicit remaining manual
  device check.

## Stop condition

Stop and report a blocker rather than simulating support if a source requires
credentials, private fixtures, or an undocumented DOM contract. The implementation
is successful when all automatable criteria in `DESIGN.md` pass and the remaining
physical-device/manual checks are listed precisely.

