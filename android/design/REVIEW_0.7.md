# 0.7 Astra 최종 검토 — 1차

검토자: GPT-6 Astra High. 대상: GPT-5.5 구현 소스. 판정: **수정 필요 — 현재 빌드 승인 보류**.

이 문서는 2026-09-15 검토 중 읽은 소스의 1차 판정이다. 구현자가 동시에 수정 중이므로 아래 줄 번호는 해당 읽기 시점 기준이며, 후속 수정의 승인을 뜻하지 않는다. 소스·테스트를 읽고 분기와 데이터 흐름을 추적했다. Gradle, APK, 실계정, 휴대폰을 실행하지 않았다. 66개 JUnit 통과는 상위 작업에서 제공한 결과이며, 아래 실패 경로를 검증한 증거가 아니다.

## 수정이 필요한 사항

### 1. P1 — 보이지 않은 브리핑까지 열람 처리

- 위치: `BriefingReminders.kt:108-116`, `BriefingActivity.kt:77-83,108-110`.
- 재현: 동일한 준비 행동이 있는 새 공지 4개를 수신 시각 t1<t2<t3<t4로 보관한다. 정렬 결과 t4,t3,t2만 화면에 나타나면 `seenTimestampFor`는 t4를 반환한다. 이후 `receivedAt > seen` 때문에 보이지 않은 t1도 영구히 새 브리핑에서 제외된다. 기한순 정렬에서도 같은 문제가 발생한다.
- 기대: 실제로 보여준 **공지 개정 ID**만 열람 처리. 최대 타임스탬프로 표시 범위를 표현하지 않는다. `seenTimestampUsesOnlyRecordsShownInBriefing`은 max 계산만 검사하여 이 회귀를 막지 못한다.

### 2. P1 — 브리핑에 같은 출처 중복·완료 행동 부활

- 위치: `BriefingActivity.kt:73-103`, `BriefingReminders.kt:108-113`.
- 재현: 공지 A에서 자동 부탁 A를 만든다. 브리핑은 A 공지와 A 부탁을 별도로 집계하고 최대 3개에 둘 다 넣는다. A 부탁을 완료해도 공지 A는 `isRequiredForChild()`만으로 다시 보인다.
- 기대: 모든 화면이 동일한 출처/행동·완료 판단을 사용해야 한다. 홈과 대화의 linked-source 제외와 브리핑의 결과가 현재 다르다. 브리핑 알림의 건수도 같은 목록에서 계산해야 한다.

### 3. P1 — 수정·취소 공지가 기존 자동 부탁을 갱신하지 않음

- 위치: `AssistantTaskStore.kt:79`, `CandidateActionPlanner.kt`의 `AutoActionCoordinator.handle`, `ProbeRepository.kt:246-249`.
- 재현: 동일 notificationKey의 첫 공지에 금요일 09:00 제출을 등록한 뒤 토요일 09:00로 정정한다. store가 동일 sourceNotificationId를 전부 거절하므로 기존 기한은 그대로 남는다. 제출 취소 공지는 plan=null로 끝나 기존 부탁도 남는다. 대화는 연결된 원문을 제외하므로 새 정정 근거를 숨기고 옛 부탁을 답할 수 있다.
- 기대: 새로 만드는 자동 부탁의 생성 경로·개정을 구분하고 실질 수정/취소를 반영한다. 과거 사용자 명시 부탁을 추정 삭제하지 않는다. 완료된 동일 행동의 단순 재수신은 부활시키지 않는다.

### 4. P1 — 신청 마감 안내를 참여 의무로 간주

- 위치: `NoticeDecision.kt:213-220`의 `source.contains("신청") && source.contains("마감") -> REQUIRED`.
- 재현: 제목 `방과후학교 신청 안내`, 본문 `신청 마감은 내일 오후 5시입니다.`. optionalWord에 해당하지 않고 이 분기를 타서 사용자 참여 선택·필수 근거 없이 REQUIRED, APPLIES와 정확한 dueAt이 만들어진다. planner가 자동 부탁으로 기록한다.
- 기대: 마감 사실과 참여 의무를 분리한다. 필수성 또는 사용자 선택의 증거가 없으면 자동 저장·긴급 알림을 하지 않는다. `까지` 단독 오판은 제거됐지만 이 분기는 동일한 문제를 남긴다.

### 5. P1 — 일부 수집·첨부 미확보 상태가 자동 실행을 막지 않음

