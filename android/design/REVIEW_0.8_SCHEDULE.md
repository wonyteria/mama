# 0.8 Schedule / Calendar 독립 검토 — 1차

검토: GPT-6 Astra High. 읽기 기준: 2026-09-15 08:29:08 KST. 판정: **수정 필요, 일정/외부 알람 경로 승인 보류**.

범위: ScheduleCommandParser/Models, CalendarGateway/Journal/Preferences, ExternalAlarmGateway, LocalAgentEngine/AgentActivity 실제 caller, MainActivity reset, Manifest. 알람 화면·snooze UI lane은 검토하지 않았다. 소스 읽기와 이 문서 작성만 수행했으며 Gradle/실기기/계정/외부 앱을 실행하지 않았다. 0.7의 83개 통과는 0.8의 증거가 아니고, 신규 테스트의 현재 실행 결과는 shared build 담당자가 확인해야 한다.

## S1 / P1 — 부정·설명 입력도 외부 생성 명령이 됨

위치: `agent/ScheduleCommandParser.kt:19,70-80,215-221,259-265`; `LocalAgentEngine.kt:54-64`; `AgentActivity.kt:361-368`.

재현: `내일 오전 7시 기상 알람 맞추지 마`. negative는 저장/등록/추가 금지만 검사하고 `맞추지 마`는 빠져 있다. detectTarget는 부분 문자열 `맞추`를 찾으므로 AlarmRequestCommand가 만들어지고 선호 handler가 있으면 외부 앱 실행으로 이어진다. `10월 17일 14:30 상담 일정 저장 방법 설명해줘`도 생성 동사 부분문자열로 인식되며 설명 요청 gate가 없다. 인용도 특정 따옴표+후행 표현만 막는다.

기대: 현재 사용자의 명시적인 생성 명령 종결과 대상이 증명될 때만 mutation. 금지/설명/인용은 저장 0건. 파서 단독뿐 아니라 engine.scheduleCommand→Activity 실행 0건을 검증한다.

## S2 / P1 — 시각·기간 span을 구별하지 않아 잘못된 시간/제목으로 저장

위치: `ScheduleCommandParser.kt:117-139,141-164,197-210,250-258`.

- `10월 17일 오후 2시 30분 상담 일정 추가`: duration 정규식이 시작 시각의 `30분`을 다시 잡아서 기본 60분 대신 30분 일정으로 만든다. time/end ranges가 겹친 채 removeRange를 두 번 적용해 제목도 훼손될 수 있다.
- `10월 17일 14:00 또는 16:00 상담 일정 추가`: 복수 시각을 세지 않아 첫 14:00으로 저장한다. 두 날짜만 검사하는 것으로 충분하지 않다.
- `10월 17일 오전 13시 상담 일정 추가` / `오후 0시`: 오전/오후 원시 시간 1~12 범위를 검증하지 않고 보정 결과만 검사해 각각 13:00/12:00으로 저장할 수 있다.
- 시각으로 소비된 문자열과 분리한 명시 duration/end만 받아야 한다. 지원하지 않는 반복/복수/충돌 구문과 과대한 숫자는 보류해야 하며 첫 match나 숫자 overflow로 실행/앱 종료하지 않아야 한다.

## S3 / P1 — 동의 철회·전체 삭제 이후에도 외부 변경과 로컬 재저장이 가능

위치: `AgentActivity.kt:128-160,218-236,258-315,407-410,473,519-644`; `CalendarGateway.kt:71-104,142-159`; `CalendarCommandStore.kt:63-94`; `CalendarPreferences.kt`; `ExternalAlarmGateway.kt`.

`allowed`는 화면 분기에만 쓰이고 save/undo/dispatch boundary에는 generation 또는 fresh consent gate가 없다. LaunchedEffect(allowed)도 undoTask만 지우며 calendar/alarm/clarification pending 요청을 남긴다. 캘린더 권한 또는 AM/PM/목적지 선택 대기 중 다른 화면에서 동의 철회/전체 삭제를 하면 늦은 callback이 saveEvent, undo 또는 startActivity를 시작할 수 있다. reset 뒤 IO callback이 journal을 다시 생성할 수도 있다.

기대: 요청에 reset/consent generation을 결합하고 권한 응답·선택·외부 dispatch·provider insert/delete·journal update 직전 유효성을 검증한다. reset과 진행 중 변경은 같은 실행 경계에서 조정한다. 화면 숨김만으로 보호됐다고 보지 않는다. 외부 캘린더 행을 reset에서 지우지 않는 현재 정책은 유지한다.

## S4 / P1 — undo가 관련 알림/참석자 변경을 보호하지 못하고 조회 실패를 삭제 성공으로 오인

위치: `CalendarCommandStore.kt:20-32`; `CalendarGateway.kt:142-159,230-278,283-318`.

Snapshot/query/assert는 Events.HAS_ALARM/HAS_ATTENDEE_DATA boolean만 확인한다. 예를 들어 저장 당시 provider 기본 reminder가 존재한 event에서 사용자가 알림을 10분 전→30분 전으로 바꾸면 boolean은 계속 true다. 실제 Reminders row를 읽지 않으므로 snapshot이 같다고 보고 event를 삭제한다. 참가자 내용/상태 변경도 같은 문제이며 Events의 기타 변경 필드도 완전 snapshot이라고 할 수 없다.

또 `readEvent`는 null cursor와 성공 조회 0행을 모두 null로 반환한다. undo는 이를 `이미 없어졌어요/취소했어요`로 해석하므로 provider 조회 실패를 거짓 UNDONE으로 보고한다. nullable/빈 문자열을 합친 snapshot 후 IS NULL assertion은 정상 저장한 빈 문자열 event의 undo도 막을 수 있다.

