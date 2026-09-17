# 0.9 Public Source Fetch Implementation

Status: public fetch lane implemented against `sync/SourceSyncModels.kt`. This document records the public-source scope only: NEIS public schedule and the 성남정자초 official website. It does not cover DB ingest, UI, WorkManager scheduling, e알리미 authenticated fetch, build output, or device QA.

## Implemented Files

- `app/src/main/java/kr/mom/probe/connector/NeisPublicClient.kt`
  - Keeps the existing `findExactSchool` and `upcomingEvents` API for current UI callers.
  - Implements `SourceFetcher` for `SourceIds.NEIS_PUBLIC`.
  - Returns `PARTIAL` with `MISSING_API_KEY` and `SAMPLE_LIMITED` when `NEIS_API_KEY` is empty, instead of storing sample/no-key data as a complete school schedule.
  - In keyed mode, pages `SchoolSchedule` by `pIndex` up to shared limits, checks top-level/head result codes together, validates row date/scope/title fields, distinguishes `SUCCESS_EMPTY`, `ERROR`, and `PARTIAL`, and emits `FetchedNotice` schedule items with `EVENT` date facts.
  - Existing `findExactSchool` and `upcomingEvents` compatibility paths now fail on API error/non-row JSON instead of returning false empty success or ambiguous fallback rows.
  - If a later NEIS page reports `INFO-200` after earlier rows, if `INFO-200` appears with rows/count, if `list_total_count=0` appears with rows, or if `list_total_count` is missing/changes, the sync result stays `PARTIAL`/`ERROR` instead of `SUCCESS_EMPTY` or complete.
  - Duplicate rows across NEIS pages are treated as schema drift and do not inflate completion counts.
  - Requires official school/office fields on schedule rows so blank scope keys do not become verified records.
  - Preserves unknown grade-flag schema as `NoticeApplicability.UNKNOWN` when official fields are absent or unrecognized.
  - Preserves observed NEIS grade flags as typed per-grade `SourceAudienceFact` ranges so future grade changes can be reevaluated from stored facts instead of reparsing evidence text.
  - Uses bounded stream reads without Java 9-only `InputStream.readNBytes`, and keyed fetch run budgets count actual response bytes.

- `app/src/main/java/kr/mom/probe/connector/SchoolWebsiteParser.kt`
  - Parses only the observed 성남정자초 HTML contract:
    - list: `a.nttInfoBtn[data-id]` inside rows, sibling published date cells, `#srchForm`, `#pagingForm`
    - detail: `#nttViewForm th.title` and balanced `tr.cont`
  - Uses a balanced `<tr>` scan for detail bodies so nested tables do not truncate content at the first inner `</tr>`.
  - Restricts detail title/body parsing to the balanced `#nttViewForm` form and verifies hidden `mi`/`bbsId`/`nttSn` metadata before a body can be used for the requested item.
  - Normalizes stable item IDs from `data-id`/`nttSn` and canonical detail URLs from `(mi, bbsId, nttSn)`.
  - Preserves official DEXT server metadata and `/upload/snjj-e/...` source paths as `LINK_ONLY` and records unsupported attachment issues.
  - Does not claim any DEXT handler/POST endpoint is app-usable; it only preserves literal source paths found in the school detail HTML.
  - Emits typed audience, date, obligation, attachment, origin, evidence, and revision-hash fields for shared ingestion.
  - Keeps missing audience evidence as `UNKNOWN`; it only emits school-wide `APPLIES` when the page contains explicit whole-school evidence.
  - Parses elementary and middle grade groups separately so `초등5~6학년, 중1~3학년` does not apply to an elementary 2nd-grade scope.
  - Keeps negated wording such as “제출할 필요가 없습니다” informational instead of promoting it to a required action.

- `app/src/main/java/kr/mom/probe/connector/SchoolWebsiteClient.kt`
  - Implements `SourceFetcher` for `SourceIds.SCHOOL_WEBSITE`.
  - Fetches the three approved boards only:
    - 공지사항: `bbsId=12354`, `mi=14296`
    - 가정통신문: `bbsId=12359`, `mi=14305`
    - 공지사항(초1~2 맞춤형, 수익자 방과후): `bbsId=12570`, `mi=14704`
  - Uses GET for page 1 and POST form fields for later pages: `currPage`, `listCo`, `bbsId`, `mi`, `searchType`, `searchValue`.
  - Enforces shared response/run/page/detail limits and returns `PARTIAL` with cursor/issues rather than claiming completion on truncation, schema drift, page mismatch, or attachment-only content.
  - Validates returned page number and total page metadata before fetching detail bodies from that list page.
  - Merges item-level detail issues into the batch result so attachment-only or incomplete details cannot produce top-level `FETCHED complete=true`.
  - Uses `SourceCheckpoint.cursor` as `boardId:page` to resume bounded page windows instead of rereading only the first 30 details forever; when resuming a deeper page, it first rechecks that board's page 1 so fresh revisions are not skipped.
  - Accepts a resume cursor only when checkpoint `sourceGeneration`, date coverage window, and prior page coverage match the current request.
  - Requires exact validated school scope: `성남정자초등학교`/`성남정자초`, host `snjj-e.goesn.kr`, default/443 HTTPS port, path `/snjj-e/`.
  - Limits normal dated items to the recent 14-day window, rejects future-dated rows for the current run, and keeps pinned items only when their publication date is in the current academic year.
  - Downloads attachment bytes only when the URL came from the verified detail source and matches `https://snjj-e.goesn.kr/upload/snjj-e/na/bbs_<same board>/...` with no query/fragment, default/443 HTTPS port, and `.hwp`/`.hwpx` extension.
  - Attachment downloads do not follow redirects, share the public response byte cap, are admitted against the remaining run byte budget before each request, and must pass HWP OLE or HWPX ZIP magic before text extraction.
  - Attachment fetch/extract checks coroutine cancellation before requests, after responses, during response chunks, and around extraction. Once the remaining byte budget/deadline is exhausted or a response exceeds its admitted cap, no further attachment request is made for that detail.
  - Calls `DocumentTextExtractor.extract(bytes, filename, mimeType)` for verified HWP/HWPX bytes. Extracted text is merged into the fetched notice body so audience/date facts are rederived from it, while `attachmentText:*` / `attachmentCompleteness:*` evidence and extractor issues preserve whether the document was partial or unsupported.
  - If extracted attachment text is partial, or if an empty-HTML notice has one readable attachment and another expected same-board HWP/HWPX attachment unread, the notice content state is `PARTIAL_EXTRACTION`; downstream code must not treat it as fully verified or create automatic actions from partial/truncated text.