- 위치: `NoticeDecision.kt:129-150,171-194`, `NotificationCandidateParser.kt:16-33`, `CandidateActionPlanner.kt:19-37`.
- 재현: `truncated=true`, 본문 `준비물: 물통. 내일 오전 9시까지`인 기록이 정확한 dueAt과 required action을 만들고 자동 저장된다. 잘린 나머지에 대상 제한 또는 취소가 있을 수 있다. `첨부 대상 확인. 준비물: 물통. 내일 오전 9시까지` 역시 첨부 미확보 표시만 붙고 실행된다.
- 기대: 불완전함이 대상·의무·날짜 검증에 영향을 주면 행동을 보류한다. 최소한 알려진 PARTIAL_EXTRACTION은 자동 생성/긴급 경로에서 제외한다. 알림 텍스트만 있다는 사실 자체와 검증된 전체 첨부를 혼동하지 않는다.

### 6. P1 — 긴급 알림이 실제 다음 브리핑·완료·동일 행동 개정을 고려하지 않음

- 위치: `AssistantAlertNotifier.kt:40-45,93-99`, `ProbeRepository.kt:246-249`.
- 재현: 현재 09:00, 다음 사용자 브리핑 12:00, 기한 18:00이면 지금 푸시한다. 현재 금요일 밤이고 다음 브리핑 월요일, 일요일 기한이면 12시간 밖이라 빠질 수 있다. 완료된 source task가 있어도 새 내용 개정은 notifier로 다시 들어간다. notification ID를 재사용하는 것은 소리·진동 재알림 억제의 증거가 아니다.
- 기대: 미래의 필수/선택된 행동이 **다음 실제 예정 브리핑 전**에 필요한 경우만 즉시 알린다. 완료/철회 및 의미가 동일한 개정을 제외한다. 사용자 설정 시각·주말·꺼진 slot을 반영한다.

### 7. P2 — 오늘·내일·가까운 기한의 공통 편성이 없음

- 위치: `ProbeScreens.kt:231-257`, `BriefingActivity.kt:73-103`, `LocalAgentEngine.kt:255-267`.
- 재현: 다음 달 기한 또는 이미 지난 공지/부탁이 홈의 `오늘 챙길 일`과 브리핑의 `오늘 챙길 내용`에 섞인다. 대화에서는 복수 DUE를 가진 공지가 자동 확정 보류되어도 첫 DUE만 골라 `내일` 질문에 포함할 수 있다.
- 기대: 한 공통 편성 결과로 오늘/내일/가까운 기한·날짜 미확정·만료를 구분한다. 모호한 날짜에서 첫 날짜를 다시 선택하지 않는다.

### 8. P2 — 직접 메모 저장이 요청하지 않은 개별 푸시를 생성

- 위치: `LocalAgentEngine.kt:104-113`, `AgentActivity.kt:134-142`.
- 재현: `내일 오후 3시 물통 챙겨줘`는 15:00 기한뿐 아니라 07:00 알림(또는 지금+15분)을 자동 생성한다. 사용자는 해당 알림 시각을 정하지 않았는데 `정한 알림도 함께 저장했어요`라고 말한다.
- 기대: 명시적인 알림 요청이 없는 일반 부탁은 브리핑에만 포함한다. 명시된 알림 시각이 있으면 그 시각을 따른다. 바로 로컬 저장·성공 후 취소 제공 구조는 적절하다.

### 9. P2 — evidence.quote에 실제 원문이 아닌 설명·재구성 문자열 저장

- 위치: `NoticeDecision.kt:142-144,228,247-252`.
- 재현: 제한 없는 원문에서 `대상 학년 제한 없음`이라는 비원문 quote가 생긴다. 원문 `초등학교 5~6학년, 중 1~3학년`은 `초등 5~6학년 · 중등 1~3학년`로 재구성된다. UNKNOWN의 설명 문장도 quote로 들어간다.
- 기대: quote는 실제 원문 span만 저장한다. 설명·정규화한 범위는 별도 value/reason이다. 찾지 못한 조건은 근거 없음으로 표현한다.

### 10. P2 — 실제 한국어 공지 표기와 부정 표현 회귀가 빠짐

