# 1.1 안정성 후보 검증 기록

feature/1.1-reliability-rebuild · versionCode 14 / versionName 1.1.0 · `origin/main`(1.0.2) 위에 DESIGN.md의 1.1 신뢰성 항목만 재적용한 후보다. 아래는 자동 검증·에뮬레이터·실기기·사용자 조사를 구분한 현재 상태다. 실행하지 않은 항목은 NOT_RUN으로 남긴다.

## 자동 검증 (PC, 최신 실행)

`cd android && ./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`

- 단위 테스트 XML 합계(testsuite 속성): tests=309, failures=0, errors=0, skipped=2 — 307 통과 + 2 skip. skip은 외부 공개 fixture opt-in 테스트다. Robolectric 화면 테스트는 프로덕션 Composable을 실제 렌더한다. `RingingNotificationContractTest`(3건, FSI 제거 회귀) 포함.
- `AccessibilityLayoutTest` 21/21 통과, `ProbeScreensRenderTest` 22/22 통과.
- lint 오류 0. debug APK·androidTest APK 조립 성공.
- `8de5897`에서 `--no-build-cache`로 fresh clean 재실행(1m06s): 동일 XML 합계(309/307/2/0/0), lint 오류 0, debug APK 29M·androidTest APK 2.5M 조립 성공.
- release 조립은 서명 정보 없이 실행하면 지정된 fail-closed 메시지로 실패한다.
  - `Release signing is not configured. Set MAMA_RELEASE_STORE_FILE, ...`
  - `Debug signing is never used for release builds.`
- release `BuildConfig.java`에 `NEIS_API_KEY` 필드가 없다(grep 0건). APK에 운영 비밀 키를 싣지 않는다.

## 조용한 음성 캡처·오늘 큐 (자동, 이번 사이클)

`8083878`(탭 기반 음성 캡처) 이후 Stage 4/5 — KUU의 "말하기 우선·모든 말을 할 일로 만들지 않음"을 MAMA 정책으로 번역했다(4분면 격자·ADHD 포지셔닝 복제 아님, 근거/확인 중심 유지).

- fresh clean `testDebugUnitTest` XML 합계: **tests=420, pass=418, skipped=2, failures=0, errors=0**(follow-up 라운드 +17: 메모 라우팅·캘린더 편집 payload·unresolved fail-fast·non-retryable UI·새 세션 saveRetryable 리셋·날짜 tri-state·리마인더 일관성). lint **오류 0 / 경고 101**(신규 파일에 추가된 경고 없음 — 기존 싱글톤/`.edit()` 패턴과 동일 계열). debug·androidTest APK 조립 성공.
- 발화 분류: `LocalAgentEngine.capture()`가 MEMO/TASK/REMINDER/CALENDAR/SHOPPING/QUESTION으로 분류하고 preview는 `오늘 챙길 일`·`나중에 확인`·`메모만`·`엄마 확인 필요`·`내려놓기/저장 안 함` 버킷 중 하나를 표시한다. 질문형은 저장 없이 인라인 답변만, 알람·날짜 불명확·파싱 불가는 확인 없이 쓰지 않는다(`CaptureClassificationTest` 13건).
- 리뷰 라운드 blocker 수정: `stopCapture()`는 `adapter.stop()` 성공 직후 즉시 THINKING+`onChanged()`(callback 지연/부재에도 LISTENING 잔류 불가), `begin()`의 `adapter.start()` 예외는 teardown → IDLE 복구 → fallback disclosure 표시이며 취소 후 다음 mic 탭으로 재시도 가능(`VoiceQuickCaptureTest` 회귀 2건 추가).
- 저장 경로: 미리보기 → 명시 일괄 확인 → `VoiceCaptureSaver` → `CaptureWriteCoordinator`(captureId 저널) → 기존 `AssistantTaskStore`/`CalendarGateway`. 저장 실패 시 transcript+edit 보존·재시도, `voice:<captureId>:<index>` source key로 실제 task write까지 멱등, 내려놓기는 아무것도 쓰지 않는다(`VoiceQuickCaptureActivityTest` 25건 — 권한 거부/재시도·on-device 부재 disclosure·generic 취소·빈 결과·중복 탭·회전·stale callback·동기 stop 회귀·start 예외 fallback 재시도·edit 보존·분류 실패 포함).
- `VoiceCaptureStore`: 확인된 발화의 **텍스트만** 기존 ProbeCrypto+SharedPreferences로 최대 200건·14일 보존(`VoiceCaptureStoreTest` — consent 없으면 fail-closed, 오디오를 담을 필드가 없음을 구조로 단언, `reset()`은 delete-all 경로에 연결됨).
- 위젯: open-Todo와 말하기 액션 PendingIntent를 분리(각각 명시 인텐트·request code 분리), 기본 렌더는 정적 리소스만 — 아이 이름·학교명·구체 할 일 없음(`VoiceEntryPointsTest`). QS Tile은 탭 시에만 `VoiceQuickCaptureActivity`를 명시 인텐트로 연다 — 자동 청취 없음.
- Today: `오늘은 이것만` 최대 3개 우선 큐 + 순서 버킷 `기한 지남` → `오늘·내일` → `이번 주` → `나중에 확인`(각 최대 5, 완전 경로는 `할 일 모두 보기`; needsReview는 날짜 버킷에 숨지 않음). `ProbeScreensRenderTest` 32/32 통과(버킷 순서·중복 없음·200% font·landscape·48dp·텍스트 상태·TalkBack 라벨 포함).
- 공지 체인: 테스트 전용 `SyntheticNotificationIngest` → 실제 allowlist/`NotificationRecordAssembler` 정규화 → APP_NOTIFICATION record → NoticeGrouping → `AutoActionCoordinator.routeFor` → applyAutomaticPlansFrom → evidence 첨부 task를 `NoticeCaptureChainTest` 9건으로 검증(같은 공식 문서 재수집 시 중복 없음, 제목만 같은 다른 공식 문서는 분리, 본문 동일해도 다른 공식 ID면 분리, 정보성 공지 자동 task 없음, 완료 상태가 resync에 유지, 학년 범위 불일치 공지는 history/evidence에 남지만 INELIGIBLE로 Today/Todo 승격 불가). allowlisted 5개 발신자(`학교종이`·`e알리미`·`하이클래스`·`키즈노트`·`클래스노트`)를 table-driven 합성 알림으로 실제 경계까지 커버한다 — 실제 사용자 알림 접근/삭제는 전혀 없다. HWP/HWPX·웹 추출 단위 커버리지는 기존 그대로.
- DESIGN.md canonical 갱신: KUU의 calm single-action rhythm만 참고(4-grid 금지 명시), 결과 세로 카드 버킷(오늘 처리/내일·이번 주/날짜 없음/확인 필요/메모·보류), soft sky·cream·green surface + navy/green 텍스트 대비(저대비 pastel label 금지), Today는 single-column attention stack(360dp·200% font·landscape에서 side-by-side bento 금지), source-state 어휘(verified body/notification-only/attachment-only/scope mismatch/auth required/unsupported/partial), 1.1은 primary child 1명, 폴더는 canonical item의 context view(별도 복제 저장소 아님). 구현 반영: Today attention stack을 세로 단일 열로 통합, `Clay.Sky` 토큰 추가, provenance label을 Ink/Muted 고대비로 상향.
- 에뮬레이터 계측 재실행(이번 follow-up 라운드 후): `emulator-5554`(mama_qa_api35, Android 15 · disposable) `connectedDebugAndroidTest` — 권위 XML **tests=28, pass=26, skipped=2, failures=0, errors=0**(skip 2개는 opt-in 공개 HWP/HWPX fixture). CI 베이스라인과 동일. `PublicSchoolLiveSyncDeviceTest`는 disposable API35 에뮬레이터에서만 실행 대상이며 opt-in skip 상태를 유지한다.
- **NOT_RUN**: 실기기 STT(마이크·RecognitionService 실동작·generic fallback 동의 UX의 실기기 확인), 실제 학교 앱/개인 e알리미 알림 수집, QS Tile의 실제 타일 추가·탭 UX, signed release, 부모 사용성 조사 — 이전과 동일하게 미실행이다. e알리미·하이클래스 개인 웹 자동 수집은 검증된 계약이 없어 UNSUPPORTED 유지. 물리기기 connected suite·앱 삭제·데이터 초기화·설정 변경은 수행하지 않았다.

