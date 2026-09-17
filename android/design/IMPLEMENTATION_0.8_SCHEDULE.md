# 0.8 일정·외부 알람 명령 구현 기록

상태: schedule/calendar lane source frozen after S1~S10, 2차 B-findings, 3차 D1-D6 repairs, E1 encrypted restoration, and F1 durable schedule authorization token. Final 0.8.1 source frozen after device QA feedback, TaskStore null-ID UI-lane fix, calendar tombstone fix, and full shared Gradle sequence.

## 구현 범위

- `agent/ScheduleCommandModels.kt`, `agent/ScheduleCommandParser.kt`를 추가해 현재 사용자가 직접 입력한 명시적 생성 명령만 typed `CalendarCreateCommand`, `AlarmRequestCommand`, `ScheduleClarification`, `ScheduleNoMutation`으로 분리했다.
- `LocalAgentEngine`은 일정·알람 명령을 기존 로컬 부탁 저장보다 먼저 판별한다. 조회·부정·인용·설명 문장은 mutation으로 승격하지 않는다.
- `calendar/CalendarPreferences.kt`는 CalendarProvider 저장 대상 계정/캘린더와 외부 입력 화면 앱 handler를 별도 암호화 preference로 저장한다. 기본 종료 시간은 60분이며 30/60/90분 UI 선택을 제공한다.
- `calendar/CalendarCommandStore.kt`는 request id, 정규화 payload, 목적지 snapshot, provider event id, readback snapshot, 상태를 Android Keystore 암호화 SharedPreferences에 기록한다. 기록 복호화/JSON 오류는 fail-closed로 처리해 새 insert를 막고, unresolved records는 eviction하지 않는다.
- calendar journal claim은 request id 단위로 원자화했다. 같은 request id와 같은 payload의 동시 호출은 한 caller만 insert 권한을 얻고, 나머지는 기존 PREPARED/UNCERTAIN/SAVED 상태를 조회한다. PREPARED observer는 journal을 UNCERTAIN으로 덮지 않으며, monotonic update guard가 SAVED/eventId 기록을 stale writer로부터 보존한다. 같은 request id의 다른 payload는 저장 전에 거절한다.
- `calendar/CalendarGateway.kt`는 새 insert 전에만 현재 권한, start-in-future, end validation, 선택 calendar id/account/name binding을 검증한다. 기존 request id는 저장된 payload/destination으로만 복구 조회한다. 모든 post-dispatch journal commit은 schedule-specific durable authorization generation을 실제 journal claim/update lock 안에서 다시 확인한다. 이 generation은 process-local notification epoch가 아니라 persisted consent timestamp/version에서 만든 positive token이라 process restart와 listener disconnect로 바뀌지 않고, consent reset/delete/re-consent에서는 달라진다. reset/동의 철회와 late callback이 겹치면 prepared/inserted/saved/uncertain/undo record를 다시 만들지 않는다.
- provider insert 뒤 event id readback이 payload와 일치할 때만 `SAVED`로 보고한다. insert dispatch 또는 readback 불명은 `UNCERTAIN`으로 남기고 같은 request id로 재삽입하지 않는다. event id가 있는 불명/inserted 기록은 readback 조회로만 복구하며, AgentActivity는 recoverable journal records를 보이는 `다시 확인` action으로 노출한다. 이 action은 `verifyExistingEvent` query-only entrypoint만 호출하므로 journal record가 없거나 손상돼도 새 insert를 절대 시도하지 않는다.
- undo는 저장된 event snapshot이 현재 provider row와 같고 reminders/attendees row까지 batch assertion으로 같은 경우에만 delete를 실행한다. SQL NULL과 empty string은 snapshot에서 구분한다. 조회 실패 또는 변경 보호 보장 실패는 자동 삭제하지 않고 event view를 제공한다. Undo caller는 original receipt generation을 전달해야 하며 generation을 모르면 자동 삭제하지 않는다.
- `reminder/ExternalAlarmGateway.kt`는 실제 `AlarmClock.ACTION_SET_ALARM` handler만 조회하고 시계 앱으로 표시한다. 요청 instant가 launch 시점 device timezone의 다음 wall-clock occurrence와 정확히 같을 때만 handoff한다.
- section 10 최신 조정에 맞춰 외부 알람 handoff는 `EXTRA_SKIP_UI=true`로 요청을 dispatch하고 `REQUEST_DISPATCHED`만 보고한다. 외부 앱 저장 완료, 알람 ID, undo는 주장하지 않는다.
- 외부 알람 dispatch digest는 request id와 handler/payload를 함께 저장해 같은 요청의 자동 재전송을 막고, 별도 사용자 요청은 새 request id로 구분한다. dispatch 기록이 손상되거나 저장되지 않으면 fail-closed로 dispatch하지 않는다.
- `AgentActivity`는 캘린더 권한 요청, 캘린더 계정 선택, 외부 calendar input handler 선택, 시계 앱 handler 선택, AM/PM clarification buttons, 캘린더 view/undo action을 연결했다. Pending 요청에는 consent/reset generation을 묶고 권한 callback, 선택 callback, provider save/undo, 외부 handoff, Momo fallback task 저장 직전에 다시 검사한다. Momo fallback은 IO thread 안에서 `AssistantTaskStore.addTask` 직전 한 번 더 generation과 future time을 확인한다.
- AM/PM clarification은 최초 해석한 날짜를 절대 `YYYY년 M월 D일`로 넣어 재파싱하므로 자정 이후 버튼 선택이 원 요청의 relative date를 밀지 않는다. Pending schedule request/receipt의 raw text, payload, account, handler list는 Android saved-instance Bundle에 평문 저장하지 않는다. `ScheduleStateCodec`의 custom Saver가 JSON을 production `ProbeCrypto`로 암호화한 Base64 문자열만 저장하고, 복원된 request/receipt는 원 generation이 현재 durable schedule generation과 맞을 때만 사용한다. ready=false 초기 frame은 복원 state를 지우지 않으며 먼저 도착한 permission result는 ready/allowed 후 같은 UUID로 한 번 처리한다. 첫 캘린더 선택 화면에서 바꾼 기본 기간은 현재 pending payload에도 반영되며, 그 결과 자정을 넘기면 provider insert와 external calendar handoff 전에 거절한다.
- `MainActivity` 전체 삭제는 Momo의 schedule preferences/journals와 외부 입력/시계 앱 선택 기록만 삭제한다. 외부 사용자 캘린더 행은 삭제하지 않으며 reset dialog copy도 이를 분리해 말한다.
- `AndroidManifest.xml`에 calendar read/write, normal set-alarm permission, alarm/calendar intent queries, `TaskAlarmActivity` 등록을 추가했다.
- `app/build.gradle.kts`는 `versionCode=9`, `versionName=0.8.1-agent`로 올렸다.