- 위치: `NoticeDecision.kt:93,165-168,320-335`; `NoticeDecisionEngineTest.kt` fixture.
- 재현: 실제 첨부의 `2026.9.28.(월)17:00 ~ 2026.10.2.(금)09:00`는 날짜 뒤 점 때문에 현재 calendarDate가 요일·시각을 함께 읽지 못한다. 테스트는 이를 `2026-09-28 월 17:00`로 바꿔 검증한다. `선착순이 아닙니다. 추첨으로 선정`도 `선착순 아님`의 정확한 문자열이 아니어서 선착순으로 분류된다. 명시 요일과 달력의 불일치도 검사하지 않는다.
- 기대: 실제 원문 표기를 보존한 회귀를 추가한다. 읽지 못한 날짜/선정 정책은 확정하지 말고 불확실로 남긴다. 이 첨부 자체의 정답은 **선착순 마감**이다.

## 확인한 개선과 한계

- runtime source에서 `manual_reference_transcription`을 신뢰 토큰으로 처리하는 분기는 발견하지 않았다. 추출 방식은 `android_notification_text`이고 fixture는 테스트에만 있다. 원문 텍스트를 코드/네트워크 지시로 실행하는 경로도 검토 범위에서는 발견하지 않았다.
- 초등·중등 school level을 나눴고, 제공된 선택 체험 fixture는 초등 2학년 대상 외/초등 5학년 선택으로 판정한다. `까지` 단독으로 필수 처리하지 않고 지난 기한 즉시 알림과 23:59·내년 자동 이월은 제한했다.
- 자동 공지 부탁의 remindAt은 null이다. 직접 대화 명령에는 위 8번의 별도 잔존 경로가 있다.
- 로컬 부탁 저장은 실제 저장 성공 뒤 성공 문구와 취소를 제공한다. 실패를 외부 신청 완료로 말하는 구현은 발견하지 않았다.
- 개인 웹 이동·쿠키만으로 로그인 완료라고 하지 않는 `isLikelySignedIn=false` 경계는 유지된다. 생성 AI 호출 경로나 개인 공지를 provider에 전송하는 구현은 검토한 코드에서 발견하지 않았다. 공개 나이스 조회와 WebView/TTS 같은 기존 네트워크 경로까지 모두 오프라인이라는 뜻은 아니다.
- 실제 OCR, 첨부 획득, 표 읽기, 생성 AI, 개인 웹 공지 조회는 구현/검증 완료가 아니다. 새 ML Kit 도입은 이번 리뷰 범위 밖이다. 실제 한국어 공지, 기기 권한·백그라운드 수집·음성·알람·UI는 별도의 실행 증거가 필요하다.

수정 후 위 재현을 겨냥한 회귀 검증과 2차 Astra 소스 검토가 필요하다. 이 1차 리뷰는 66개 테스트 통과를 제품 요구 충족으로 승인하지 않는다.

---

# 2차 검토 — 2026-09-15 07:22:52 KST 읽기 시점

판정: **수정 필요**. 위 1차 항목의 일부는 해결됐으나 실제 production 흐름에 남은 P1과 새 질문→자동 저장 회귀를 확인했다. 동시 구현 중 소스를 읽은 판정이며 이후 변경을 승인하지 않는다. 검토 중 메시지에 적었던 임시 시각은 잘못된 값이어서 `Get-Date`로 위 시각을 확인했다. 이 검토에서는 테스트/Gradle/휴대폰을 실행하지 않았다.

## 1차 항목별 상태

