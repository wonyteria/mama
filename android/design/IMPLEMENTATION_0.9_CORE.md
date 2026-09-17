# 0.9 core source sync implementation notes

Status: core A/D implementation in progress. Gradle has not been run from this lane because public fetcher and document extraction lanes are still active.

## Source audience contract

`SourceAudienceFact.applicability` stores what the source declared about the fact range, not the current child’s final result.

- `APPLIES` with `gradeStart..gradeEnd` means that range is included by the source.
- `INELIGIBLE` with `gradeStart..gradeEnd` means that range is excluded by the source.
- `UNKNOWN` means the source had a fact for that range or scope but could not prove inclusion or exclusion.
- `schoolWide=true` is only for explicit whole-school evidence.

Current-child matching is derived by `SourceAudienceEvaluator`. A positive 5–6 range makes elementary grade 2 ineligible. A negative grade 6 fact alone does not prove elementary grade 2 is included or excluded. If facts contain a current-child `UNKNOWN`, child-specific agenda/action selection fails closed.

Adapters should preserve all declared grade flags they can verify. For NEIS this means emitting each known grade flag as a typed fact, not reducing the response to the currently configured child grade. For school HTML this means explicit `초등 5~6학년` is an `APPLIES` range and the grade-2 exclusion is derived later.

## Core fixes covered

- `SourceScopeFactory` now validates the exact official 성남정자초등학교 elementary grade range 1–6 and requires connected NEIS metadata.
- `SourceSyncWorker` waits for repository readiness, revalidates source scope inside the per-source mutex, and uses strict state-store generation checks to reject late reset/disconnect commits.
- `SourceSyncStateStore` exposes `snapshotsFlow`, fails closed on encrypted state read failures for mutations, and only updates success/checkpoint for verified committable results.
- `ProbeRepository.ingestSource` rechecks source generation and child/school scope, keeps duplicate source item revisions idempotent, preserves first-seen time across revisions, tombstones deleted source items, and validates item provenance.
- Home, chat, briefing, and source agenda now route source records through `DefaultRecordSourcePolicy` and `SourceRecordSelectors`.
- Source agenda stores every verified event date, omits incomplete optional opportunities, and no longer falls back to transient NEIS memory as a separate truth.
- Source reset/disconnect/school change cancels unique WorkManager runs and suspends active automatic tasks derived from affected source records.
- The debug-only `.qa` validation activity can initialize a sanitized grade-2 profile, enqueue the production worker, and report source state/record/agenda/chat/briefing counts.

## Test additions

- `SourceAudienceEvaluatorTest` documents declared-range re-evaluation.
- `SourceIngestFlowTest` covers the shared `SourceSyncRunner` fetch→ingest→snapshot path, idempotent duplicate ingest, deleted-item tombstones, changed revision firstSeen retention, source-generation hiding, no-notification-permission source agenda/chat/briefing, empty partial no-success, late state result rejection, and corrupt encrypted state fail-closed behavior.
- `SourceRecordSelectorsTest`, `NoticeDecisionSourceMetadataTest`, `ProbeScreensRenderTest`, and `AssistantTaskStoreTest` cover multi-date agenda, grade-change re-evaluation, incomplete optional content, typed/body conflicts, home SSOT, and automatic-task suspension.