## follow-up 리뷰 blocker (자동, 이번 라운드)

`6464ba2` 리뷰의 REQUEST CHANGES 항목을 구현했다. **Stage 4/5와 실제 알림 수집 E2E는 아직 완료로 주장하지 않는다** — 아래는 단위/합성 경계의 증거이고, 실기기 STT·실제 학교 앱 알림·signed release·부모 조사는 계속 NOT_RUN이다.

- A 멀티클로즈 캡처: `LocalAgentEngine.captureBatch()`가 그리고/또/쉼표/문장 경계로만 보수적으로 분절한다(child·날짜·시간·행동을 발명하지 않음). 각 clause는 세로 editable 카드(발화·행동·날짜 입력·disposition·버리기/유지)로 렌더되고, ambiguous/unparseable-date clause는 명시 해소 전까지 non-saveable이다. `batch.saveable`이 유일한 게이트 — 확인 전 write 0건을 Activity 테스트로 단언한다.
- B 내구 멱등 write: `CaptureWriteCoordinator`가 captureId 저널을 두고 clause별 write → record → complete를 단계별로 내구화한다. 실제 task write는 `sourceNotificationId="voice:<captureId>:<index>"`로 스토어 차원 dedup되므로 저널 마킹 실패 후 재시도도 중복을 만들지 않는다. record 저장 실패는 `Saved`가 아니라 retryable `Failed`를 반환한다(`CaptureWriteCoordinatorTest` 6건: 동일 captureId 재커밋·record 실패 후 재시도·mid-batch 실패 재개·저널 실패·calendar 라우팅·dropped/unresolved 미작성).
- C stop 콜백 경합: `adapter.stop()`이 동기적으로 `onResult/onError`를 호출하면 DONE/ERROR가 세션을 이미 해체하므로, 이후 THINKING 전이는 `adapter === a && state == LISTENING` 가드 아래에서만 일어난다(`stop with a synchronous final result lands in DONE not THINKING`).
- D 실제 합성 인제스천 심: `NotificationRecordAssembler`가 `ProbeRepository.capture`의 실제 정규화 단계로 추출됐고, 테스트 전용 `SyntheticNotificationIngest`가 실제 allowlist(`ProbeRules.canCapture`)→정규화→APP_NOTIFICATION record→grouping→`AutoActionCoordinator.routeFor`→`applyAutomaticPlansFrom` 경로를 구동한다. 5개 allowlist 패키지 table-driven(카탈로그 identity pin 포함). scope-mismatch는 실제 apply 경로에서 SUSPEND·zero task를 단언한다. app 알림에는 `SCHOOL_WEBSITE` 메타데이터를 위조하지 않는다 — web fixture만 `source:` identity를 쓴다.
- E Today 조직 계약: 오늘은 이것만(최대 3) 아래에 명시 순서 버킷 기한 지남 → 오늘·내일 → 이번 주 → 나중에 확인이 이어진다. needsReview task는 날짜 버킷에 숨지 않고 나중에 확인으로 이동한다. 완전 경로는 `할 일 모두 보기`. 200% font·landscape·48dp 타깃·텍스트 상태(비색상 의존)·TalkBack contentDescription을 render 테스트로 검증한다.
- F 분류 실패 명시: classifier 예외는 `classificationFailed` 상태로 분리되어 `다시 분류`/`메모로 저장` 명시 선택만 제공한다 — plan==null로 접어 저장 가능처럼 보이지 않는다.
- Robolectric 경계(정직 기록): 실제 Activity `setContent` 안의 editable `TextField`가 Robolectric에서 영구 `pendingMeasureOrLayout`을 일으켜 `waitForIdle`이 교착한다. 이를 우회하기 위해 Activity 테스트는 DONE 이후 controller 상태를 직접 단언하고(`compose.setContent`가 아닌 실 경로), editable UI 편집·버킷·a11y 계약은 `compose.setContent` 호스트의 `VoiceCaptureScreenLayoutTest`/`ProbeScreensRenderTest`가 커버한다. Compose idle 자체의 앱 논리 결함은 아니다.
- 검증 숫자는 아래 "자동 검증 (PC, 최신 실행)"의 최신 실행 행을 갱신한다.