| 1차 | 상태 | 2차 확인 결과 |
|---|---|---|
| 1. 미표시 공지 열람 | OPEN, 부분 해결 | `seenRevisionIdsFor`가 `record.id`를 저장하고 새 경로는 timestamp를 쓰지 않는다. 그러나 로딩·오류 화면일 때도 표시되지 않은 공지를 markSeen하는 아래 R2가 남는다. |
| 2. 출처 중복·완료 부활 | CLOSED, 소스 확인 | Activity/Receiver 모두 전체 tasks의 연결 source를 공지에서 제외한다. 완료 task도 제외 집합에 남는다. 실제 저장 완료 전 로딩 문제는 R2로 별도 추적한다. |
| 3. 수정·취소 반영 | OPEN, 부분 해결 | `AUTO_NOTICE`/revision/suspended 구분과 기존 자동 task의 제자리 갱신, 완료 동일 출처의 부활 억제는 확인. 불확실한 새 revision이 이전 부탁을 남기는 R1은 계속 열린 상태다. |
| 4. 신청 마감→필수 | CLOSED, 소스 확인 | 신청+마감만으로 REQUIRED를 만들던 분기는 제거됐다. 선택 후보는 parser에서 자동 task로 반환하지 않는다. |
| 5. 일부 추출 실행 | CLOSED, 소스 확인 | PARTIAL_EXTRACTION/ATTACHMENT_MISSING은 action=null이며 notifier에서도 제외한다. 이전 자동 부탁을 보류하는 production 경로도 존재한다. |
| 6. 긴급 알림 | OPEN, 부분 해결 | 실제 활성 slot·주말을 반영한 다음 브리핑과 비교하며 completed source도 제외한다. 의미가 같은 새 revision의 반복 푸시는 R4로 남는다. |
| 7. 공통 편성·첫 날짜 | OPEN | 홈 공지는 `isBriefingAction`, 브리핑 공지는 `isRequiredForChild`, 홈 부탁 수는 all pending이다. parser의 첫 DUE 문자열 fallback도 남는다(R3/R5). |
| 8. 부탁별 임의 푸시 | CLOSED, 기존 재현 | 챙겨줘/기억해줘는 remindAt=null, 명시 알려줘는 지정 시각으로 바뀌었다. 대신 일반 정보 질문을 저장하는 새 P1 R6을 만들었다. |
| 9. 실제 근거 quote | OPEN, 부분 해결 | 없는 근거를 빈 문자열로 바꾸고 각 range raw를 보존했다. 여러 raw를 합친 quote는 실제 연속 원문 span이 아니다(R7). |
| 10. 실제 날짜·부정 | OPEN, 부분 해결 | 점+요일+시각 fixture와 `선착순이 아닙니다`는 대응 코드를 확인했다. 요일 모순 검증은 여전히 없으며 원문 범용 정확도 증거는 아니다. |

## 남은 blocker와 재현

**R1 / P1 — 새 revision이 자동 계획을 만들 수 없을 때 옛 기한이 살아남는다.** `CandidateActionPlanner.kt`의 `AutoActionCoordinator.handle`에서 상태/선택/INELIGIBLE 분기만 `suspendAutomaticSource`를 호출하고, 나머지는 `plan(...) ?: return`이다. rev1 `내일 오전 9시까지 제출` 후 rev2 `제출 기한은 다시 안내하겠습니다` 또는 `제출 취소`를 받으면 기존 AUTO_NOTICE task가 그대로 남을 수 있다. UNKNOWN 대상, 모호한 복수 날짜, 시각 삭제, 지난 날짜도 같은 경로다. 계획을 만들지 못한 새 revision은 기존 자동 행동을 유지할 근거가 아니다. 실제 handle→store load/update/suspend→notifier를 통해 검증해야 한다. 현재 `reconcileAutomaticRevisionForTest`는 handle이 언제 그 함수를 호출하는지 검증하지 않는다.

**R2 / P1 — 로딩/오류 문구만 보여준 공지를 읽음 처리한다.** `BriefingActivity.kt:98-111`: taskLoading이면 내용은 `부탁을 확인하고 있어요`, taskError이면 오류/건수뿐인데 `LaunchedEffect(allowed,demo,shownNoticeRows)`는 두 상태를 보지 않고 `markSeenRecords`를 실행한다. cold start에서 taskStore가 비어 있는 동안 공지를 읽음 처리한 뒤 사용자가 화면을 닫으면 다음에는 누락된다. seen ID 방식 자체는 맞지만 실제 내용 렌더 조건과 effect 조건/키가 같아야 한다.

**R3 / P2 — 복수 기한의 첫 문자열을 다시 주장한다.** `NotificationCandidateParser.kt:33`의 `action.whenText ?: decision.dates.firstOrNull(DUE)?.text`는 decision이 두 DUE 때문에 whenText/dueAt을 보류한 것을 되돌린다. `내일 오전 9시까지 제출. 모레 오후 5시까지 회신`을 `제출할 것 있어?`처럼 날짜 없는 질문으로 물으면 첫 문자열을 확정 기한처럼 붙일 수 있다. 상세 화면에도 parser 값이 전파된다. action에서 결정한 whenText만 쓰거나 명시적으로 복수/모호 상태를 보여야 한다.

