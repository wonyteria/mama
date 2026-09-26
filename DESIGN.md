# MAMA Product Design Contract

Status: Canonical for the next implementation cycle  
Target release: Android 1.1 reliability rebuild  
Last updated: 2026-09-24

## 1. Source of truth

This file is the product and UX contract for MAMA. When an older README, mockup,
test, or handoff document conflicts with this file, this file wins. Existing
documents remain useful as history and supporting detail, especially
`android/docs/DEVIN_PRODUCT_DESIGN_V2.md`, but they are not release evidence.

Implementation is complete only when the behavior in this contract is covered by
automated tests and the release candidate has fresh device evidence.

## 2. Brand

**Product name:** MAMA  
**Assistant name:** 모모  
**Promise:** 아이에게 해당하는 준비물, 제출, 일정 변경을 놓치지 않게 해주는
가족용 실행 보조 앱.

MAMA is calm, factual, and persistent. It does not pretend to understand a notice
when evidence is weak. It does not present source installation as successful
connection. It never uses guilt, panic, or surveillance language.

## 3. Product goals

### Primary outcome

A parent can move from a real notice to a trustworthy action loop:

`supported source -> notice evidence -> child-specific action -> Todo -> reminder -> completion`

### Recovery-release goals

1. Make the existing notification path and one verified school-web path reliable.
2. Represent connection and extraction capability truthfully.
3. Prevent missed required work even when a deadline is absent.
4. Prevent duplicate Todos when the same notice arrives through multiple paths.
5. Preserve actions through snooze, revisions, restart, and source resync.
6. Ship only with repeatable build, test, and device-verification evidence.

### Explicit non-goals for 1.1

- A generic chatbot or open-ended conversation surface.
- Claiming support for every school, academy, or notice service.
- Server-side multi-family collaboration or payment.
- Silent hiding of original notifications before instrumentation tests prove safe
  behavior on a physical device.
- Pixel-polish that delays correctness and trustworthy status reporting.

## 4. Personas and jobs

### Primary persona: overloaded elementary-school parent

They receive fragmented notices while working, often through multiple channels.
Their job is not to read everything. Their job is to know what applies to their
child, what must be done, by when, and whether it is already complete.

### Secondary persona: co-caregiver

They need a clear, shareable understanding of an action, but account sync and
multi-user assignment are outside the 1.1 scope.

### Core jobs to be done

- “Show me only what needs attention today.”
- “Turn a required notice into something I cannot accidentally lose.”
- “Let me verify why MAMA created this action.”
- “Tell me honestly when a source is not supported or is temporarily uncertain.”

## 5. Information architecture

The root navigation remains intentionally small:

1. **오늘 (Today):** attention queue, status, and the next meaningful action.
2. **할 일 (Todo):** persistent required work, including undated work.
3. **소식 (News):** notice history and evidence, grouped across sources.

Connections and settings are secondary destinations, not root tabs. Chat is not
part of the product and unreachable legacy chat components must be removed.

### Today

Today contains, in order:

- urgent and overdue Todos;
- Todos due today;
- changed notices that affect an open Todo;
- source errors requiring user action;
- lower-priority upcoming items.

Empty state: “지금 확인할 일이 없어요.” It must not imply that every configured
source was successfully checked unless all relevant source runs are healthy.

### Todo

Todo sections are overdue, today, upcoming, and no date. A required action with
no reliable deadline belongs in **날짜 없음**, never in the discard path.

### News

News shows canonical notice groups, not raw transport events. A group may contain
an app-notification copy and a school-web copy of the same notice. The user sees
one item, the strongest available body/evidence, and source provenance.

## 6. Core domain model

### SourceService and SourceLane

The user configures one `SourceService` row per recognizable service or
institution. Internally it may contain multiple independent `SourceLane`s:

- Android notification listener;
- verified web listing/body adapter;
- public API adapter.

A preferred app mapping must not disable a verified web lane. Lanes run in
parallel and feed one visible service. Capability is explicit:

- `NOTIFICATION_CAPTURE`
- `LISTING_DISCOVERY`
- `BODY_EXTRACTION`
- `REVISION_DETECTION`