### 계약 결함 수정 (자동, follow-up 라운드)

`c265744` 리뷰의 계약 결함 4건을 수정했다.

- **메모는 절대 task가 되지 않는다**: `CaptureWriteCoordinator`는 TASK/REMINDER/SHOPPING만 taskWriter, CALENDAR만 calendarWriter로 보낸다. MEMO clause는 per-clause write가 없고 `VoiceCaptureRecord`(확인된 transcript)에만 남는다 — Todo/Today 노출 0건. `LocalVoiceCaptureSaver.writeTask`에도 `require(intent != MEMO)` 방어선을 뒀다. 회귀: `memo clauses write only the record never a task`(writer 호출·record 내용·journal 완료 단언) + `memo-only batch writes the record without ever creating a task`(분류 실패 → `메모로 저장` → MEMO_ONLY clause가 saver까지 도달).
- **캘린더 편집이 실제 저장값이다**: `calendarPayloadFor`가 부모가 확인한 편집을 payload에 반영한다 — `clause.action`→제목, `clause.dueAt`→시작 시각(파싱된 duration 유지), zone/명시 종료는 파스 결과를 따른다. 회귀: `calendar payload honors the edits the parent confirmed`(편집 제목·시작·보존 duration·zone 단언).
- **내구 경계가 saveable을 강제한다**: `commit()`은 `batch.saveable`이 아니면 어느 write도 시작하지 않고 non-retryable로 fail-fast한다(kept-but-unresolved 부분 commit 금지, dropped만 있으면 허용). kept QUESTION도 pre-validation에서 거부한다. 회귀: `unresolved kept clause fails fast with zero writes`·`dropped clause alone does not block the commit`·`kept question clause fails fast before any write`.
- **retryable이 UI까지 간다**: `VoiceSaveResult.Failed.retryable` → `controller.saveRetryable` → 화면. retryable 실패만 같은 저장 재시도를 제공하고, non-retryable(setup·permission·destination)은 저장 버튼을 철회하고 "다시 시도해도 저장되지 않아요" 복구 안내를 표시한다 — false success/retry 없음. 회귀: `non-retryable save failure preserves edits but withdraws the retry`·`non-retryable failure hides the save retry and explains recovery`.

`6500b23` 리뷰의 계약 결함 2건을 수정했다.

- **non-retryable 실패가 다음 캡처를 오염시키지 않는다**: `finishSave(retryable=false)`가 `saveRetryable=false`를 남겨도 `startCapture()`가 새 세션 시작 시 `saveRetryable=true`로 리셋한다 — 실패한 배치는 계속 non-retryable이지만 새 마이크 캡처의 saveable preview는 저장 버튼을 되찾는다. 회귀: `non-retryable failure does not poison the next fresh capture`(실패 → 새 캡처 → saveable DONE → 저장 성공까지).
- **날짜 비우기는 정말 비운다**: clause 날짜는 tri-state다 — `dateEdited=false`이면 미편집(파싱된 proposal 사용), 명시적 편집/비우기는 `dateEdited=true`로 기록되어 제안 복원이 금지된다(typed `setClauseDate`와 programmatic `setClauseTimes` 둘 다 같은 플래그를 세팅 — `dateInput`은 provenance 텍스트일 뿐 숨은 커플링이 아니다). `taskWriteSpecFor`는 비운 날짜를 `dueAt=null`·`remindAt=null`로 기록한다(남겨진 몰래 알람 없음). CALENDAR clause가 시작을 비우면 `writable=false`/`needsReview`가 되어 저장 불가로 막히고, `calendarPayloadFor`는 `null`을 반환해 write가 닫혀 있다 — 낡은 파싱 시작을 절대 재사용하지 않는다. 회귀: `untouched task date follows the parsed proposal`·`edited task date wins over the proposal`·`explicitly blank task date persists null and clears the reminder`·`cleared calendar start is non-writable and the payload fails closed`(write 0건 단언)·`cleared date still commits as an undated task`·`programmatic null times clear the date without restoring the proposal`(controller 심).