**R4 / P2 — 내용상 동일한 수정에도 즉시 푸시한다.** `AssistantAlertNotifier.notify`는 completed source와 다음 브리핑은 확인하나, 알린 행동/기한/개정의 의미상 fingerprint를 저장·조회하지 않는다. 같은 notificationKey의 기한·행동을 유지한 채 문구/공백만 바뀌면 DAO의 새 record가 `notify`에 들어와 다시 진동한다. `setOnlyAlertOnce`도 없고 사용자가 기존 알림을 지운 경우도 고려하지 않는다. 중복 억제는 알림 ID 재사용만으로 충족되지 않는다.

**R5 / P2 — Receiver와 Activity의 건수는 맞지만 홈·대화와 날짜 기준이 다르다.** `BriefingReminders.kt:120,126-128`: 부탁은 7일 horizon을 적용하나 공지는 isRequiredForChild만 검사한다. `ProbeScreens.kt:235`는 공지에 isBriefingAction을 적용하고 `MainActivity.kt:521`은 부탁을 기한과 무관하게 센다. 따라서 지난/먼 미래의 공지 또는 task가 화면마다 오늘 할 일에 포함/제외된다. Receiver에서 store를 load한 뒤 unseenCount(tasks)+briefingTasks를 쓰는 개선은 확인했다. 그 공통 projection의 범위를 각 소비자가 동일하게 사용해야 한다.

**R6 / P1 NEW — 일반 질문이 실제 부탁으로 저장된다.** `LocalAgentEngine.kt`의 proposedTask는 `알려줘` 명령일 때 subject에 상대 날짜가 있는지만 검사한다. `내일 준비물 알려줘`, `오늘 제출할 것 알려줘`에는 interrogative 정규식에 걸리는 단어가 없어서 `proposedTask`가 반환된다. `resolveCommandDue`가 null이어도 반환되며 AgentActivity가 곧바로 저장한다. 정보조회 질문을 task save보다 먼저 구분하거나 명시적인 미래 알림 시각/행동 문장에만 알려줘 저장을 허용해야 한다. 새로운 `explicitReminderCommandUsesRequestedTime` 테스트 외에 이 두 일반 질문이 proposedTask=null이며 기존 알림에 답하는 회귀가 필요하다.

**R7 / P2 — quote를 원문 조각들의 조합으로 만든다.** `NoticeDecision.kt` applicability의 `ranges.joinToString(" · ") { it.raw }` 및 bareGrade 조합. 원문 `대상 초등학교 5~6학년, 중 1~3학년`에서 만들어진 `초등학교 5~6학년 · 중 1~3학년`은 sourceText에 존재하지 않는다. 여러 evidence 항목으로 각각 raw span을 저장하거나 두 범위를 포함하는 원문 substring을 보존해야 한다.

요일 검증도 미구현이다. 예를 들어 `2026.9.16.(화)09:00까지 제출`은 달력상 수요일과 충돌하지만 preciseAt을 생성한다. 현재 date resolver에는 명시 요일 대조 또는 이슈에 따른 행동 차단이 없다. 이는 1차 10번의 OPEN 잔여다.

## production 연쇄와 검증 범위

- 완료된 동일 source의 자동 task는 새 revision에서도 completed를 유지하고 revision만 갱신한다. source 갱신 때 completed=true인 옛 task를 만들고 새 task를 추가하던 구조는 현재 읽은 소스에 없었다. suspended는 completed와 분리됐으며 task reminder 소비/브리핑에서 제외한다.
- `seenRevisionIdsFor(records)`는 source identity가 아니라 실제 `record.id`를 쓴다. 같은 source의 새 revision을 기존 seenId 하나로 숨기던 문제는 이 경로에서 해결됐다.
- Receiver가 production `unseenCount(context,repository,tasks)`와 `briefingTasks(tasks)`를 호출하는 것은 확인했다. 단, R5의 잘못된 날짜 편성 기준까지 해결된 것은 아니다.
- `AssistantTaskStoreTest.suspendedAutomaticTaskDoesNotLookCompletedToNotifierProjection`은 테스트 안에서 boolean predicate를 다시 쓰는 검사다. 저장·재로드·수정·완료·알림 연쇄가 작동한다는 증거가 아니다.
- 빌드/테스트의 새 결과는 구현자가 별도로 제공해야 한다. 이 문서의 CLOSED는 해당 지적에 대한 소스 경로 확인이며 실기기 실행 승인이나 전체 제품 정확도 보장이 아니다.
- 등하교/미디어 새 설계, 실제 앱 식별, OCR·provider·개인 웹 연동은 2차 검토와 현재 0.7 구현 범위 밖이다.

