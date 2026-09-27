# 14 — 라비 음성 캡처·잠금화면 설계 (P0 설계 확정본)

상태: **Stage 0 설계 문서 — 구현 착수 전 Codex 재검토 대기**
기준: `feature/1.1-reliability-rebuild` @ `b8f706c` 이후 문서 전용 단계
원칙: 일반 챗봇이 아니라 **구조화된 로컬 음성 캡처·질의** 기능. 토끼는
기능 상태 표시·진입점 표현일 뿐 별도 에이전트 런타임이 아니다.
새 엔진·DB·외부 의존성 금지. 기존 `LocalAgentEngine`,
`ScheduleCommandParser`, `AssistantTaskStore`, `CalendarGateway`, 기존
reminder/alarm 경로를 재사용한다.

## 범위 분할

- **P0 (이번 구현 범위)**: AgentIdentity 중앙화, 토끼 상태
  `IDLE/LISTENING/THINKING/DONE/NEW_INFO`, VoiceQuickCapture 진입 화면,
  STT, LocalAgentEngine 연결, 위젯 "말하기" 액션.
- **P1 (설계만, 구현 안 함)**: 홈 화면 재구성, QS Tile, 잠금화면 상세
  동작 흐름, 지속형 브리핑 알림 재설계.
- **P2 (연구만)**: Android 16 `ProgressStyle`/Live Update — 일반 task/
  reminder 알림에 부적합(진행 중 작업 표현용이 아님)하여 보류. 고급
  애니메이션·에이전트 UI.
- **NOT IMPLEMENTED**: hotword/상시·백그라운드 청취, 오디오 파일 저장,
  일반 대화·외부 지식 답변, 앱이 직접 운영/호출하는 외부·유료 클라우드
  STT와 앱의 오디오 업로드.

---

## 1. Agent Identity

### 결정
- `agent/AgentIdentity`에 비서 표시명·wake-name 목록·마스코트 타입을
  중앙화한다. 표시명 기본값은 **라비**.
- **계약/리소스 분리(중요)**: Android XML·manifest·위젯 문자열은 Kotlin
  객체에서 런타임 파생할 수 없다. 따라서 `AgentIdentity`가 유일한
  진실 원천이라는 허위 주장을 하지 않고, **Kotlin 계약**
  (`AgentIdentity.displayName` 등)과 **리소스 미러**
  (`strings.xml`, `assistant_widget_strings.xml`)를 분리해 선언하고,
  둘의 일치를 **계약 테스트**로 보장한다.
- 표시 이름을 바꾸는 것은 카피/리소스 수준 변경일 뿐이다.
  **rename 금지 대상**: 알림 채널 ID(`mom-assistant-alarm`,
  `mom-briefing`, `mom-task-reminders`, `mom-action-candidates` — ID를
  바꾸면 사용자 알림 설정이 리셋됨), prefs 키(`assistant_tasks` 등),
  저장된 task 텍스트·raw 데이터, `ProbeCrypto` 태그.
- Wake-name strip: `LocalAgentEngine`의 기존 `removePrefix("모모야 ")`
  계열을 **긴 이름 우선 + 토큰/공백 경계** 규칙으로 일반화한다.
  목록 `[라비야, 모모야, 라비, 모모]`를 길이 내림차순으로 검사하고,
  이름 뒤에 공백/구두점이 오는 경우만 제거해 "모모랜드" 같은 일반어를
  훼손하지 않는다. 모모/모모야 입력은 계속 유효하다.
- 계약 테스트: `AgentIdentityTest` — wake-name strip 양방향,
  `AgentIdentity.displayName`과 `strings.xml`의 라비 표시 문자열 일치
  단언, 리소스 미러 드리프트 방지.

## 2. Rabbit Mascot

- 상태: `IDLE`, `LISTENING`, `THINKING`, `DONE`, `NEW_INFO`.
- Compose Canvas 또는 VectorDrawable로 구현(외부 이미지 의존성 없음).
  크림/베이지 몸, 연살구 inner ear, 세이지 포인트, 차분한 표정.
- 각 상태는 **시각 + 텍스트 라벨 + `stateDescription` semantics**를
  함께 제공한다(색/형태만으로 상태를 전달하지 않음).
- 컴포넌트 경계를 상태 enum 기준으로 유지해 나중에 PNG/Lottie로
  교체해도 호출부가 변하지 않게 한다.
- 위젯용 정적 VectorDrawable(`assistant_widget_rabbit.xml`)은 별도
  리소스로 두고 기존 `assistant_widget_momo.xml` 리소스는 유지한다.