`b2f6a61` 리뷰의 계약 결함 1건을 수정했다.

- **편집된 리마인더는 편집된 시각에 울린다**: 날짜를 명시적으로 편집한 clause는 낡은 `proposedRemindAt`을 절대 재사용하지 않는다 — `effectiveRemindAt`이 같은 tri-state를 따른다. REMINDER clause("알려줘" — 알림이 곧 목적)의 날짜 편집은 알람을 편집된 시각으로 이동하고, 비-REMINDER의 편집은 숨은 알람을 남기지 않는다(명시 `setClauseTimes` remind만 유지). 비우면 due와 remind가 함께 null이다. 미리보기도 같은 tri-state를 그린다 — 날짜 필드는 `dateInput ?: effectiveDueAt`을 표시하므로 `setClauseTimes(i,null,null)` 후에는 낡은 제안 날짜가 아닌 빈 필드를 보여준다. 회귀: `edited reminder date moves the alarm never reuses the stale proposal`·`edited non-reminder task drops the stale proposal reminder`(production `taskWriteSpecFor` 심)·`programmatic null times clear the date without restoring the proposal`(모델 + `clause-date-0` 빈 필드·편집 포맷 UI 단언).

## A–N 결함 수정 (자동, 신규)

`android/design/DEFECT_TRACKING_1.1.md`가 수락 기준과 테스트 매핑을 추적한다. 각 항목은 재현 테스트(수정 전 실패) → 최소 수정 → 회귀 검증을 거쳤으며, 커밋 `f16380c`(task/data 계층), `8846640`(UI/activity 계층), `11a9c86`+`7bffff2`+`1e62ab4`+`9e513f4`(기기 QA 안전 경계, 저널 내구성, cold-start replay, 알림 조회 실패 hard-fail)에 나뉘어 있다. 요약:

- A: 공식 문서 ID가 다른 공지는 fingerprint 앵커가 같아도 병합하지 않는다(disjoint-id·app/web 사본·완료 미승계 테스트).
- B: 불명확 revision/첨부 실패는 마지막 신뢰 상태를 유지하고 `needsReview`+`수정 공지 확인 필요`로 노출한다.
- C: 마감 임박·경과 공지도 required Todo를 만들어 overdue로 노출한다(과거 알람은 예약하지 않음).
- D: 무관한 revision은 사용자 snooze/reminder를 보존하고, 실제 기한 변경만 재예약한다.
- E: `ReconcileJournal`이 capture→task 사이를 내구화하고, startup replay는 멱등 재실행으로 논리적 1회 복구를 달성한다(중복 0). 실제 프로세스 kill의 atomic exactly-once는 검증하지 않았다.
- F: consume 후 notify 전 중단 상태(`activeAlarmOccurrenceId`)를 `restoreNow`가 복구한다.
- G: 오늘 헤드라인이 미수집/실패/확인된 일 없음/실제 완료를 구분한다.
- H: 연결 레인이 지원 범위·마지막 성공·미지원 이유를 표시하고 미지원 웹 행을 유지한다.
- I: UNKNOWN 대상 공지는 '엄마 확인 필요' 카드로 명시 확인 후 근거 연결 Todo를 만든다.
- J: 자동 생성/확정 문구를 실제 기능으로 통일하고 미구현 챗봇 문구를 제거했다.
- K: 저장 결과를 기다려 성공 시에만 대화상자를 닫는다(실패 시 입력·재시도 유지).
- L: exact alarm 재허용 브로드캐스트가 Todo reminder도 재스케줄한다.
- M: `ResetMarker`가 reset을 재시작 가능하게 하고 미완료 상태의 재가입을 거부한다.
- N: `ChildProfileScreen`·needs-review·오류 화면을 실제로 검증하고, 무이름 컨트롤(실제 Checkbox 결함 수정)과 상향/좌향 traversal 역행을 허용하지 않는다.

## Play 정책 대응 (자동, 신규)

- `USE_FULL_SCREEN_INTENT` 완전 제거: 전용 알람/통화 앱이 아니므로 Play의 전체화면 인텐트 정책 대상. 매니페스트 권한, `TaskReminderScheduler`·`BriefingReminders`의 `setFullScreenIntent`, `BriefingSettingsActivity`의 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 유도 UI와 '두 설정' 문구를 제거했다.
- 울림(alarm mode) 알림은 계속 `mom-assistant-alarm` 채널(IMPORTANCE_HIGH·alarm 사운드)·CATEGORY_ALARM·30초 timeout·contentIntent·완료/미루기 action·`FLAG_INSISTENT`(task 경로)를 유지한다 — heads-up 고우선 알림으로만 동작하고, 탭 시에만 `TaskAlarmActivity`/`BriefingActivity`가 열린다. 백그라운드 Activity 직접 시작은 없다.
- `alarmPermissions`는 이제 exact alarm 권한(`canScheduleExactAlarms`)만 요구한다 — exact alarm 거부 시 기존 inexact `setAndAllowWhileIdle` fallback이 유지된다.
- 회귀: `RingingNotificationContractTest`가 문자열 grep이 아니라 게시된 Notification 객체로 `fullScreenIntent==null`, contentIntent의 대상 Activity, 채널·category·timeout·action 라벨을 두 경로 모두 검증하고, `requestedPermissions`에서 FSI 부재와 exact-revoked inexact fallback(`TaskReminderSchedulerTest`)을 확인한다.
- 0.8 문서(`REVIEW_0.8_ALARM.md`·`ALARM_AND_CALENDAR_0.8.md` 등)의 FSI 언급은 당시 구현의 역사적 기록이다 — 1.1 현재 코드는 FSI를 사용하지 않는다.