---

# 3차 검토 — 2026-09-15 07:37:49 KST 읽기 시점

판정: **소스 수정 2건 필요, 배포 승인 보류**. 현재 81개 JUnit XML을 읽어 tests=81, failures=0, errors=0을 확인했다. 검토자가 Gradle/앱을 실행한 결과는 아니며 아래 두 경로는 그 통과로 검증되지 않는다. PC 화면 90점 통과는 상위 작업 제공 결과이고, 실제 기기는 offline으로 검증하지 않았다. 동시 수정 이후 소스를 미리 승인하지 않는다.

## 실제 production 경로에서 확인한 해결

| 대상 | 3차 상태 및 소스 증거 |
|---|---|
| R1 새 불확실 revision의 옛 기한 | CLOSED. `AutoActionCoordinator.handle`의 `plan(...) ?: run`에서 기존 긴급 알림 cancel 후 `suspendAutomaticSource`를 호출한다. 일부 수집·대상 외·정보/선택 분기도 동일하다. store는 AUTO_NOTICE만 suspended로 바꾸고 명시 사용자 부탁을 보존한다. |
| 수정·연장·취소 뒤 기존 긴급 알림 | CLOSED. cancel은 stable notification identity로 NotificationManager.cancel을 호출한다. `AssistantAlertNotifier.notify`는 completed 또는 shouldNotify=false인 새 판단에서 cancel한다. 연장 후 다음 브리핑 뒤 기한이 된 경우에도 옛 긴급 알림이 남는 경로를 막는다. |
| 완료된 동일 자동 행동 재수신 | CLOSED. store는 existing.completed일 때 revision만 갱신하고 false로 되돌리거나 새 task를 추가하지 않는다. suspended와 completed를 분리했고 notifier는 completed source를 제외한다. |
| R3 첫 모호한 날짜 fallback | CLOSED. `NotificationCandidateParser`의 dueText는 `action.whenText`만 사용한다. 복수 DUE에서 firstOrNull로 문자열을 복구하는 코드는 제거됐다. 대화의 날짜 필터도 복수 DUE를 하나로 확정하지 않는다. |
| R5 홈·브리핑 편성 | CLOSED, 해당 지적 범위. 홈 공지/BriefingReminders.unseenRecords는 isBriefingAction을 쓰고 MainActivity의 pendingTaskCount/Activity/Receiver는 briefingTasks를 사용한다. Receiver는 실제 task store load 이후 동일 helper를 호출한다. 명시적인 대화 조회는 질문의 기간 필터를 사용한다. |
| R6 정보 질문의 자동 저장 | CLOSED, 지적한 재현. 알려줘 저장은 실제 시각 표현을 요구하고 준비/제출/마감/알림/부탁 조회를 제외한다. `내일 준비물 알려줘`와 `최근 알림 알려줘` 회귀가 존재한다. 챙겨줘/기억해줘는 개별 remindAt을 만들지 않으며 명시 시각 알려줘만 해당 시각을 사용한다. |
| R7 evidence 원문 span | CLOSED. 학교급 범위와 bareGrade 모두 quote를 개별 raw match로 저장하고 조합 문장은 reason으로 분리했다. 근거 없는 조건은 가짜 문장 대신 빈 근거다. |
| 실제 날짜·요일 모순 | CLOSED, 지적한 재현. 점/요일/시각을 읽고 달력 요일이 다르면 preciseAt=null 및 이슈를 남긴다. 겹치는 day-only 정규식 match도 배제한다. planner가 실제 dueAt이 없는 행동을 자동 기한으로 만들지 않는다. |
| seen revision IDs | 유지 확인. `record.id` 단위 저장으로 source의 수정 공지를 blanket seen 처리하지 않는다. 아래 effect 재실행 문제가 별도로 남는다. |

## 최종으로 남은 2건

**T1 / P2 — 같은 의미의 새 revision 긴급 푸시 중복이 계속됨.** `AssistantAlertNotifier.kt`에는 알린 source+행동+기한 fingerprint의 저장/조회가 없다. `ProbeRepository.kt:247-249`는 삽입된 모든 새 rawHash revision에서 coordinator 다음 notifier를 호출한다. 같은 source의 준비물·기한을 유지하고 공백이나 관련 없는 문구만 바꾼 수정은 다시 manager.notify에 도달한다. 알림 ID 재사용만으로 재진동/사용자 닫기 이후 재푸시를 억제하지 못한다. 현재 생산 코드 전체에서 해당 dedup 로직을 발견하지 못했으므로 구현됐다고 승인할 수 없다.