기대: 관련 상태를 읽고 batch assertion으로 검증하거나, 변경 보호를 보장하지 못하는 경우 자동 undo 대신 일정 보기로 제한한다. 조회 결과는 Found/NotFound/Unavailable을 분리한다. 동일 event ID만 대상으로 하며 사용자 외부 편집은 보존한다.

## S5 / P1 — 실행 기록 읽기 오류가 빈 기록으로 바뀌어 재삽입 가능

위치: `CalendarCommandStore.kt:80-94`; `CalendarGateway.kt:71-95`.

decrypt/Base64/JSON 오류는 `getOrDefault(emptyList())`로 처리한다. 한 번 insert한 requestId의 journal을 읽을 수 없으면 find=null이 되고 saveEvent가 새 insert를 시도한다. MAX_RECORDS=50으로 PREPARED/UNCERTAIN까지 무조건 잘라 버리는 것도 오래된 동일 요청의 중복 방지 근거를 소실한다. 개별 journal 메서드만 synchronized이므로 전체 find→prepare→insert는 원자적 요청 claim이 아니다.

기대: journal 실패는 fail closed, 새 외부 쓰기 0건. 미확인/진행 요청은 임의 eviction하지 않고 동일 request의 첫 claim만 insert하도록 실제 transaction 경계를 둔다.

## S6 / P2 — 저장 여부 불명 상태가 조회 복구로 이어지지 않고 UI에서 요청 ID도 유실

위치: `CalendarGateway.kt:107-123`; `AgentActivity.kt:128-136,281,361-368`.

insert 뒤 readback 실패하면 eventId를 가진 UNCERTAIN으로 바뀌지만 resumeExisting(UNCERTAIN)은 readEvent를 재시도하지 않는다. UI에도 같은 requestId의 조회 복구 action이 없고 pending 요청은 remember에만 있어 회전/프로세스 종료에서 사라진다. 다음 입력마다 새 UUID를 생성한다. 따라서 이미 존재할 수 있는 일정의 확인 대기가 끝나지 않으며 재입력이 새 일정이 되기 쉽다.

기대: eventId가 있으면 동일 요청으로 조회만 복구하고 새 insert하지 않는다. pending/request ID를 재생성 없이 복원하고 불명 요청을 화면에서 다시 확인할 수 있어야 한다. id 없는 불명 결과는 계속 불명으로 보존한다.

## S7 / P1 — 외부 알람 launch 실패를 이후 요청 전달 완료라고 주장

위치: `AgentActivity.kt:307-318`; `ExternalAlarmGateway.kt:100-102,124-132`.

markDispatched를 startActivity 이전에 기록한다. 그 후 ActivityNotFound/SecurityException 등으로 launch가 실패해도 dispatch key가 남는다. 다음 동일 입력은 intent=null, REQUEST_DISPATCHED와 `이미 요청했어요`로 반환된다. 사전 기록 자체는 중복 방지에 유용하지만 아직 전달됐다는 receipt가 아니다.

기대: PREPARED/UNCERTAIN/REQUEST_DISPATCHED를 분리한다. 전달 성공 뒤에만 전달 완료라 말하며, 불명 상태를 자동 재전송하지 않는다. 명백한 실행 실패는 정확한 실패 상태다. 같은 payload라는 이유로 별도로 요청한 새 request까지 영구 동일 요청으로 취급하지 않는다.

## S8 / P1 — 외부 앱 선택 중 시간대 변경 후 옛 시간대로 dispatch

위치: `ExternalAlarmGateway.kt:44,93-116`; `AgentActivity.kt:114,292-315`.

ExternalAlarmGateway는 생성 시점의 ZoneId.systemDefault를 보관한다. 앱 선택 dialog가 열린 동안 기기 시간대가 바뀌면 launch 직전 prepare를 다시 해도 옛 시간대에서 next occurrence를 검사하고 그 시·분을 전송한다. 수신 시계 앱은 현재 기기 시간대를 쓰므로 요청한 절대 instant와 달라진다.

기대: 실제 launch 경계에서 현재 device zone/time과 handler를 다시 검증한다. 변환 기준은 엄마에게 명확히 표시하고 날짜를 다음 발생일로 몰래 바꾸지 않는다. 현재 같은 시간대에서 handler를 다시 조회하고 미래 occurrence를 검사하는 부분은 확인했다.

## S9 / P2 — AM/PM·기본 기간 선택이 최초 요청의 의미를 변경하거나 선택을 무시

위치: `AgentActivity.kt:519-545,576-596`; `ScheduleCommandParser.kt:33-43`.

- 자정 전 `내일 3시 치과 일정 추가`를 보내고 자정 후 AM/PM 버튼을 누르면 handleQuestion이 원문 `내일`을 새 오늘 기준으로 재파싱하여 하루 더 뒤로 저장한다.
- 첫 calendar 선택 dialog에서 기본 기간을 60→30분으로 바꿔도 PendingScheduleRequest.payload는 이미 60분으로 계산돼 있다. 화면은 30분을 설명하지만 saveCalendar(request)는 기존 payload로 60분 일정을 저장한다.

기대: 최초 날짜/시간대 기준을 pending 요청에 보존하며, 선택한 기간을 현재 요청에도 적용하거나 적용 범위를 명확히 보여준다. 이미 지난 시각은 boundary에서 재확인한다. 외부 calendar ACTION_INSERT fallback도 선택 지연 후 payload 유효성을 재검사해야 한다.

## S10 / P2 — 불일치 외부 알람을 모모로 바꾼 뒤 지난 시각 저장·정확 알림 오해