## 접근성·소형 화면 회귀 (자동, 신규)

`AccessibilityLayoutTest` 21개: 360dp 너비·글꼴 200%·가로 방향에서 온보딩(시작/자녀/앱 선택/알림 허용/자녀 프로필), 오늘(오류 상태 포함), 할 일(needs-review 포함), 연결, 근거·수정내역 대화상자, 불확실 공지 확인 카드, 알람의 핵심 조작이 스크롤로 도달 가능하고 표시되는지 확인한다. 모든 클릭 가능 노드는 TalkBack 이름(문구·설명·상태 — 역할/토글 단독은 인정하지 않음), 최소 48dp 터치영역, 시각 순서와 일치하는 traversal 순서(표시된 컨트롤 기준 상향/좌향 역행 금지)를 검사한다.

이 회귀에서 실제 결함을 찾아 최소 변경으로 고쳤다.

- 할 일 완료 Checkbox의 터치영역이 24x24dp였다 → 48dp로 확장.
- 동의 행(ConsentRow)이 34dp였다 → 48dp로 확장.
- 본문 수준의 `TextButton`/`OutlinedButton`이 40dp였다 → 공통 `Modifier.minTouchTarget()`으로 48dp 보장.
- 하단 탭이 선택 상태를 TalkBack에 알리지 않았다 → `selectable`의 `Selected` 의미 부여.
- 읽지 않은 소식이 색 점으로만 표시돼 TalkBack이 읽지 못했다 → 카드에 `읽지 않은 소식` stateDescription 추가.
- 할 일 완료 Checkbox가 이름 없는 클릭 가능 노드였다 → `${task.text} 완료` contentDescription 추가.

## 에뮬레이터 계측 (실행됨)

`./gradlew :app:connectedDebugAndroidTest` · `mama_qa_api35` AVD · Android 15(API 35) ARM 이미지.

- JUnit XML(testsuite 속성, 권위) 기준: tests=28, failures=0, errors=0, skipped=2 — 즉 26 통과 + 2 skip(GitHub API 35 x86_64 CI에서도 동일한 XML 합계 확인).
- Gradle/UTP 콘솔은 같은 실행에서 `Finished 30 tests`처럼 XML보다 큰 수를 표시한다. XML testsuite 합계와 콘솔 표시는 일관되게 `tests + skipped`만큼 어긋나며(28 XML -> "30"), 이는 UTP가 skip을 테스트 케이스와 별도 이벤트로 중복 집계하는 표시 문제다. 완료 수를 과장하지 않기 위해 이 문서와 CI 요약은 항상 XML 속성을 사용한다.
- skip 2개는 외부 HWP/HWPX 공개 fixture를 `externalFilesDir/qa-input/`에 넣어야 하는 opt-in QA 테스트다. fixture 파일은 저장소에 없으므로 의도된 `assumeTrue` skip이다.
- NEIS 운영 동기화 비활성(`PRODUCTION_SYNC_ENABLED=false`) 검증과 온보딩 드라이버, 알람/스누즈/수집 흐름을 포함한다.

현재 구현 안전 경계(에뮬레이터·CI에서 검증 — 물리기기 증거 아님):

- collection 불변식: "collection gate가 닫힌 채 합성 allowlist 검증 후에만 test collection을 연다" — listener access가 이미 켜진 기기에서도 `collectionEnabled=false`이거나 allowlist 밖 패키지면 `canCapture`/`capture`가 거부함을 `nonAllowlistedSourceIsNeverCapturedWhileCollectionIsClosed`가 세 경계(게이트 닫힘·접근 허용 후·collection 활성 후)에서 입증한다. 원래 `enabled_notification_listeners` 값을 verbatim 캡처해 `finally`에서 복원하며, 빈 원본은 `settings delete`로 복원하고 복원값 verbatim 비교·QA 컴포넌트 잔존 검사가 실패 시 테스트를 hard-fail한다. 정리는 테스트가 만든 정확한 record id·task id·notification key에만 한정되며, 게시된 합성 알림은 지속된 record의 key를 await해 listener가 취소하고 비활성화를 확인한다 — 취소/연결 실패도 테스트 실패다. 전역 알림 열거·cancel-all·사용자 알림 접근/삭제는 없다.
- 파괴적 초기화 보호: `DeviceQaSafety.requireDestructibleState`가 모든 androidTest의 `deleteAll`/`reset`/설정 쓰기 경로를 감싼다. 물리 기기에서는 records/tasks/onboarding이 비어 보여도 다른 QA 상태(CalendarCommandStore, SourceSyncStateStore, 알람 등록 등)를 지울 수 있으므로 파괴적 테스트는 격리 에뮬레이터에서만 실행되고 물리 기기에서는 사유와 함께 skip된다(XML에 honest skip으로 기록). 비파괴 테스트(합성 HWP5·HWPX 기기 fixture)는 이 게이트를 타지 않아 물리기기에서도 실행된다 — SM-S926N 현재 코드 실행에서 실제로 통과했다(아래 실기기 절).