**T2 / P2 — 로딩 완료 후 읽음 처리 effect가 재실행되지 않을 수 있음.** `BriefingActivity.kt:110-113`은 `!taskLoading && !taskError` guard를 추가하여 미표시 공지를 읽음 처리하던 P1은 해결했다. 그러나 LaunchedEffect의 key에는 `allowed, demo, shownNoticeRows`만 있고 taskLoading/taskError가 없다. cold start에서 empty task store를 로드하는 동안 첫 effect는 guard 때문에 종료한다. 로드가 끝나도 동일한 notice rows이면 key가 바뀌지 않아 실제 표시된 공지의 markSeen이 실행되지 않는다. 다음 브리핑에서 같은 공지가 다시 나온다. 실제 표시 여부를 정의하는 상태를 effect key에도 포함해야 한다.

이 두 변경 후에만 해당 경로를 대상으로 제한된 4차 검토가 필요하다. 1·2차에서 수정된 부분을 되돌려 전체 검토를 반복하거나 OCR/등하교 새 기능을 확장할 필요는 없다.

## 남는 검증 한계

생성 AI/API, runtime OCR/첨부 수집, 개인 웹 공지 connector, 실제 등하교 앱 식별·원문 수집은 이 0.7 결과에 포함되지 않는다. 실기기 알림 권한·백그라운드 수집·잠금·알람·WebView/TTS·업그레이드 저장소는 실행하지 않았다. 현재 수동 한국어 fixture/81개 XML/PC render는 그 실세계 동작이나 모든 공지 해석 정확도를 입증하지 않는다. 테스트 helper 존재만으로 production 연쇄를 승인하지 않았으며 위 해결 판정은 실제 caller→저장→알림 소스를 읽은 범위의 판단이다.

---

# 제한된 4차 검토 — 2026-09-15 07:40:28 KST

범위: 3차 T1/T2만 재검토. **T2는 CLOSED, T1은 production 연쇄의 잔여 1건 때문에 OPEN**이다. 전체 AI 제품 승인이 아니며 마지막 테스트/빌드는 상위 작업이 별도 확인한다. 소스·테스트 읽기만 수행했다.

- T2 CLOSED: `BriefingActivity.kt:110-113`의 LaunchedEffect key에 taskLoading/taskError가 포함되고 guard에도 두 값과 nonempty shownNoticeRows가 들어간다. 로딩·오류 중 읽음 처리하지 않고 로딩 완료 후 재실행하는 경로를 확인했다.
- T1 부분 해결: notifier가 실제 notification source ID+공백 정규화 action.label+dueAt을 fingerprint로 만들고 실제 notify 전에 이전 fingerprint를 비교한다. 성공한 `manager.notify` 뒤에만 SharedPreferences에 저장하며 권한/채널/보안 예외 실패 때는 새 fingerprint를 쓰지 않는다. 동일 기한/행동이면 억제하고 다른 dueAt이면 허용된다. reset은 전체 preference를 지운다.
- T1 잔여 P2: 새 cancel이 표시 알림과 fingerprint를 함께 삭제한다. 기존 production `CandidateActionPlanner.plan`은 dueAt이 now+5분 이내면 null을 반환하고, coordinator의 plan-null 분기는 cancel을 호출한다. repository는 그 직후 notifier를 다시 호출한다. 따라서 **3분 뒤 기한인 동일 source/행동의 수정 수신 → plan=null → cancel로 fingerprint 삭제 → shouldNotify는 미래 기한이라 true → 재푸시**가 반복된다. 새 fingerprint helper 테스트는 이 실제 연쇄를 실행하지 않는다.

최소 수정은 표시 알림 취소와 이미 알린 fingerprint 보관을 분리하는 것이다. 일반 취소 때 이력을 유지하고 전체 reset에서 지우면, 수정된 기한은 다른 fingerprint로 허용하면서 임박 기한의 동일 행동 재푸시를 막을 수 있다. 이 한 경로를 수정한 뒤 제한 검토를 종결할 수 있다.