위치: `AgentActivity.kt:328-346`.

saveMomoAlarm은 permission/channel만 검사하고 permission 응답 뒤 startMillis>now를 다시 확인하지 않는다. 미래에 해석한 요청의 시각이 권한 대기 중 지나면 과거 remindAt으로 task를 저장할 수 있다. taskStore.addTask는 >0만 검사하며 이 UI는 readback/예약 결과와 exact alarm 가능 여부를 확인하지 않고 `알림을 저장했어요`라고 말한다.

기대: 실행 직전 과거 여부를 확인하고 실제 저장/예약 실패를 구별한다. exact 권한이 없으면 지정 시각 무렵이라는 한계를 알린다. 이 지적은 새 AgentActivity 연결부에 한정하며 task/alarm UI lane의 내부 구현은 별도 담당자 검토 범위다.

## 확인한 적절한 경계

- UI가 실제 CalendarGateway.saveEvent와 ExternalAlarmGateway.prepare/intent dispatch에 연결돼 있다. fake saved 문자열만 만든 구현은 아니다.
- CalendarGateway는 신규 insert 전 현재 시작 시각·READ/WRITE 권한·calendar ID/account/type/owner/displayName binding을 확인한다. 같은 requestId에 다른 payload는 거절하며 PREPARED는 바로 재insert하지 않는다.
- event ID readback의 calendar/title/start/end/timezone이 맞을 때만 SAVED로 승격한다. provider 저장과 서버 동기화를 구분한다.
- journal/raw request/payload/destination은 기존 ProbeCrypto로 암호화한다. external alarm dedup은 payload digest를 저장한다. 앱 백업은 Manifest에서 비활성이다.
- external alarm은 실제 ACTION_SET_ALARM handler만 선택하고 EXTRA_SKIP_UI=true, 시/분/label만 전송한다. 임의 날짜를 weekday 반복으로 바꾸지 않으며 외부 SAVED/자동 undo를 주장하지 않는다. 다만 S7의 전달 이력 상태를 고쳐야 한다.
- MainActivity의 전체 삭제는 Momo preferences/journal을 지우며 CalendarProvider event 삭제를 호출하지 않는다. S3의 늦은 실행 차단과는 별개다.
- 0.7 원문 자동화 경로와 일정 명령 입력은 분리되어 있다. 생성 AI/OCR/개인 웹/실제 등교 앱 연동을 구현했다고 볼 근거는 없다.

## 후속 검증 요구

각 재현을 겨냥한 파서·gateway 단위 회귀 외에 permission/target/AMPM→현재 generation→저장/dispatch→readback/실패 흐름을 검사해야 한다. FakeBackend가 이미 완전 snapshot 비교를 하는 테스트는 실제 Provider backend의 알림·참가자 조회 누락을 검증하지 못한다. MemoryJournal 테스트도 실제 encrypted read 실패/eviction/요청 복원을 검증하지 않는다.

휴대폰이 오프라인이고 선호 calendar/clock 앱을 모르는 상태에서 실제 provider 호환·EXTRA_SKIP_UI·서버 sync·취소 보호·기기 잠금/권한 흐름의 성공은 주장할 수 없다. 새 의존성/외부 계정/서비스 연동은 이번 검토에서 수행하지 않았다.

### S5 동시 요청의 구체적 실행 순서 (추가 확인)

CalendarGateway.saveEvent에는 전체 요청 단위 잠금/원자적 claim이 없다. A와 B가 같은 requestId로 동시에 호출돼 둘 다 journal.find=null을 읽으면, A.prepare가 PREPARED를 저장한 뒤 B.prepare는 그 기존 record를 그대로 반환한다. Gateway는 prepare의 신규/기존 여부를 구별하지 않고 양쪽 모두 `backend.insert(payload,destination)`를 실행한다. 결과는 2 insert다. B가 다른 payload여도 최초 find=null을 이미 통과했으므로 기존 payload 거절 검사까지 우회하고 A의 prepared record에 B의 eventId를 연결할 수 있다. Journal 메서드의 개별 @Synchronized와 MemoryJournal의 getOrPut은 이 경계를 막지 않는다. 한 요청에서 insert 권한을 얻은 caller 하나만 실행하도록 claim 결과를 명시하고 다른 caller는 조회/대기로 전환해야 한다.

---

# 2차 Schedule 검토 — 2026-09-15 08:50:07 KST 읽기 기준

판정: **수정 필요**. 이번에는 S1~S10 및 그 수정의 직접 파급만 읽었다. 알람 UI A1~A6 수정 파일은 재검토하지 않았고 Gradle/기기 실행도 하지 않았다. 구현 기록의 완료 설명과 실제 production 경로를 구분했다.