## 3. Voice Quick Capture (P0 진입점)

- `voice/VoiceQuickCaptureActivity`: non-exported, dialog/sheet 스타일.
  명시 Intent로만 진입(위젯 말하기, P1 홈 버튼, P1 QS Tile).
- 진입 즉시 마이크를 켜지 않는다. 화면 내 마이크 버튼 **탭**이 유일한
  청취 시작 경로이며, 그 탭 이후에만 `RECORD_AUDIO` 런타임 권한을
  요청한다. 거부 시 안내 문구와 재시도 경로를 보여주고 청취하지 않는다.
- 상태 머신: `IDLE → (tap+grant) LISTENING → THINKING → DONE`
  / `ERROR`. 각 상태가 토끼 상태와 1:1로 매핑된다(`NEW_INFO`는
  잠금화면/브리핑 재진입 시 기존 NEW_INFO에만 사용).
- 회전/재생성 시 **자동 재청취 금지**: 재생성되면 IDLE에서 사용자의
  재탭을 기다린다. 마이크 사용 중에는 화면에 청취 상태를 표시한다.
- 백그라운드 청취·hotword·오디오 파일 저장 없음. 결과는 recognizer
  콜백의 텍스트만 사용한다.

## 4. STT

- API 기준(정정된 명칭):
  `SpeechRecognizer.isRecognitionAvailable(context)`,
  `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)`,
  `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`,
  `SpeechRecognizer.createSpeechRecognizer(context)`.
  `isOnDeviceRecognitionAvailable`/`createOnDeviceSpeechRecognizer`는
  API 31+이고 `isRecognitionAvailable`/`createSpeechRecognizer`는
  더 오래된 API다 — 어느 쪽이든 minSdk 33에서 모든 호출을 사용할 수
  있다.
- 선택 정책: `isOnDeviceRecognitionAvailable(context)`가 true이면
  `createOnDeviceSpeechRecognizer(context)`를 사용한다. 생성이
  실패하거나 `UnsupportedOperationException` 등이 발생하면
  `createSpeechRecognizer(context)`로 fallback한다. 두 경로 모두
  사용할 수 없으면 `ERROR` 상태로 안내한다.
- **Fallback 정직성**: `createSpeechRecognizer`는 OEM/사용자가 선택한
  시스템 `RecognitionService`를 사용하며, 그 구현에 따라 네트워크를
  쓸 수 있다. 따라서 fallback 경로로 진입하기 전 UI에서 온디바이스
  인식이 불가하며 시스템 음성 인식이 사용될 수 있음을 알리고 사용자가
  취소할 수 있어야 한다. 앱 자체는 어떤 경로에서도 오디오 파일/원시
  오디오를 저장하거나 직접 전송하지 않는다.
- 수명주기: listener를 `startListening` **이전**에 등록한다. 생성·
  startListening·stopListening·cancel·destroy 호출은 모두
  **main thread**에서 수행한다. Activity `onDestroy`에서 `destroy()`를
  보장한다.
- `<queries>`에 `android.speech.RecognitionService` intent를 선언한다
  (미선언 시 `isRecognitionAvailable`이 false를 반환할 수 있다).
- 테스트 경계: `SpeechRecognizer`를 직접 참조하지 않는
  `SpeechRecognizerFactory`/`RecognizerAdapter` 인터페이스를 두고,
  on-device 우선 선택·fallback·listener 선등록·destroy 호출을
  Robolectric/단위 테스트로 검증한다.

## 5. Intent 라우팅 — 소유권 경계

- **분류는 `LocalAgentEngine`이 소유한다.** `VoiceIntentRouter`는
  두 번째 분류 엔진이 되면 안 되며, context 조립→확인 sheet→저장
  orchestration만 담당한다.
- `LocalAgentReply`를 **하위호환 기본값이 있는 typed `AgentIntent`
  (MEMO/TASK/REMINDER/CALENDAR/SHOPPING/QUESTION)** 로 확장한다.
  기존 필드(`proposedTask`, `scheduleCommand` 등)는 유지하고 intent
  타입은 기존 판정 결과로부터 유도되므로 현재 테스트와 호환된다.