OCR/API/개인 웹/등하교 앱 원문/새 빌드 실기기 검증은 여전히 범위 밖이며 수행하지 않았다.

---

# T1 정확 경로 최종 재확인 — 2026-09-15 07:42:37 KST

**T1 기능 경로 CLOSED.** 일반 cancel이 NotificationManager.cancel만 호출하므로 임박 기한 plan-null→cancel→notify에서도 기존 fingerprint가 유지된다. 이어지는 production notify의 동일 fingerprint 비교가 재푸시를 막는다. 바뀐 dueAt은 다른 fingerprint이며 reset은 전체 preference를 지운다. 저장은 실제 manager.notify 성공 후다. 새 테스트는 production cancel의 이력 보존을 확인하지만 전체 coordinator 연쇄 실행은 아니므로 소스 추적과 구분한다. 테스트/빌드 최종 실행 결과는 상위 작업이 확인한다.

**요청된 개인정보 파급 확인에서 최소 수정 1건 발견.** 현재 `alertFingerprint`는 hash가 아니라 sourceId·action.label·dueAt을 합친 평문이다. `action.label`에는 원문에서 얻은 준비물과 아이 이름 등 민감할 수 있는 내용이 들어갈 수 있으며 이를 SharedPreferences에 새로 복제한다. 기존 암호화 저장 경계와 맞지 않는다. 기존 `ProbeRules.digest`로 합친 값을 digest한 결과만 저장하면 평문 원문 파생 내용의 추가 저장을 피할 수 있다. 해시는 익명화를 보장한다는 주장을 해서는 안 된다. 이 한 수정 뒤 T1/T2의 제한 소스 검토를 종결할 수 있다.

OCR/API/개인 웹/실제 등하교 앱 원문/새 빌드 실기기는 계속 미검증이며 전체 AI 비서 완료 판정이 아니다.

---

# 최종 종결 — 2026-09-15 07:44:53 KST

**판정: 현재 0.7 로컬 개선 범위의 소스 검토 통과. 이 검토에서 추적한 blocking 항목은 모두 CLOSED.** 앞선 보류 판정은 당시 소스의 사실로 보존하며, 이 최종 판정은 후속 수정 내용을 직접 확인한 결과다. 최종 빌드·테스트·패키징·실기기 배포의 성공 판정은 상위 작업의 별도 증거에 따른다.

마지막 개인정보 잔여를 확인했다. `AssistantAlertNotifier.kt:131-137`은 source identity·정규화 행동·기한을 합친 값을 기존 `ProbeRules.digest`에 전달한다. 해당 함수는 SHA-256이며 production 저장 경로는 manager.notify 성공 뒤 이 digest만 `alerted:` preference 값으로 기록한다. source identity 역시 package/key의 digest다. 따라서 새로운 중복 이력 저장소에 준비물/아이 이름 등의 원문 파생 문자열을 평문으로 복제하던 문제는 CLOSED다. 이는 해시가 익명화를 보장한다는 뜻이 아니다.

기존 CLOSED 유지: 일반 cancel은 표시 알림만 제거하여 5분 이내 plan-null→cancel→notify에도 중복 이력이 보존되고, 전체 reset은 해당 preference를 clear한다. 변경 기한은 다른 fingerprint로 처리하며 loading/error effect key 문제도 해결됐다. 최종 테스트 소스에 원문 문자열이 fingerprint에 포함되지 않는 검사가 있는 것은 확인했으나, 검토자가 실행하지 않았다.

검토자는 소스·검증 산출물 읽기 및 이 문서 작성만 수행했다. 최종 JUnit/build/lint 결과와 APK가 그 소스에 대응하는지, 실제 설치·권한·잠금·백그라운드·알람은 상위 작업이 별도 검증해야 한다. 당시 기기는 offline이며 새 빌드를 검증하지 않았다.

이 판정은 **완전한 AI 비서 구현 완료가 아니다**. runtime OCR/첨부 획득, 생성 AI/API, 개인 웹 공지 연동, 실제 등하교 서비스의 앱 식별·원문 수집은 지원 또는 검증 완료로 인정하지 않는다. ML Kit 새 의존성 승인은 별도이며, 실제 한국어 공지 전체의 해석 정확도와 기기 동작도 미검증이다. 현재 결과는 제한된 로컬 판단·저장·브리핑·알림 경계의 개선으로 보고해야 한다.