| 항목 | 상태 | 확인 결과 |
|---|---|---|
| S1 부정·설명 실행 | OPEN, 부분 해결 | `맞추지 마` 및 설명/방법 예시는 차단된다. 하지만 생성 명령이 아닌 저장 사실/조회에서 부분 문자열 동사로 실행되는 잔여가 있다. |
| S2 시간/기간 | OPEN, 부분 해결 | 시작 minute를 mask하고 복수 시간·오전/오후 시작 hour 범위를 검사한다. unchecked duration 숫자와 겹치는 종료 range 처리가 남는다. |
| S3 철회 뒤 실행/재저장 | OPEN | UI pending에 epoch를 붙이고 취소 상태에서 지우는 개선은 확인했다. gateway의 journal claim/uncertain update 및 undo에는 아래 generation 빈틈이 남는다. |
| S4 undo 변경 보호 | OPEN, 부분 해결 | 실제 Reminders/Attendees 읽기·개수/내용 batch assert와 null cursor 실패 분리는 확인했다. 빈 문자열/null 불일치가 남는다. |
| S5 claim/journal | OPEN, 부분 해결 | 손상 journal은 fail closed이고 미확인 기록 eviction을 막았다. 원자적 claim의 승자만 insert하므로 기존 두 insert 문제는 해결됐다. 그러나 observer의 stale 상태 쓰기가 성공 receipt를 덮을 수 있다. |
| S6 불명 복구/요청 보존 | OPEN, 부분 해결 | eventId 있는 UNCERTAIN은 readback만 재시도한다. 실제 UI의 request 복원/불명 조회 action은 아직 없다. |
| S7 외부 launch 실패 receipt | CLOSED, 지적 재현 | PREPARED와 DISPATCHED가 분리되고 startActivity 성공 뒤 DISPATCHED를 기록한다. 실패 시 PREPARED를 지우며 불명 PREPARED는 재전송하지 않고 확인 중이라고 말한다. requestId도 digest 입력에 포함했다. |
| S8 시간대 재확인 | CLOSED, 지적 재현 | deviceZone이 함수로 바뀌어 각 prepare에서 현재 시간대를 읽는다. 선택 완료 후 실제 prepare를 호출하며 handler와 next occurrence를 재확인한다. |
| S9 날짜/기간 선택 | OPEN, 부분 해결 | AM/PM 버튼의 문장에 최초 해석 날짜를 절대 날짜로 고정하고 기간 변경을 pending payload에도 반영했다. 기간 선택 후 자정 넘김 검증이 빠졌다. |
| S10 모모 fallback | OPEN, 부분 해결 | 권한 복귀 및 coroutine 시작에 미래 시각/epoch 검사가 추가됐고 exact 권한 문구도 구분한다. 실제 IO 저장 경계와 receipt/예약 검증은 아래 한계가 남는다. |

## 남은 구체 재현

### B1 / P1 — 명령이 아닌 저장 조회/사실 문장이 아직 mutation

`ScheduleCommandParser.detectTarget`는 계속 `text.contains("저장"/"추가"...)`를 쓰며 `isLookup`의 queryWords 검사는 createVerb가 있으면 적용되지 않는다. `10월 17일 14:30 치과 일정 저장한 거 확인해줘`는 조회지만 CalendarCreateCommand가 된다. `10월 17일 14:30 치과 일정 저장했어`도 완료 사실을 새로운 저장 요청으로 해석한다. 기존 S1에서 요구한 명시적인 생성 명령 종결 검증이 필요하다. 질문 단어 예외를 계속 늘리는 것만으로 이 경계를 대체하지 않는다.

### B2 / P2 — duration/end의 숫자·range 처리가 여전히 안전하지 않음

`ScheduleCommandParser.extractEnd`는 duration의 `toLong()` 및 `Duration.ofHours(...).toMillis()`를 검사 없이 실행한다. `10월 17일 오후 2시 상담 999999999999999999999999시간 일정 추가`는 500자 입력 한도 안이지만 예외가 발생하며 Activity.handleQuestion이 파서 예외를 잡지 않는다.

start span만 mask하여 종료 시각의 minute는 duration으로 다시 소비할 수 있다. 종료 시각+명시 기간을 먼저 구분해야 한다. 또한 extractTitle은 date/time/end ranges를 union하지 않고 각각 removeRange하여 rangeClock이 시작 시각을 포함하는 지원 입력에서 같은 부분을 두 번 제거할 수 있다. 시작/종료의 오전·오후 원시 hour 검증도 공통으로 적용해야 한다. 안전하지 않은 입력은 저장 0건/clarification이어야 한다.

### B3 / P1 — reset 이후 journal 재생성 경로와 generation 없는 undo

`CalendarGateway.verifyInserted`가 provider read 중 reset을 만나 실패/null을 받으면 `uncertain(record,...)`로 들어간다. uncertain은 expectedGeneration을 받지 않고 `journal.update(next)`를 실행한다. 따라서 reset으로 지운 rawText/payload/destination을 늦은 실패 처리에서 다시 쓸 수 있다. 마찬가지로 readCalendar가 지연된 사이 reset되면 그 후 journal.claim은 먼저 원문을 기록하고 mutationAllowed를 검사한다.

UI에는 fresh epoch 검사가 추가됐으나 journal의 쓰기 자체와 reset이 같은 실행 경계에 있지 않다. `undo(record)`는 mutationAllowed(null)이어서 원 요청 세대와 현재 새 동의를 비교하지 않는다. expected generation을 갖고 마지막 callback의 UI 결과·undo·claim/update까지 유효성을 유지해야 한다. 성공 경로 앞에만 check를 두어 실패 경로의 private data 재생성을 허용해서는 안 된다.

### B4 / P1 — concurrent observer가 성공 receipt를 eventId 없는 불명으로 덮음

A가 claim한 뒤 insert/readback을 진행한다. B는 같은 requestId의 PREPARED를 읽어 `resumeExisting→uncertain`의 copy를 만든다. B의 journal.update가 지연되고 A가 SAVED/eventId를 먼저 기록한 후 B가 그 옛 PREPARED 기반 UNCERTAIN을 쓰면 최신 eventId/snapshot이 사라진다. Journal.update는 상태/version 확인 없이 같은 requestId 전체를 교체한다.

승자만 insert하는 수정은 맞다. 관찰자는 진행 중 PREPARED를 결과로만 반환하고 owner의 상태를 쓰지 않거나, version/단조 상태 전이 검사로 뒤늦은 update가 INSERTED/SAVED를 덮지 못하게 해야 한다. 새 concurrency test는 insert 횟수만 확인해 이 실행 순서를 막는 증거가 아니다.