- 귀착 표:

  | Intent | 분류 근거(엔진) | 저장 계약 |
  |---|---|---|
  | TASK | 기존 task 제안 판정 | `AssistantTaskStore.addTask`(확인 후) |
  | REMINDER | task 제안 + 시간/알림 표현 | `addTask(remindAt=…)` → `TaskReminderScheduler.sync` |
  | CALENDAR | `scheduleCommand == CalendarCreateCommand` | `CalendarGateway.saveEvent`(확인 후) |
  | SHOPPING | TASK의 표시 타입 구분 | 동일 `AssistantTaskStore`(카테고리/표시만 구분) |
  | MEMO | 명령형 없는 서술 | `addTask` undated(확인 후) |
  | QUESTION | 기존 조회류 판정 | 저장 없음, 로컬 컨텍스트 답변만 |

- 외부 알람(`AlarmClock.ACTION_SET_ALARM`)은 **별도 우회 분류를 만들지
  않고** 기존 `scheduleCommand`의 `AlarmRequestCommand` 결과로만
  취급한다.
- 모호성: `ScheduleCommandParser`의 `ScheduleClarification`(날짜 복수,
  AM/PM, 과거 시각, 미지원 표현 — 예: "다음주 화요일"은 현재 parser가
  상대날짜 오늘/내일/모레만 지원)은 **clarification/no-write**로
  반환한다. 임의 보정·자동 저장 금지. Parser 확장은 별도 단계.
- QUESTION은 저장된 task/공지/agenda/소스 상태만 답한다. 일반 지식·
  미지원 소스(e알리미 개인 공지, HiClass 등) 접근을 주장하지 않는다.

## 6. 쓰기 경계 — 확인 전 저장 금지

- task·reminder·calendar·memo·shopping 모두 **명시 사용자 확인 전
  저장·알람 예약·외부 Intent 실행 금지**. 확인 sheet는 STT 결과
  transcript와 해석된 action을 미리 보여준다.
- raw transcript는 기본적으로 **메모리 preview**만 한다. 사용자가
  쓰기를 확인한 경우에만 encrypted `VoiceCaptureRecord`(id,
  rawTranscript, intentType, createdAt, linkedRecordId)로 저장하고
  연결된 task/calendar 레코드와 링크한다. **취소·QUESTION·
  clarification은 기본 비저장**이다.
- `VoiceCaptureRecord` 저장소는 Room이 아니라 `AssistantTaskStore`와
  같은 **encrypted SharedPreferences 패턴**(`ProbeCrypto`, 별도 태그/
  키)으로 한다 — Room 스키마 버전·migration을 건드리지 않기 위함.
  새 의존성 없음.
- 이 store는 기존 계약을 따른다: consent/onboarding 미완료 시 쓰기
  불가(`requireConsent` 경계와 동등한 게이트), delete-all 시 함께
  삭제, 재설치/재온보딩(rejoin) 후 재사용 가능, retention 상한
  (건수·기간 — 기존 RETENTION_MS 정책과 정합), 저장 실패 시 입력을
  보존하고 재시도 경로 제공, 확인 sheet 중복 제출 방지.
- 이미 확인된 사실(구현 검증됨): `AssistantTaskStore.save()`는 기본
  `sync=true`로 `TaskReminderScheduler.sync`를 호출하므로 `addTask`만
  호출해도 reminder 예약이 동기화된다. 단 `save()`는 `load()`가 먼저
  호출돼야 하고 consent 게이트를 요구하므로 voice 경로는 진입 시
  `load()`와 onboarding 상태를 확인해야 한다.
- 캘린더 권한·선택 캘린더가 없으면 `CalendarGateway.saveEvent`의
  실패 결과를 그대로 안내한다(fail-safe, 자동 재시도·우회 없음).

## 7. Widget (P0: 말하기 액션만)

- `assistant_widget_layout.xml`에 두 번째 버튼(말하기)을 추가하고,
  `AssistantWidgetProvider`에서 별도 `PendingIntent`로
  `VoiceQuickCaptureActivity`를 연다. PendingIntent는 생성자 identity로
  실행되므로 non-exported activity가 안전하다.
- **PendingIntent identity/액션 분리**: 기존 root intent(오늘 보기,
  `EXTRA_OPEN_TODO` + requestCode 4200)와 requestCode를 다르게 한다.
  기존 동작은 유지한다.
- 위젯은 privacy-safe default를 유지한다 — task/알림 본문 내용을
  홈 화면에 표시하지 않는다.

## 8. Quick Settings Tile (P1 — 설계만)

- `TileService` 신규. `onClick`에서 API 34+ 규칙대로
  `PendingIntent` 기반 `startActivityAndCollapse`로
  `VoiceQuickCaptureActivity`를 연다. P0에서 구현하지 않는다.

## 9. Lockscreen privacy (P1 — 설계만)