## 파서 수락 조건

- 지원 날짜: `YYYY년 M월 D일`, `M월 D일`, `오늘`, `내일`, `모레`.
- 지원 시간: `오전/오후 H시 M분`, `HH:mm`, `00시`, `13~23시`.
- bare `1~12시`는 AM/PM clarification으로 멈춘다.
- `오전/오후`가 붙은 시간은 원시 hour 1~12만 허용한다.
- 연도 없는 과거 월일은 다음 해로 넘기지 않는다.
- 존재하지 않는 날짜, 분 60, 과거 시각, 복수 날짜, 복수/대안 시각, calendar 제목 없음, 종료<=시작, 기본 기간 자정 rollover는 저장하지 않는다.
- Calendar 명시 duration/end는 기본 duration보다 우선한다. 시작/종료 시각의 minute span은 duration으로 재사용하지 않고, overflow/24시간 초과 duration은 clarification으로 멈춘다. Alarm 명령은 point-in-time이며 calendar default duration/end validation으로 막지 않는다. Alarm label이 비면 추론 없이 `알람`으로 둔다. 날짜가 있는 point alarm을 사용자가 캘린더 저장으로 바꿀 때는 alarm의 1분 placeholder를 그대로 쓰지 않고 현재 calendar 기본 기간으로 변환한 뒤 calendar validation을 다시 거친다.
- 명령 끝이 지원하는 imperative form으로 끝나고 대상이 확인될 때만 mutation이다. `저장한 거 확인해줘`, `저장했어`, 조회·부정·인용·설명 텍스트는 calendar/alarm mutation이 아니다.

## 자동 검증 상태

실행 명령:

```powershell
& .\scripts\build.ps1 -Tasks @('testDebugUnitTest','assembleDebug','assembleRelease','lintDebug')
```