### B5 / P2 — 실제 UI에는 불명 요청 재조회/회전 복원이 없음

`AgentActivity.kt:144-152`의 pending/undo는 여전히 remember다. `436-437`은 명령마다 새 UUID를 만들고 `326`의 UNCERTAIN 처리는 문구 표시로 끝난다. 기록을 읽어 동일 requestId의 저장 여부를 확인하는 UI action 또는 재생성 복원 경로가 없다. Gateway의 UNCERTAIN readback 기능이 실제 엄마의 복구 흐름에 도달하지 않는다.

pending requestId를 원래 요청과 함께 보존하고, eventId가 있는 불명 건은 조회만 재시도한다. 원문 전체를 평문 saved-state에 복사하는 해결은 피한다. 새 독립 요청과 동일 요청 복귀를 구분해야 한다.

### B6 / P2 — 새 기간 선택이 자정 넘김 gate를 우회

기본60분에서 `10월 17일 22:45 상담 일정 추가`는 23:45 종료로 유효하다. 저장할 캘린더 선택 중 90분을 누르면 `withDefaultDuration`이 endMillis만 00:15로 바꾼다. 이후 saveEvent는 start>now만 확인하므로 최초 parser가 금지하던 자정 기본 종료를 확인 없이 저장한다. 기간 선택을 현재 요청에 반영한 뒤 동일한 날짜/기간 validation을 재사용해야 한다.

### B7 / P2 — null/빈 문자열 보존과 IO 알림 경계 잔여

Provider readEvent/readAttendees 및 journal decode는 `ifBlank { null }`로 빈 문자열을 합치는데 batch assert는 IS NULL을 사용한다. DB 값이 빈 문자열이면 변경되지 않은 모모 일정조차 assertion을 통과하지 못한다. nullable 필드는 원래 값 그대로 보존해야 한다. 관련 row를 읽고 변경된 내용을 차단하는 새 코드는 올바른 개선이다.

모모 fallback은 coroutine에서 시간/epoch를 검사한 뒤 별도 IO dispatcher에 넘겨 taskStore.addTask를 호출한다(`AgentActivity.kt:401-406`). IO가 지연돼 목표 시각이 지난 뒤 저장되는 경우를 저장 boundary에서 막는 검사는 없다. 새 반환 task를 받은 사실과 실제 예약 요청/정확 알람 가능 여부는 구분해야 한다. scheduler 내부는 다른 lane이 수정 중이라 이번에는 재독하지 않았으며 그 최종 receipt 주장은 통합 검증이 필요하다.

## 검증 경계

새 source에서는 calendar account binding, encrypted journal, no insert retry on unknown, single claimant insert, related-row undo assertions, current device timezone 재조회, explicit EXTRA_SKIP_UI=true와 외부 저장 완료 미주장 경계를 확인했다. 실제 외부 앱/CalendarProvider 및 새 unit/build는 실행하지 않았다. permission/transaction/reset/실패 callback을 연결한 검증이 필요하며 테스트용 MemoryJournal/FakeBackend가 실제 store/provider를 대체한다는 주장은 하지 않는다.

---

# Schedule 3차 검토 — 2026-09-15 09:07:16 KST 읽기 기준

판정: **수정 필요**. 생산 Kotlin 컴파일 통과는 상위 작업 제공 정보이며, 이 검토자는 Gradle/테스트/폰을 실행하지 않았다. Alarm UI lane의 통과 판정을 재개방하지 않고 B1~B7와 point-alarm 변경의 직접 파급만 확인했다.

| 2차 항목 | 3차 판정 |
|---|---|
| B1 저장 사실/조회 오실행 | CLOSED. target detection이 일정/캘린더/알람 대상+지원 imperative 종결 regex로 바뀌었다. `저장했어`, `저장한 거 확인해줘`는 생성으로 들어가지 않는다. |
| B2 시간·기간·range | OPEN. 거대한 duration의 toLongOrNull/24시간 한계와 시작/종료 minute 분리는 개선됐다. 중복 range 삭제와 종료 AM/PM hour 검증이 여전히 남는다. |
| B3 generation/late write | OPEN, 부분 해결. uncertain에도 원 generation을 전달하고 undo caller도 receipt의 generation을 전달한다. 그러나 journal commit과 reset의 실행 경계가 분리돼 있다. |
| B4 observer 상태 덮기 | CLOSED. PREPARED observer는 journal을 쓰지 않는다. 실제 store의 shouldKeepCurrent가 SAVED/UNDONE downgrade와 기존 eventId 제거를 차단한다. 원자적 claim의 단일 insert도 유지된다. |
| B5 요청 복원/복구 | OPEN, 부분 해결. saveable request ID/receipt와 보이는 다시 확인 action이 생겼다. 민감 payload를 평문 Bundle에 복제하며 다시 확인의 생산 경로가 엄격한 query-only API가 아니다. |
| B6 기본기간 자정 넘김 | CLOSED, 직접 calendar 경로. gateway validateNewPayload와 external calendar handoff validation이 default end의 날짜 변경을 막는다. |
| B7 null fidelity/IO 경계 | NULL/빈 문자열 부분 CLOSED. provider와 journal이 원래 빈 문자열을 보존한다. 모모 fallback의 실제 IO 저장 직전 시간/epoch 검사는 아직 밖에 있다. |

## D1 / P1 — 지원하는 시작~종료 입력에서 제목이 훼손됨 (B2 잔여)