- `app/src/main/java/kr/mom/probe/connector/PublicSourceFetchers.kt`
  - Provides the public-lane factory/registration hook:
    `PublicSourceFetchers.registerDefaults()`.
  - Registers `NeisPublicClient()` for `SourceIds.NEIS_PUBLIC` and `SchoolWebsiteClient()` for `SourceIds.SCHOOL_WEBSITE`.
  - App-start invocation is left to the sync-core owner because `ProbeApplication`/worker wiring is outside this lane.

- `app/src/test/resources/schoolweb/*.html`
  - Public, unauthenticated fixture snapshots:
    - `family-list-page1.html`
    - `family-list-page2-post.html`
    - `family-detail-1953062-grade6.html`
    - `family-detail-1951993-parent-day.html`

- `app/src/test/java/kr/mom/probe/connector/*Test.kt`
  - Parser tests for list selectors, POST pagination fixture, nested detail body preservation, 6th-grade exclusion for a 2nd-grade scope, HWPX link-only attachment state, the HWP-only parent participation notice, mixed elementary/middle grade groups, and negated required-keyword wording.
  - Client tests for page-number mismatch producing `PARTIAL`, cursor resume with page-1 reconciliation, stale checkpoint ignored, page-limit truncation producing `PARTIAL` plus cursor, synthetic HWP attachment text integration, mixed readable/unread attachments staying partial, remaining-byte-budget call-count stopping, and an optional real-file HWP fixture check from `device-qa/public-fixtures/parent-class-notice-1951993.hwp`.
  - NEIS tests for missing-key `PARTIAL`, unknown grade flag preservation, and per-grade typed flag preservation for future reevaluation.

## Live Public Evidence

The public school HTML paths were fetched without credentials on 2026-09-15:

- `https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttList.do?bbsId=12359&mi=14305`
  - HTTP 200, 48,865 chars.
  - Observed `a.nttInfoBtn[data-id]`, `#srchForm`, `#pagingForm`.
  - List showed `전체 427 건`, `1/43페이지`.

- POST `https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttList.do`
  - Form included `currPage=2`, `listCo=10`, `bbsId=12359`, `mi=14305`.
  - HTTP 200, 49,155 chars.
  - Returned a different first row (`nttSn=1908174`), validating that page changes must be checked from returned content.

- `https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttInfo.do?mi=14305&bbsId=12359&nttSn=1953062`
  - HTTP 200, 93,601 chars.
  - Observed `#nttViewForm`, `th.title`, `tr.cont`, and HWPX attachment script.
  - Title: `2026학년도 6학년 1일형 현장체험학습 스쿨뱅킹 안내장`.
  - Body includes 6th-grade participation fee, payment period, and supplies. For an elementary 2nd-grade scope, parser emits `INELIGIBLE`.
  - Attachment metadata/source path is preserved as link-only; HWPX is not parsed or auto-downloaded.

- `https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttInfo.do?mi=14305&bbsId=12359&nttSn=1951993`
  - HTTP 200, 47,656 chars.
  - Observed `#nttViewForm`, `th.title`, and an effectively empty `tr.cont`.
  - Title: `2026 학부모 수업 참여의 날 안내`.
  - The usable content is HWP-only (`가정통신문(2026).hwp`, 265,216 bytes in DEXT metadata). The literal DEXT upload source resolves to `https://snjj-e.goesn.kr/upload/snjj-e/na/bbs_12359/2026/09/637E0D84-5917-9208-3DEF-475796380F5D.hwp`.
  - The local non-app fixture `device-qa/public-fixtures/parent-class-notice-1951993.hwp` is 265,216 bytes with SHA-256 `0CAAADECFCB2FAF9439EB8EE4E158C01497540C7948673BD42093EC629F2C77B`, matching root's browser/UI download evidence. It is used only by the optional integration test and is not bundled under app test resources.
  - Document extraction for this HWP is expected to be `PARTIAL` because embedded binary/image content is skipped, while text can still provide typed audience/date facts such as the 2nd-grade target.

## Explicit Non-Claims

- RSS was not retried or used.
- Blocked JavaScript assets were not downloaded.
- No NEIS no-key full query was treated as complete.
- e알리미 authenticated collection is outside this lane.
- Attachments are not parsed through generic download/OCR paths.
- Only verified same-board public HWP/HWPX upload paths are downloaded and passed to the bounded document extractor; PDF/image and unverified links remain link-only or unsupported.
- No DB, UI, WorkManager, ADB, or release build changes were made in this lane.

## Verification Status

Source-level implementation and fixture tests were added. Gradle/build/device verification was intentionally not run in this lane because root/core reserved Gradle ownership for 0.9 coordination.