- 알림은 기본 `VISIBILITY_PRIVATE` + redacted `publicVersion`
  (기존 `AssistantAlertNotifier`/`TaskReminderScheduler` 패턴 재사용).
  잠금화면에서 task/공지 본문·자녀·학교 정보를 노출하지 않는다.
- 민감 action(삭제, 완료 처리, 캘린더 쓰기 확인 등)은 잠금 해제/
  인증 후에만 수행한다.

## 10. Persistent briefing notification (P1 — 설계만)

- `AssistantAlertNotifier`가 입증한 패턴(sourceId 기반 stable
  notification id + fingerprint dedup + 갱신)을 "오늘 요약" 단일
  알림 컨테이너로 확장하는 방향만 문서화한다. 새 채널 ID를 만들지
  않고 기존 채널 재사용을 우선 검토한다.

## 11. FSI 유지

- `0595d32`의 Play 정책 대응(`USE_FULL_SCREEN_INTENT` 제거,
  `setFullScreenIntent` 제거)을 유지한다. 라비의 어떤 경로도
  FSI·백그라운드 Activity 직접 시작을 재도입하지 않는다.

## 12. Stage 계획·검증 매트릭스

| Stage | 내용 | 검증 |
|---|---|---|
| 0 | 본 문서 확정 | docs-only |
| 1 | AgentIdentity + wake-name 일반화 + 리소스 미러 | `AgentIdentityTest`, 기존 `LocalAgentEngineTest` 회귀, 문자열 계약 테스트 |
| 2 | 토끼 상태 컴포넌트 + 위젯 drawable | 상태 semantics 테스트, render 회귀 |
| 3 | VoiceQuickCapture + STT factory/adapter | 권한 거부, factory fallback, listener 선등록, destroy, 회전 시 비재청취 |
| 4 | 라우팅 + 확인 + VoiceCaptureRecord | intent 매핑 샘플, clarification no-write, 확인 전 저장 없음, delete-all 동반 삭제 |
| 5 | 위젯 말하기 PendingIntent | 두 PendingIntent 분리 단언, 기존 root 동작 회귀 |

- 각 Stage 통과 조건: targeted unit green + fresh clean
  `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  :app:assembleDebugAndroidTest` 통과 + XML 합계 기록
  (baseline: unit 309/307/2/0/0, lint 0).
- 최종 Stage에서 API35 에뮬레이터 `connectedDebugAndroidTest`
  (baseline XML 28/26/2/0/0).
- **물리기기 connected 테스트·실사용자 데이터·파괴적 테스트 금지**
  (`connectedDebugAndroidTest`가 QA 패키지를 자동 제거하는 경계 준수).
- 실기기 STT 수동 QA는 별도 **NOT_RUN/PENDING**으로 기록한다 —
  에뮬레이터/Robolectric으로는 실제 인식 경로를 입증할 수 없다.

## 13. 미구현 명시

- hotword·상시 청취·백그라운드 녹음·오디오 파일 저장: 구현하지 않음.
- 일반 챗봇·외부 지식 답변·외부 의존성: 구현하지 않음. 앱이 직접
  운영/호출하는 외부·유료 클라우드 STT와 앱의 오디오 업로드는 없으나,
  시스템 `RecognitionService` fallback은 OEM/사용자 선택 구현이며
  네트워크를 사용할 수 있어 위 §4의 사전 안내·취소 정책을 따른다.
- QS Tile·잠금화면 상세·지속 브리핑·홈 재구성: P1 설계만.
- Android 16 `ProgressStyle`: P2 연구 — 일반 task/reminder 알림에
  부적합.

## 14. 향후 hotword 검토

- 이번 버전에는 hotword를 도입하지 않는다. 향후 검토 시에도
  상시 마이크·클라우드 인식을 요구하는 방식은 배제하고, 기기 내
  always-on keyword spotting이 개인정보 원칙과 충돌하는지를 별도
  문서로 평가한 뒤 결정한다.

## 15. 변경 근거 로그

- DESIGN.md "conversational mascot UI가 아닌" 원칙과 라비 요구의
  충돌 → 라비를 구조화된 로컬 캡처/상태 진입점으로만 예외화
  (DESIGN.md §8 명문화).
- DESIGN.md "Assistant name: 모모" → 라비, 단 레거시 wake-name
  모모/모모야 입력 호환 유지, 저장 데이터·채널 ID·prefs 키 rename
  금지.
- AgentActivity가 1.1에서 제거된 상태이므로 라비는 "죽은 UI 재부활"이
  아니라 도달 경로가 있는 새 진입점으로 설계.