`ScheduleCommandParser.extractTitle`은 range union/mask 없이 date/start/end range를 각각 removeRange한다. `10월17일14:00~16:00상담일정추가`에서 start=`14:00`, end-range=`14:00~16:00`이 같은 위치에서 겹친다. 현재 regex/range 삭제 계산을 읽기 전용 PowerShell로 재현하면 삭제 후 문자열이 `가`만 남는다. 이후 제목 정리에서도 `가`가 남아 잘못된 제목으로 저장될 수 있다. 이것은 앱/JUnit 실행 결과가 아니라 동일 range 알고리즘의 계산 확인이다.

범위를 union하거나 이미 존재하는 maskRanges로 한 번만 제거해야 한다. `parseEndClock`/`parseRangeEnd`도 시작 parser와 달리 marker가 있을 때 raw hour 1~12를 검사하지 않는다. `10월17일오전11시부터오전13시까지상담일정추가`는 13:00 종료로 통과할 수 있다. 유효하지 않은 종료 시각은 자동 보정하지 않고 거절해야 한다.

## D2 / P1 — generation 확인과 실제 journal commit이 reset과 원자적으로 묶이지 않음

Gateway는 journal.claim/update 앞에서 mutationAllowed를 확인한다. 하지만 journal의 @Synchronized 쓰기 함수에는 expected generation을 전달하지 않고, reset은 같은 journal lock을 나중에 별도로 획득한다. 예: IO caller가 `mutationAllowed=true`를 읽고 claim/update 진입 전 대기 → 메인 reset이 epoch 증가·journal clear → 기존 caller가 claim/update를 수행하면 삭제된 원문/계정/요청 기록이 다시 생성된다. claim/update가 다른 journal 호출 때문에 대기 중인 경우에도 가능하다.

실패 readback 뒤 uncertain의 명시 gate 누락은 해결됐다. 남은 문제는 check와 mutation 사이 실제 실행 경계다. journal 내부에서 generation을 확인하고 reset과 공유하는 lock/transaction으로 보호하거나 동등한 방식을 사용해야 한다. 현재 함수 앞쪽의 반복 boolean 검사만으로 late commit 금지를 승인할 수 없다.

## D3 / P2 — raw pending/receipt를 평문 saved-state에 복제 (B5 수정의 직접 파급)

`AgentActivity.kt:144-155`는 CalendarCommandRecord/PendingScheduleRequest/PendingClarification를 rememberSaveable로 저장한다. 이 자료형은 Serializable이며 rawText, 제목·시간·계정·참석자 snapshot을 포함한다. custom 암호화 Saver 없이 Android Bundle에 이 내용을 그대로 보존하므로 기존 ProbeCrypto journal 밖에 새로운 원문 파생 사본이 생긴다.

request ID/receipt ID만 saveable에 두고 암호화 저장소에서 복원하거나 암호화한 serialized state만 사용해야 한다. 단순히 Serializable을 붙인 것은 앱의 암호화 보관 경계를 유지하는 수정이 아니다. 이를 해시/OS 저장소만으로 익명화했다고 설명해서도 안 된다.

## D4 / P2 — 다시 확인 action이 새 insert 경로도 호출 (B5 잔여)

보이는 `다시 확인`은 `saveCalendar(PendingScheduleRequest(record.requestId,...))`를 호출한다. saveEvent는 journal.find가 없으면 신규 insert를 하는 함수다. stale saved-state receipt가 남았지만 reset 등으로 journal record가 없는 경우 현재 generation을 새로 붙여 신규 일정이 만들어질 수 있다. 불명 결과의 확인 action은 기존 record/event ID만 query하는 recovery 함수여야 하며 기록 없음은 확인 불가로 끝나야 한다.

ID 있는 UNCERTAIN의 기존 journal 경로에서 query로 복구하는 부분은 올바르게 구현돼 있다. 그 기능을 UI에서 엄격한 조회 전용 entry로 호출해야 한다.

## D5 / P2 — 알람→캘린더 fallback이 placeholder 1분을 실제 종료로 사용

Point alarm 분리는 올바르다. 23:30 알람에 calendar 기본 종료 규칙을 적용하지 않고, label이 없으면 `알람`으로 두며 외부 시계 앱에는 시/분을 전달한다.

그러나 새 AlarmRequest payload는 end=start+1분, explicitEnd=true, defaultDurationMinutes=0이다. `AgentActivity`의 날짜 불일치 fallback `캘린더에 저장`은 이 payload를 그대로 CalendarCreateCommand로 바꾼다. 다음 달 알람을 calendar로 대체하면 사용자 기본60분과 관계없이 1분 행사로 저장되고 존재하지 않던 명시 종료처럼 취급된다. Calendar 목적지로 전환할 때만 공개된 calendar 기본기간과 동일 validation을 적용해야 한다.

## D6 / P2 — 모모 fallback의 마지막 시간/epoch 검사는 IO 큐 밖

`AgentActivity.kt:430-436`은 main coroutine에서 미래/epoch를 확인한 뒤 IO dispatcher로 옮겨 addTask한다. IO가 지연되는 동안 시각이 지나거나 동의 세대가 변경될 수 있다. 실제 IO 블록의 addTask 직전에 request generation·future time을 확인해야 한다. 이 부분은 새 schedule caller의 경계이며 이미 통과한 alarm UI 상태/화면 기능을 다시 검토한 것이 아니다.

최종 자동 검증에서는 위 남은 재현과 실제 journal commit/reset interleaving을 포함해야 한다. 새 point-alarm 기본값과 복구 UI가 생성됐다는 것만으로 저장소/목적지 전환까지 승인하지 않았다. 실제 calendar/clock 앱, cloud sync, 기기 잠금/FSI/새 APK는 계속 미검증이다.