CI의 `instrumentation` 작업은 GitHub 호스팅 API 35 x86_64 에뮬레이터에서 같은 `connectedDebugAndroidTest`를 실행하고(KVM 가속, AVD 스냅샷 캐시, 부팅·작업 타임아웃), 실행 후 `androidTest-results/connected/*.xml`의 testsuite 속성을 합산해 tests/passed/failures/errors/skipped를 GitHub Step Summary에 기록한다. failures 또는 errors가 1 이상이면 작업이 실패하고, 실패 시 XML·HTML 보고서를 업로드한다. 콘솔 총계는 신뢰하지 않는다.

## 실기기 계측 (현재 코드 · SM-S926N)

`ANDROID_SERIAL=R3CX40BMCYV ./gradlew :app:connectedDebugAndroidTest` · Samsung SM-S926N, Android 16 / API 36, QA 빌드 1.1.0-qa(versionCode 14), HEAD `147d7a8`.

- JUnit XML(권위) 기준: tests=28, failures=0, errors=0, skipped=26 — 즉 **2 통과 + 26 skip**. 콘솔 `Finished 54 tests`는 UTP 중복 집계라 권위가 아니다.
- 통과한 2개는 게이트를 타지 않는 비파괴 테스트다: `syntheticHwpxExtractsKoreanAndChineseTextOnAndroidSax`, `syntheticHwp5AssetReportsEmbeddedBinaryPartialOnDevice` — 합성 HWP5 기기 fixture가 실기기에서 실행·통과함을 실측으로 확인했다.
- skip 26개는 파괴적 `requireDestructibleState` 게이트가 잡은 테스트(알림 파이프라인 E2E·`ReconcileDurabilityDeviceTest`·온보딩 드라이버·라이브/수집 경로 등)와 opt-in 공개 fixture다. 즉 물리기기에서 파괴적 레인이 실제로 skip됨을 코드 수준 가정이 아니라 실행으로 확인했다.
- 이것은 **전체 스위트 통과가 아니다** — 게이트가 잡은 파괴적 테스트는 실행되지 않았으므로 알림 수집→저장→Todo 생성 E2E와 저널 내구성은 물리기기 증거가 여전히 없다(NOT_RUN).
- 물리기기 UI 스모크는 추가하지 않았다: instrumentation target process는 테스트 본문이나 사전 조건 검사보다 먼저 `ProbeApplication.onCreate`를 실행하고, 그 시점에 `candidate_feedback` 마이그레이션 clear/commit, `WorkManager` enqueue, `SourceSyncScheduler.schedulePeriodic`·`scheduleLearnedWindows` 같은 쓰기가 발생한다. MainActivity 시작 경로도 ON_RESUME에서 `cleanupExpired`(record·tombstone 삭제)와 `enqueueForegroundStale`(source state 갱신)을 무조건 수행한다. 따라서 "아무것도 쓰지 않는" 물리기기 UI 스모크는 증명할 수 없어 만들지 않았다.
- 별도 설치 검증: `app-debug.apk`를 격리 패키지 `kr.mom.probe.qa`로 설치(기존 `kr.mom.probe` 데이터 미접촉) — dumpsys versionCode=14·versionName=1.1.0-qa, cold launch 560ms·activity resumed 유지·crash/ANR/FATAL 없음(플랫폼 HAL·deprecation 경고만), 온보딩이 스크린샷으로 정상 렌더링됨.
- secrets 부재 확인: `assembleRelease`가 `:app:verifyReleaseSigning`에서 지정된 두 문장("Release signing is not configured...", "Debug signing is never used for release builds.")으로 fail-closed.

### FSI 제거 후 실기기 재확인 (현재 코드, 2026-09-27)

같은 SM-S926N, FSI 제거가 포함된 fresh `app-debug.apk`를 격리 패키지 `kr.mom.probe.qa`에 `install -r`로 갱신(기존 `kr.mom.probe`는 dumpsys versionCode=10/versionName=0.9.0-agent 그대로 — 읽기만 수행, 데이터 미접촉).

- 설치 후 `dumpsys package kr.mom.probe.qa`의 선언 권한 목록에 `USE_FULL_SCREEN_INTENT` 없음 — API 36 기기에서 매니페스트 제거를 실측 확인.
- cold launch: `kr.mom.probe.qa/kr.mom.probe.MainActivity` resumed 유지, logcat에 FATAL/ANR/crash 없음.
- 알림 게시 자체는 실기기에서 실행하지 않았다(NOT_RUN): 모든 알림 트리거(receiver)가 non-exported라 외부에서 앱의 notify 경로를 건드릴 수 없고, `POST_NOTIFICATIONS` 미부여 상태라 허가 자체가 설정 변경이 된다. Notification 객체 계약은 `RingingNotificationContractTest`(Robolectric)가, OS 파이프라인 게시는 에뮬레이터 `NotificationPipelineDeviceTest`가 증거다.
- 증거 경계: 위의 물리기기 connected XML 28/2/26은 FSI 제거 이전 `147d7a8` 기준 결과이며 현재 HEAD의 전체 스위트 실행이 아니다 — FSI 제거 후 전체 connected suite는 물리기기에서 NOT_RUN이다(아래 selected-test 증거 참고).