Status is also explicit: `READY`, `NEEDS_SETUP`, `UNSUPPORTED`,
`TEMPORARILY_UNCERTAIN`, or `ERROR`. “App installed” is not `READY`.

For 1.1, advertise only the capabilities actually implemented and tested. The
known school-web adapter may name only its verified institution. e알리미 DOM
capture remains `UNSUPPORTED` until a maintained contract and fixtures exist.

### NoticeGroup and NoticeRevision

Raw captures become revisions inside one canonical notice group. Cross-lane
deduplication uses the strongest available identifiers in this order:

1. official document ID or canonical URL;
2. institution + normalized external ID;
3. institution + normalized title + event/deadline date + stable body fingerprint.

Title alone is never enough. Source item ID alone cannot deduplicate across
lanes. The model preserves every source reference for evidence and debugging.

### CandidateAction, Todo, and evidence

An action becomes a Todo when all are true:

1. it applies to the configured child/audience, or the audience is explicitly
   confirmed by the user;
2. it is required rather than merely informative;
3. the supporting text span is retained as evidence.

Deadline is optional. Required undated work becomes an undated Todo. If confidence
is below the safe threshold, show a suggestion that requires confirmation instead
of silently creating a Todo.

A Todo stores the originating notice group, revision, evidence span, action type,
optional deadline, and lifecycle state. Notice updates amend the open Todo and
show what changed; they do not create a duplicate. Completion is user-owned and
is not silently reversed by resync.

### Reminder occurrence

Snooze schedules a future occurrence; it does not weaken or expire the Todo.
There is no fixed three-snooze cutoff. Disturbance controls may limit notification
frequency, but the action remains active until completion, dismissal, or an
explicitly valid cancellation from a newer notice revision.

## 7. Design principles

1. **Truth before coverage.** Unsupported is better than falsely connected.
2. **Evidence before automation.** Every generated action links back to text.
3. **Persistence before cleverness.** Required work survives missing dates,
   snoozes, restarts, and resync.
4. **One service, many lanes.** Transport details do not create duplicate UX.
5. **Recoverable actions.** Completion, dismissal, and deletion offer undo.
6. **Quiet by default.** Escalation reflects urgency and user intent, not arbitrary
   retry counts.

## 8. Visual language

MAMA uses a warm, low-noise system rather than a conversational mascot UI.

- Backgrounds: neutral warm surfaces with strong text contrast.
- Primary accent: calm green or teal for safe/complete states.
- Warning: amber for attention; red only for overdue, destructive, or true errors.
- Typography: system Korean sans serif; body text at least 16sp; no critical
  information encoded only by weight or color.
- Cards: one action per card, short title, concrete next step, due/status line,
  and evidence affordance.
- 모모 may appear in short explanatory copy, never as decoration that competes
  with the action.

## 9. Components

### Action card

Required fields: action, child/audience, deadline or “날짜 없음”, source label,
status, and completion control. Secondary actions: evidence, snooze, dismiss.

### Evidence sheet

Shows the exact supporting notice excerpt, notice title, institution/service,
capture time, and all source lanes. It distinguishes extracted fact from MAMA's
interpretation.

### Source service row

Shows a human-readable service/institution name, capability badges, last checked
time, and honest status. Internal `sourceId`, package name, or adapter key is
diagnostic data and must not appear in normal user copy.

### Status banner

Used only when user action or data trust is affected. It states what failed, what
still works, and the next available action.

### Revision diff

For changes affecting an open Todo, show a plain-language before/after summary
and retain the original evidence.

## 10. Accessibility

- All interactive targets are at least 48dp.
- TalkBack labels describe action and state, not icon names.
- Color contrast meets WCAG AA; state is also conveyed with text/iconography.
- Font scaling to 200% must not hide the primary action or completion control.
- Focus order follows visual priority; sheets return focus to their invoker.
- Notifications avoid exposing child, school, or task details on the lock screen
  unless the user explicitly opts into detailed previews.

## 11. Responsive behavior

Primary target is portrait Android phones from 360dp width. Content uses a single
column and remains usable with large text. On wider screens, constrain the reading
column rather than stretching action cards edge to edge. Landscape must preserve
completion and evidence controls without horizontal scrolling.

## 12. Interaction states

Every source and action surface implements loading, empty, success, partial,
offline, permission-required, unsupported, and error states.