---

# Schedule 제한 4차 검토 — 2026-09-15 09:28:16 KST

판정: **원래 S6의 요청/receipt 복원 1건 OPEN; 나머지 D1/D2/D4/D5/D6 CLOSED**. 새 기능 검토로 범위를 넓히지 않았고 Alarm UI source PASS 및 상위 PC visual 판정은 유지한다. 검토자는 Gradle/폰을 실행하지 않았다. 127개 중 2개 UI 기대문구 assertion 실패라는 결과는 상위 작업 제공 정보이며 최종 전체 통과와 구분한다.

## 직접 확인한 종결

- D1: extractTitle은 unionRanges 후 maskRanges로 겹친 날짜/시작/종료 span을 한 번만 지운다. parseEndClock와 parseRangeEnd는 오전/오후 원시 hour 1~12를 모두 검사한다. 지적한 제목 손상/오전13시 종료 경로는 CLOSED.
- D2: 실제 CalendarCommandStore의 claimIfAllowed/updateIfAllowed는 @Synchronized 안에서 generation callback을 검사하고 해당 commit까지 같은 monitor를 유지한다. clear/reset도 같은 store monitor를 쓴다. gateway의 모든 production claim/update는 이 guarded entry를 호출한다. reset 전에 commit이 먼저 이기면 뒤의 clear가 제거하고, clear 뒤의 늦은 writer는 epoch가 달라 거절되는 경계를 확인했다. 테스트용 인터페이스 기본 메서드만 보고 승인한 것이 아니다.
- D4: verifyExistingEvent는 권한 세대와 journal.find를 확인하고 기록 없음/손상/eventId 없음은 확인 불가로 종료한다. insert 경로가 없으며 UI `다시 확인`도 이 entry만 호출한다.
- D5: alarmRequestAsCalendar는 현재 calendar 기본기간을 적용하고 explicitEnd=false로 바꾼다. 그 뒤 기존 calendar payload validation을 통과해야 하므로 1분 placeholder가 실제 캘린더 종료로 전달되지 않는다.
- D6: 모모 fallback은 IO 블록 안에서 addTask 직전에 requestStillValid와 future startMillis를 다시 확인한다.
- D3의 평문 payload Bundle 복제 자체는 제거됐다. 그러나 그 수정으로 아래 복원 요구를 다시 잃었다.

## E1 / P2 — 민감 saved-state 제거와 함께 원래 요청·receipt 복원 기능이 빠짐

`AgentActivity.kt:143-155`의 undoCalendar, pendingCalendar, calendar/alarm 목적지 선택, fallback, AM/PM pending은 모두 remember다. rememberSaveable에는 draft와 undoCalendarGeneration만 남았다. 암호화 pending 저장 또는 opaque request ID를 보존하는 대체 경로는 없다.

재현 1: 명시 일정 생성 → Calendar 권한 dialog 대기 → Activity 회전. 새 Activity의 pendingCalendar는 null이므로 허용 callback이 원 요청을 실행/복구하지 못한다. 목적지·AM/PM 선택 중 회전도 requestId와 최초 날짜 문맥이 사라진다. journal.claim은 destination이 결정된 후이므로 이 pre-insert pending을 encrypted journal에서 복원할 수도 없다.

재현 2: 실제 calendar 저장 성공 → 화면 회전. undoCalendar receipt는 사라지고 generation 숫자만 남는다. onload는 recoverableRecords(=eventId 있는 unresolved)만 찾으므로 SAVED receipt의 보기/undo를 복원하지 못한다.

기대: 민감 payload를 평문 Bundle에 넣지 않으면서 원 요청과 receipt를 복원한다. 예를 들어 암호화한 Saver를 사용하거나, saveable에는 opaque ID만 두고 암호화 pending/receipt 저장소에서 조회한다. 단순히 모든 값을 remember로 되돌리는 것은 앞서 요구한 same-request/회전 복원을 충족하지 않는다. id 있는 UNCERTAIN 조회 복구는 유지돼 있으나 위 두 상태를 대신하지 못한다.

이 E1을 해결한 뒤 그 저장·복원·취소/세대 경로만 제한 재확인하면 Schedule 소스 검토를 종결할 수 있다. 외부 CalendarProvider/시계 앱의 실제 호환·동기화·권한·실기기·최종APK 검증은 계속 별도다.

---

# E1 암호화 복원 제한 검토 — 2026-09-15 09:51:36 KST

판정: **E1 부분 해결, process 재생성의 generation 복원 1건 OPEN**. 마지막 요청대로 codec/Saver/permission/readiness/receipt 복원 경로만 확인했다. D1~D6와 Alarm UI 판정을 다시 열지 않았다.

## 확인한 해결

- AgentActivity의 custom stateSaver는 JSON을 ProbeCrypto로 암호화한 Base64 문자열만 saved-instance Bundle에 넣는다. 원문·계정·참석자·handler를 평문 Serializable로 넣던 경로는 제거됐다. 생산 crypto는 AndroidScheduleSavedStateCrypto→ProbeCrypto이며 테스트의 injected AES/GCM과 구분했다.
- pending request UUID, 절대 payload 날짜/시각, clarification 문장, 원 generation 및 SAVED receipt/event snapshot을 JSON codec이 보존한다.
- ready=false인 동안 effect는 복원된 요청을 지우지 않는다. 먼저 도착한 calendar/notification permission result는 saveable boolean으로 보관하고 ready/allowed 이후 원 요청과 함께 처리한다.
- 같은 process에서 Activity 회전하는 경우 pending/receipt Saver와 undoCalendarGeneration을 복원하고 원 세대가 맞는지 확인하는 생산 경로가 있다. 복호화 실패는 null로 보류하며 자동 새 mutation을 만들지 않는다.