### 현재 HEAD 물리기기 selected-test 실행 (2026-09-27, `8de5897`)

같은 SM-S926N에서 class filter로 **비파괴 합성 테스트 2개만** 선택 실행했다(`syntheticHwpxExtractsKoreanAndChineseTextOnAndroidSax`, `syntheticHwp5AssetReportsEmbeddedBinaryPartialOnDevice`).

- JUnit XML(권위): **tests=2 / 2 pass / 0 skip / 0 fail / 0 error** — 현재 HEAD의 selected-test 증거이며 **전체 connected suite 통과가 아니다.** 게이트된 파괴적 레인은 실행하지 않았다.
- **도구 경계(중요)**: Gradle `connectedDebugAndroidTest`가 종료 정리 과정에서 target QA 패키지 `kr.mom.probe.qa`를 **자동 제거**했다 — "QA 데이터 보존" 전제로 물리기기 connected 테스트를 실행하면 안 된다. QA 앱 데이터는 이 실행으로 보존되지 않았다. 향후 물리기기 connected 테스트는 disposable QA state/격리 전용 기기에서만 실행한다.
- 실행 후 `app-debug.apk`를 `adb install -r`로 재설치 — cold launch Status ok, TotalTime 564ms, dumpsys versionCode=14·versionName=1.1.0-qa. 정식 `kr.mom.probe`는 versionCode=10/versionName=0.9.0-agent 그대로, 건드리지 않았다.
- 알림 게시 자체는 실기기에서 실행하지 않았다(NOT_RUN) — 위 FSI 재확인 섹션의 사유와 동일.

### 과거 baseline (역사적 기록, 현재 코드 증거 아님)

`ANDROID_SERIAL=R3CX40BMCYV ./gradlew :app:connectedDebugAndroidTest` · 같은 SM-S926N, `DeviceQaSafety` 도입(`11a9c86`)·강화(`7bffff2`) 이전 커밋에서 수행(실행일 미기록, 두 커밋 모두 이보다 최신).

- JUnit XML(권위) 기준: tests=18, failures=0, errors=0, skipped=2 — 즉 16 통과 + 2 skip. 콘솔은 `Finished 20 tests`로 표시됐다(UTP 집계 문제; 18+2=20).
- 당시 스위트는 온보딩 드라이버, 알람/스누즈 흐름, `PRODUCTION_SYNC_ENABLED=false` 검증 등 기존 QA 경로를 커버했다. 알림 수집→저장→Todo 생성 E2E, 합성 HWP5 기기 fixture, 저널 내구성, 안전 게이트는 그 이전 스위트에 없었다.
- QA 앱 cold launch 후 프로세스 유지·즉시 crash/ANR 없음을 확인했다.
- 대상은 전용 QA 설치였으므로 `deleteAll`이 사용자 release 데이터를 건드리지 않았다.

남은 실기기 항목(여전히 NOT_RUN):

- OS 경유 합성 알림 E2E(`NotificationPipelineDeviceTest`)·`ReconcileDurabilityDeviceTest` — 파괴적 게이트(`requireDestructibleState`)가 있어 물리기기에서는 실행되지 않는다. skip 자체는 현재 코드 실행에서 관측됐으나 두 레인의 물리기기 동작 증거는 없다.
- 현재 HEAD의 **전체** 물리기기 connected suite — `8de5897`에서는 class-filtered 2개만 실행했다. 게다가 `connectedDebugAndroidTest`가 QA 패키지를 자동 제거하므로, disposable QA state가 보장된 격리 기기 없이는 전체 suite를 물리기기에 돌리지 않는다.
- 실제 학교·학원 앱 알림 수집과 lane별 상태 정확성 — 실제 부모 기기 필요.
- 알림 접근 권한 회수·재부여 흐름.
- 재부팅·프로세스 종료 후 할 일·스누즈(연속 4회 이상)·읽음 상태 유지.
- 잠금화면 개인정보 보호(`FLAG_SECURE`)와 원본 알림 숨김(기본 OFF, 경계 유지).
- 완료·미루기·알람 울림의 실기기 동작.
- 24시간 이상 사용 중 crash/ANR 감시.

## 1.1 출시 체크리스트

스토어 제출 전 수동·외부 절차는 `android/design/RELEASE_CHECKLIST_1.1.md`에 항목별로 분리했다(서명 검증, versionCode/versionName, 권한, Data Safety, 개인정보처리방침, 데이터 보관·삭제, 백업, 롤백, 알림 접근 고지, 제출 전 수동 점검). 각 항목은 실행 증거 없이 체크하지 않는다.

## signed release (NOT_RUN)

- `signed-release` CI 작업은 `MAMA_RELEASE_KEYSTORE_BASE64`·`MAMA_RELEASE_STORE_PASSWORD`·`MAMA_RELEASE_KEY_ALIAS`·`MAMA_RELEASE_KEY_PASSWORD` secrets가 모두 있을 때만 keystore를 임시 파일로 복원해 `assembleRelease`·`bundleRelease`를 빌드하고 `apksigner`/`jarsigner`로 서명을 검증한다. 자격 증명은 저장소에 기록하지 않는다.
- secrets가 없는 환경에서는 이 작업이 건너뛰고, `verify` 작업의 음성 검사가 계속 fail-closed 계약을 강제한다. 현재 저장소에는 이 secrets가 설정되어 있지 않아 성공 경로는 실행한 적이 없다.
- secrets 부재 상태의 로컬 `assembleRelease`는 `:app:verifyReleaseSigning`에서 지정된 두 문장("Release signing is not configured...", "Debug signing is never used for release builds.")으로 실패함을 재확인했다 — fail-closed 계약 유지.