- Loading never replaces already-known content with a blank screen.
- Partial means some lanes succeeded and others failed; show both facts.
- Retry is idempotent.
- Completion, dismissal, and deletion provide undo.
- A sync revision cannot resurrect a user-completed Todo.
- Original-notification hiding defaults to OFF for 1.1. It may be exposed later
  only after tests prove capture, replacement delivery, consent revocation,
  process death, and failure fallback on a physical device.

## 13. Content voice

Use short, literal Korean sentences.

- Good: “제출 날짜를 찾지 못했어요. 날짜 없는 할 일로 저장했어요.”
- Good: “학교 웹 확인은 지원돼요. e알리미 본문 확인은 아직 지원하지 않아요.”
- Good: “알림 접근 권한이 없어 새 알림을 확인하지 못했어요.”
- Avoid: “모모가 알아서 완벽하게 챙겨드릴게요.”
- Avoid internal identifiers, model confidence decimals, and implementation terms.

## 14. Implementation constraints

### P0: correctness and release safety

1. Keep one visible source service while allowing parallel internal lanes.
2. Replace source-item-only identity with canonical cross-lane notice grouping.
3. Preserve required actions without deadlines as undated Todos.
4. Remove the fixed snooze cutoff and test at least four consecutive future
   snoozes plus process restart.
5. Remove internal source IDs from normal UI copy.
6. Default original-notification hiding OFF; retain an explicit feature boundary
   until physical-device coverage exists.
7. Remove unreachable legacy chat/agent entry points.
8. Release signing must fail closed when production signing material is absent.
9. Do not ship a production NEIS secret in `BuildConfig`; use a proxy or disable
   that production lane until a secure boundary exists.
10. Add CI for unit tests, lint, and release assembly checks.

### P1: coherent user experience

Implement the IA, capability/status language, evidence sheet, undated section,
revision handling, and accessible interaction states described above. Reuse the
current Compose/Room/WorkManager/Kotlin-coroutines stack. Add no new dependency
unless the current stack cannot satisfy a documented requirement.

### P2: validation

Create fresh evidence on the current release candidate:

- automated unit and instrumentation results;
- release build result with safe signing behavior;
- physical-device walkthrough for notification capture, permission removal,
  reboot/process death, snooze, completion, and lock-screen privacy;
- at least five parent usability sessions using their own real notice flow.

Do not claim product-market fit, broad source coverage, or production readiness
from unit tests alone.

## 15. Release acceptance criteria

The 1.1 recovery release is acceptable only when all of the following pass:

1. A supported notification produces one notice group and, when required, one
   evidence-linked Todo.
2. The same notice arriving through app and web remains one notice group and one
   Todo while preserving both source references.
3. Two genuinely different notices with the same title remain separate.
4. A required notice with no deadline creates a Todo in “날짜 없음”.
5. A non-required informational notice never silently creates a Todo.
6. A low-confidence audience/action requires confirmation.
7. A newer revision updates an open Todo without duplicating it and shows what
   changed.
8. Resync never reopens a completed Todo.
9. Four consecutive snoozes remain scheduled correctly across process restart.
10. Revoking consent or notification access prevents further capture and removes
    stale work according to the existing consent/tombstone contract.
11. A configured service reports each lane's real capability and health; an
    installed app alone is never displayed as connected.
12. No internal source ID or package name appears in normal user-facing copy.
13. Detailed lock-screen content is hidden by default.
14. Unit tests, lint, instrumentation tests, and debug build pass in CI.
15. Production assembly fails clearly without release signing material and no
    production API secret is embedded in the APK.
16. A physical-device test proves notification capture and all failure fallbacks;
    original-notification hiding remains off until its separate safety suite passes.

## 16. Open questions

- Which institution beyond the currently verified school is the next supported
  web adapter, and who owns fixture maintenance?
- What confidence thresholds should create automatically versus ask for
  confirmation? These require real-notice evaluation, not intuition.
- Should detailed notification previews be an account-wide or per-source setting?
- What retention window best balances debugging, privacy, and parent trust?
- Which five parent workflows will be used as the 1.1 validation cohort?

These questions do not block P0 correctness work. They must be resolved before
claiming broader coverage or production readiness.

