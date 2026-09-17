# 0.7 Agent implementation notes

Date: 2026-09-15

This implementation delivers the bounded 0.7 local-agent slice for the Android app. It keeps notice analysis on-device and rule-based, does not add dependencies, does not connect OCR or AI APIs, and does not perform phone actions outside local app state.

## Implemented behavior

- Added a shared `NoticeDecision` model and `NoticeDecisionEngine` for planner, urgent notifier, home, briefing, and local chat consumers. The model carries source identity, source revision, content state, applicability, obligation, date facts, admission policy, action, issues, and evidence.
- Added safe child school-level handling. A bare grade no longer means elementary by default, and middle-school grade 1 is not treated as elementary grade 1.
- Suppressed optional school programs and ineligible grade ranges from required/actionable consumers. Optional opportunities remain separate and low priority.
- Blocked automatic task creation for partial/truncated notices and attachment-missing notices. Manual attachment transcription appears only in tests; runtime does not upgrade trust based on marker text.
- Removed automatic deadline invention from first dates, date-only expressions, implicit 23:59, and year rollover. Dotted Korean dates such as `2026.9.28.(월)17:00` are parsed, and mismatched explicit weekdays preserve the source date text without creating a precise deadline.
- Corrected the attachment case admission policy to `FIRST_COME_FIRST_SERVED` for the actual red text. Separate tests cover `선착순이 아닙니다. 추첨으로 선정합니다.` as lottery.
- Made automatic notice tasks revision-aware. A changed automatic source updates the existing automatic task in place; non-actionable revisions suspend the automatic task without marking it completed; user-confirmed and completed tasks are preserved.
- Changed urgent alerts to fire only when a required, applicable, precise deadline would occur before the next real briefing. Successful alerts store a digest fingerprint of source/action/due time, so whitespace-only revisions do not repush; deadline changes can repush. Stale posted urgent notifications are cancelled when a source is no longer urgent, while duplicate history is preserved.
- Fixed briefing seen handling to mark only displayed revision IDs after task loading succeeds. Hidden fourth notices are not swallowed, and newer revisions of the same source can become unseen again.
- Shared briefing task projection across receiver, activity, and home counts so suspended, completed, expired, and distant tasks are not counted inconsistently.
- Updated local agent save behavior: clear save commands auto-save with undo; ordinary time phrases create due times but no push reminder; only explicit “N시에 알려줘” reminder commands create an individual reminder. Lookup phrases such as “내일 준비물 알려줘” and “최근 알림 알려줘” answer without persisting.
- Updated status UX copy to state `AI 분석 미연결 · 이 기기에서 기본 정리`, and avoided implying that a generic ChatGPT login grants app API access.
- Updated home UI to show actionable item details directly: items, due/source text, and source label. The fixed “today” label was changed to “곧 챙길 일”.
- Updated `scripts/build.ps1` to restore `JAVA_TOOL_OPTIONS` and pass Gradle `-D`/`-P` arguments safely through an argument array.
- Updated app version to `versionCode = 7`, `versionName = "0.7.0-agent"`.

## Review findings response

- R1 briefing watermark: closed by revision-ID seen storage and displayed-row-only markSeen.
- R2 briefing dedup/completed-source suppression: closed by source-linked filtering plus shared task projection.
- R3 automatic task stale revisions: closed by in-place automatic updates and separate `suspended` state.
- R4 optional application deadlines: closed by removing `신청+마감` as required without explicit obligation.
- R5 partial/attachment missing auto gate: closed by blocking parser/planner actions and suspending prior automatic tasks on incomplete revisions.
- R6 urgent alert schedule: closed by comparing against actual next briefing and digesting successful alert fingerprints.
- R7 local command/reminder boundary: closed by undo auto-save and explicit-time reminder gating.
- R8 date/admission parsing: closed for dotted Korean datetime, weekday mismatch, and negative first-come text.
- R9 evidence quotes: closed by keeping `quote` as raw source spans or empty strings; generated explanations stay in reason fields.
- R10 visual/copy polish: closed by shorter CTAs, direct item/due/source display, and status copy updates.

## Validation

All Gradle commands used:

```powershell
$env:JAVA_HOME='D:/Codex/.toolchains/mom-android/java/jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:/Codex/.toolchains/mom-android/sdk'
$env:GRADLE_USER_HOME='D:/Codex/.toolchains/mom-android/gradle-user'
$env:JAVA_TOOL_OPTIONS='-Duser.home=D:/Codex/mom-agent/android/.build-user'
& 'D:/Codex/.toolchains/mom-android/gradle-8.13/bin/gradle.bat' --project-dir . --no-daemon --console=plain -Dorg.gradle.native=false -Pkotlin.compiler.execution.strategy=in-process --no-parallel --max-workers=1 <task>
```

Results:

- `testDebugUnitTest`: passed, 83 tests, 0 failures, 0 errors, 0 skipped.
- `assembleDebug`: passed.
- `assembleRelease`: passed.
- `lintDebug`: passed.

Generated APKs:

- `D:\Codex\mom-agent\android\app\build\outputs\apk\debug\app-debug.apk`
- `D:\Codex\mom-agent\android\app\build\outputs\apk\release\app-release.apk`

Generated screenshot artifacts:

- `D:\Codex\mom-agent\android\app\build\reports\screenshots\child-small-large-text.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\child.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\detail-agent-action.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\home-agent-bento.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\home-agent-large-text.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\home-setup.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\sources-fixture.png`
- `D:\Codex\mom-agent\android\app\build\reports\screenshots\welcome.png`

## Limitations

- Runtime image OCR is not implemented. The attachment case is represented only as a manually transcribed synthetic test fixture.
- No external AI/API connector is connected for private notification text. No private notice content is sent outbound by this build.
- Attendance app collection and media/photo/video grouping are not implemented in this slice.
- Personal web/parent account connector automation is not implemented.
- Actual device QA was not run in this implementation lane because the phone was offline during final PC verification.

## Changed files

- `D:\Codex\mom-agent\android\app\build.gradle.kts`
- `D:\Codex\mom-agent\android\scripts\build.ps1`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\MainActivity.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\agent\AgentActivity.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\agent\LocalAgentEngine.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\data\NoticeDecision.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\data\NotificationCandidateParser.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\data\ProbeModels.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\data\ProbeRepository.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\reminder\AssistantAlertNotifier.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\reminder\BriefingActivity.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\reminder\BriefingReminders.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\task\AssistantTasksActivity.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\task\AssistantTaskStore.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\task\CandidateActionPlanner.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\task\TaskReminderScheduler.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\ui\ConnectionsScreen.kt`
- `D:\Codex\mom-agent\android\app\src\main\java\kr\mom\probe\ui\ProbeScreens.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\agent\LocalAgentEngineTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\data\NoticeDecisionEngineTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\data\NotificationCandidateParserTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\reminder\AssistantAlertNotifierTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\reminder\BriefingRemindersTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\task\AssistantTaskStoreTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\task\CandidateActionPlannerTest.kt`
- `D:\Codex\mom-agent\android\app\src\test\java\kr\mom\probe\ui\ProbeScreensRenderTest.kt`
- `D:\Codex\mom-agent\android\design\IMPLEMENTATION_0.7.md`