## 실기기 수동 QA 절차 (알림 청취자)

CI/개발 에뮬레이터에서 알림 청취자 권한의 자동 부여가 항상 가능한 것은 아니다. 수동 절차:

1. QA 빌드 설치: `adb install app/build/outputs/apk/debug/app-debug.apk`(`kr.mom.probe.qa`).
2. 권한 부여(자동): `adb shell cmd notification allow_listener kr.mom.probe.qa/kr.mom.probe.service.ProbeNotificationListener`.
   - 자동 부여가 거부되는 기기/빌드에서는: 설정 → 알림 → 특수 앱 접근 → 알림 접근 → MAMA(QA) 허용.
3. 계측 실행: `ANDROID_SERIAL=<id> ./gradlew :app:connectedDebugAndroidTest`.
4. `NotificationPipelineDeviceTest`가 청취자 부여를 자체적으로 시도하고, 실패 시 정직하게 skip한다. 게시 전에 listener service 바인딩(`ProbeNotificationListener.instance`)을 await한다 — 설정값 허용만으로는 서비스가 연결되지 않을 수 있다. 테스트가 post한 합성 알림(`회신안내`)은 캡처된 record의 `notificationKey`를 await해 listener가 정확히 취소하고, 해당 key가 active에서 사라졌음을 확인한다 — 잔존 시 테스트 실패. listener grant는 원래 설정값을 verbatim 캡처해 복원·검증하므로 테스트 후 기기는 이전 상태로 돌아간다(빈 원본은 `settings delete`로 복원).
5. 실서비스 알림(학교 앱 등)으로의 수집 검증은 별도 실기기 절차로, 여전히 NOT_RUN이다.

## HWP/HWPX fixture 커버리지

- 결정론적 합성 fixture: `SyntheticHwp5`(단위 테스트 소스셋)가 CFB/OLE·BodyText·BinData 스트림을 코드로 생성한다. 저작권·개인정보가 없고 원격 다운로드에 의존하지 않는다.
- `DocumentTextExtractorTest`는 합성 HWP5(압축·비압축)·HWPX로 기본 파서 경로를 skip 없이 검증하고, `syntheticHwp5EmbeddedBinaryReportsPartialAndSkipsBinData`가 `BinData/` 스트림 존재 시 `EMBEDDED_BINARY_SKIPPED`+PARTIAL을 확인한다.
- `SchoolWebsiteClientTest.extractsSyntheticHwp5AttachmentThroughRealExtractor`는 실제 추출기로 합성 첨부를 통과시켜 동일 계약을 커버한다.
- 기기에서는 `androidTest/assets/synthetic-parent-notice.hwp`(위 생성기로 만든 결정론적 파일, sha256 고정)가 skip 없이 실행된다.
- 실제 공개 문서 fixture(`device-qa/public-fixtures/`)는 저작권 검토 대상으로 opt-in을 유지한다 — `assumeTrue` skip 2건은 의도된 상태다.

## API 36 호환성 정리

- `AppNotificationAccess`: deprecated `AppOpsManager.unsafeCheckOpNoThrow` → `checkOpNoThrow`(API 29+, minSdk 33이라 안전). 실패는 여전히 catch에서 null("알 수 없음")로 처리해 "차단"으로 오인하지 않는다.
- `ClayTheme`: deprecated `Window.statusBarColor`/`navigationBarColor`/`isStatusBarContrastEnforced`/`isNavigationBarContrastEnforced`를 API 35+(edge-to-edge 강제로 무시되는 범위)에서 읽지도 쓰지도 않도록 `legacyBarStyle`/`applyClayBarBackground`/`restoreBarStyle`로 격리했다. API 34 이하에서는 동일 동작을 유지하고, 테마 복원 경로도 이전 상태를 그대로 되돌린다. 밝은 아이콘 플래그(`isAppearanceLight*`)는 전 API에서 `WindowCompat` 컨트롤러로 유지.

## 사용자 조사 (NOT_RUN)

- 실제 부모 5명 이상, 7~14일, 중요 공지와 대조한 알림 300건 이상 수집 실험은 시작하지 않았다. G0 완료로 주장하지 않는다.

## 의도적 미구현·비활성

- 나이스 운영 동기화: 운영 API 키를 APK에 넣을 수 없고 안전한 서버 프록시가 없으므로 `NeisPublicClient.PRODUCTION_SYNC_ENABLED=false`다. 연결·수동 조회 UI는 비활성이며 `안전한 운영 연동 준비 중 · 현재 자동 조회 미지원`으로 표시한다.
- 원본 알림 숨김: 기본 OFF, `NotificationHidingPolicy` 경계를 유지한다. 실기기 안전 근거가 생기기 전까지 켜지 않는다.
- e알리미·하이클래스 웹의 개인 공지 자동 조회: 미구현이며 UNSUPPORTED로 표시한다.

## 한계

- Robolectric 화면 검증은 실제 Compose 렌더이지만 물리 기기 캡처가 아니다. OS 키보드·권한 화면·제조사 알림 UI는 실기기에서 다시 확인한다.
- CI 에뮬레이터 실행은 설치·권한 왕복을 대체하지 않는다.