## F1 / P1 — process-local captureEpoch를 영속 consent generation으로 사용

현재 currentConsentGeneration은 repository.captureEpoch를 반환한다. ProbeRepository의 epoch는 `AtomicLong(0)`이고 저장/복원하지 않는다. 설정 저장·listener disconnect·일부 기록 삭제 때도 증가한다.

재현: 설정 완료 후 epoch=5 상태에서 calendar 권한/목적지/AMPM 요청을 시작 → saved-state 암호화 저장 → Android가 process를 종료하고 복원. 새 ProbeRepository의 epoch는0이다. ready가 된 뒤 `clearScheduleStateFromOtherGeneration(0)`가 올바르게 복호화한 generation5 pending/receipt를 지운다. encrypted journal의 unresolved 기록도 `consentGeneration==generation` 필터 때문에 나타나지 않는다. 동일-process rotation만으로 cold process 복원을 검증했다고 볼 수 없다.

추가로 epoch0 자체가 유효하지만 CalendarCommandStore의 optionalLong 및 ScheduleStateCodec의 optionalPositiveLong은0을null로 바꾼다. 새 process에서 생성한 generation0 journal 기록은 재로드 뒤 null이 되어 현재0과 일치하지 않는다.

기대: schedule용으로 process 재생성 후에도 유지되고 실제 동의 철회/reset에서만 폐기되는 durable token 또는 동등한 안전한 재검증 기준을 사용한다. capture callback을 무효화하는 일시적 epoch를 schedule 영속 권한으로 재사용하지 않는다. 유효 token의 codec roundtrip도 보장한다. notification listener가 잠시 끊겼다는 이유로 캘린더 pending 권한이 바뀌었다고 하지 않는다.

현재 codec 테스트는77/88 세대 및 receipt의 기본 null을 사용한다. 암호화 roundtrip/helper조건 테스트이지 실제 process 재생성·AndroidKeyStore 하드웨어·permission callback lifecycle 전체 실행 증거는 아니다. 이 마지막 세대 문제를 수정한 뒤 제한 재확인하면 E1 소스 검토를 종결할 수 있다. 기기와 외부 앱 동작·최종빌드/테스트는 여전히 상위 별도 증거다.

---

# E1/F1 최종 종결 — 2026-09-15 10:01:33 KST

**판정: 0.8 Schedule/Calendar 로컬 연동 범위의 추적한 소스 검토 항목 모두 CLOSED.** E1/F1의 마지막 변경만 읽었으며 Alarm UI 및 앞서 종결한 D 범위는 재개방하지 않았다. 검토 중 확인한 AgentActivity/Codec 수정 시각은 각각 09:56:44/09:56:38이며, 이 읽기 중 추가 소스 변경은 확인되지 않았다.

`currentConsentGeneration`은 더 이상 process-local captureEpoch를 사용하지 않는다. 실제 생산 경로가 `scheduleConsentGeneration(repository.settings.value)`를 사용하고, 이는 영속 설정의 consentAt+consentVersion으로부터 양수 token을 도출한다. token은 digest의15자리 값을 Long으로 변환한 뒤1을 더하므로0/null 변환 문제가 없다. 같은 암호화 설정을 process 재생성 후 읽으면 같은 값이며 listener 연결 변화나 자녀 이름 변경은 token을 바꾸지 않는다.

실제 동의가 꺼지거나 초기 설정/버전/consentAt이 유효하지 않으면 token은 null이다. 전체 reset은 설정·동의·키를 제거하고, 새 동의는 새 consentAt으로 다른 token을 만든다. 이에 따라 이전 pending/receipt를 새 동의 세대로 실행하지 않는다. 이 token은 권한 세대를 비교하는 값이며 해시 자체가 개인정보 익명화를 보장한다는 의미는 아니다.

기존 암호화 Saver는 request UUID·절대 날짜/시각·원 token·SAVED receipt를 ciphertext Bundle로 보존한다. 초기 ready=false에서 복원 상태를 지우지 않고, 먼저 도착한 permission result는 queue한 뒤 ready/allowed와 원 token을 확인해 처리한다. query-only recovery와 원 generation을 전달하는 undo 경로도 유지된다. 따라서 평문 Bundle 복제를 피하면서 단순 회전 및 정상 동의가 유지된 process 재생성 복원을 지원하는 소스 구조가 갖춰졌다.

검증 증거의 한계: 새 codec 테스트는 injected AES/GCM으로 serialization/encryption roundtrip과 positive/stable/reset-invalidated token 조건을 검사한다. 생산 crypto는 ProbeCrypto/AndroidKeyStore이며 이 검토자가 실기기 하드웨어 키 저장소나 실제 Android process death/권한 callback을 실행한 것은 아니다. 최신131개 테스트 통과와 release/build 결과는 상위 작업의 별도 실행 증거를 따르며, 검토자는 Gradle을 실행하지 않았다.

이 소스 판정은 전체 AI 비서 완성을 뜻하지 않는다. 실제 CalendarProvider 계정/서버 동기화, 선호 시계 앱의 EXTRA_SKIP_UI 호환과 울림, 기기 권한·잠금·절전, 새 APK 설치는 별도 검증 대상이다. runtime OCR·생성 AI/API·개인 웹 공지·실제 등하교 앱 원문 연결은 현재 지원/검증 완료 범위가 아니다. Alarm UI의 기존 source PASS 및 상위 visual91 판정은 그대로 유지한다.