결과: 0.8.1 device QA feedback 반영 뒤 UI lane freeze를 다시 확인하고 full Gradle sequence를 재실행했다. `assembleDebugAndroidTest`와 `assembleDebug`를 먼저 완료해 root device QA가 최신 APK를 사용할 수 있게 했고, 이어 `testDebugUnitTest`, `assembleRelease`, `lintDebug`도 통과했다. `testDebugUnitTest` XML 기준 136 tests, 0 failures, 0 errors, 0 skipped이다. 최종 출력은 `BUILD SUCCESSFUL in 8m 14s`, 247 actionable tasks 중 38 executed, 209 up-to-date이다. APK 산출물은 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release.apk`이다.

추가·갱신한 테스트 파일:

- `app/src/test/java/kr/mom/probe/agent/ScheduleCommandParserTest.kt`
- `app/src/test/java/kr/mom/probe/agent/LocalAgentEngineTest.kt`
- `app/src/test/java/kr/mom/probe/calendar/CalendarGatewayTest.kt`
- `app/src/test/java/kr/mom/probe/reminder/ExternalAlarmGatewayTest.kt`
- `app/src/androidTest/java/kr/mom/probe/DeviceAgentSmokeTest.kt`
- `app/src/androidTest/java/kr/mom/probe/CalendarProviderDeviceTest.kt`

추가 androidTest harness는 debug `.qa` application id만 허용하고 synthetic consent/profile/deferred setup을 만든다. `DeviceAgentSmokeTest`는 AgentActivity 한국어 직접 입력으로 로컬 부탁 저장/취소, bare time calendar AM/PM clarification, 날짜 있는 alarm fallback, TaskAlarmActivity snooze를 검증하며 native screenshots를 `.qa` app external files의 `device-smoke` 디렉터리에 저장한다. `CalendarProviderDeviceTest`는 `MOMO_QA_*` temporary local calendar만 sync-adapter URI로 생성하고 production `CalendarGateway.saveEvent()` readback/undo를 검증한 뒤 반환받은 own event/calendar id만 정리한다.

Device QA preliminary/final에서 발견한 handler preference 무시와 provider tombstone readback도 반영했다. AgentActivity는 explicit handler가 없으면 저장된 SET_ALARM handler를 gateway prepare 전에 해석한다. ProviderCalendarBackend는 `CalendarContract.Events.DELETED != 0` 행을 논리적으로 없는 일정으로 취급해 undo 뒤 tombstone row를 변경 실패로 오인하지 않으며, null cursor/query failure는 계속 조회 실패로 남긴다.

주요 회귀 항목은 anchored imperative-only mutation, 부정/설명/확인완료형 입력 mutation 0건, 시작 minute≠duration, `14:00~16:00` range title union-mask, 오전/오후 end raw hour validation, 복수/대안 시각 거절, huge duration clarification, 23:30 alarm allowed while 23:30 calendar default rollover rejected, request id payload conflict, inserted/uncertain readback recovery, query-only `verifyExistingEvent` missing-record no insert, generation-change no late uncertain commit, claim commit-block no prepared record, encrypted journal read failure fail-closed, encrypted saved-state roundtrip/no plaintext, loading-frame permission result queue with same UUID retention, process-restart durable schedule token continuity, reset invalidation, journal generation encode/decode, same request concurrency single insert/no downgrade, undo read failure no delete, and external alarm next-wall-clock compatibility이다.

## 남은 제한

- 휴대폰이 오프라인인 현재 실제 CalendarProvider 저장, 계정 동기화, 특정 시계 앱의 `EXTRA_SKIP_UI` 호환, Alarmy handler 지원은 검증하지 않았다.
- `SAVED`는 local CalendarProvider readback 성공만 뜻한다. cloud sync 완료나 다른 기기 반영은 뜻하지 않는다.
- 외부 calendar input fallback은 prefilled handler 화면 또는 direct handler dispatch만 제공한다. event id가 없으므로 Momo undo를 제공하지 않는다.
- Momo fallback alarm 저장은 기존 `AssistantTaskStore.addTask`/`TaskReminderScheduler` 계약을 사용한다. 영수증은 기존 `BriefingReminders` 알람 모드와 권한이 모두 켜진 경우에만 “모모 알람”이라고 말하고, 그 밖에는 “일반 알림”으로 표시한다. 정확 알람 권한이 없으면 “지정한 시각 무렵”으로 제한을 표시한다.
- source generation AI, OCR, private web import는 계속 미구현이다.




