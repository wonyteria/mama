# 1.1 QA 결함 추적 (A–N)

Astra 설계 검토에서 승인된 결함 목록. 각 항목은 재현 테스트(수정 전 실패) → 최소 수정 → 회귀 검증을 거쳤다. 상태: DONE / PARTIAL / NOT_RUN / BLOCKED, 근거는 테스트 이름과 커밋.

| # | 결함 | 수락 기준 | 재현/검증 테스트 | 상태 |
|---|------|-----------|------------------|------|
| A | 공식 ID가 다른 두 공지가 fingerprint groupId로 병합될 위험 | 기관/제목/날짜/본문160자 동일 + 공식 문서 ID 상이 → 별도 할 일, 완료 미승계; app/web 사본은 통합 | `NoticeGroupingTest`, `AssistantTaskStoreTest.disjointOfficialIdsWithSameFingerprintProduceSeparateTasks`, `completionOfOneOfficialNoticeDoesNotCarryToDisjointOfficialNotice`, `appCopyWithoutOfficialIdsStillMergesIntoOfficialTask`, `suspendingOneOfficialNoticeLeavesDisjointOfficialTaskActive` | DONE |
| B | 불확실한 수정 공지/첨부 실패가 의무를 숨김 | 불명확 revision은 마지막 신뢰 상태 보존 + `needsReview` + '수정 공지 확인 필요' 노출; 명확한 취소/완료와 구분; linked-record 필터가 회복을 막지 않음 | `AssistantTaskStoreTest.uncertainRevisionMarksNeedsReviewWithoutHidingObligation`, `needsReviewFlagSurvivesStorageRoundTrip`, `clearRevisionResolvesNeedsReview` | DONE |
| C | 임박/지난 기한 공지를 empty plan으로 버림 | 5분 이내/마감 후 도착해도 required Todo 생성·유지, overdue 노출, 과거 알람 무한 반복 금지, 마감 후 revision suspend 금지 | `CandidateActionPlannerTest.requiredNoticeWithinFiveMinutesOfDeadlineStillProducesTask`, `requiredNoticeAfterDeadlineProducesOverdueTaskWithoutPastAlarm`, `TodoSelectorsTest.sameDayPastDeadlineCountsAsOverdueNotDueSoon` | DONE |
| D | 무관한 body revision/cross-lane copy가 remindAt/occurrence 초기화 | 사용자 reminder/snooze는 무관 revision에서 보존, 날짜 없는 Todo도 snooze 유지, 실제 기한 변경만 재스케줄 | `AssistantTaskStoreTest.unrelatedRevisionPreservesUserSnoozeAndCompletion`, `undatedTaskKeepsUserSnoozeAcrossCrossLaneRevision`, `fourConsecutiveSnoozesStayScheduledAndSurviveReload`, `genuineDeadlineChangeRearmsAutomaticReminder` | DONE |
| E | record 저장 ↔ task 생성 사이 중단 시 Todo 영구 누락 | `ReconcileJournal`로 capture 시점 markPending + 성공 시 retire; startup replay는 정확히 1회, tombstone/epoch 넘어 되살리기 금지 | `ReconcileJournalTest.pendingEntriesSurviveUntilCleared`, `retiredKeysAccumulateAcrossDeletes`, `CandidateActionPlannerTest.pendingActionsReplaysRecordedSourceOnce`, `pendingActionsDropsMissingAndRetiredSources` | DONE |
| F | consume 후 notify 전 중단 시 알림 영구 누락 | `remindAt==null` + `activeAlarmOccurrenceId` 상태를 `restoreNow`에서 복구; 완료/제외 task 제외 | `ReconcileJournalTest.activeAlarmsPendingRecoveryExcludesFinishedAndExcluded`, `TaskReminderSchedulerTest`, `AssistantTaskStoreTest.staleOccurrenceCannotSnoozeOrCompleteLatestAlarm` | DONE |
| G | '다 끝냈어요/남은 할 일 없음'이 source health 미반영 | 미수집/실패/확인된 일 없음/실제 완료 구분 | `TodoSelectorsTest.headlineCountsOpenTasksAboveEveryOtherState`, `headlineDistinguishesOffMissingFailedDoneAndEmpty`, `suspendedAndExcludedTasksDoNotCountAsDone` | DONE |
| H | 연결 화면이 지원 범위/마지막 성공/미지원 이유를 숨김 | `capabilityText` 필드로 서비스별 범위 표시, 마지막 성공 시각·오류 표시, 미지원 학교 웹 행 유지, package/sourceId 미노출 | `SourceLanesTest`, `ProbeScreensRenderTest` 미지원 행 render | DONE |
| I | UNKNOWN audience → action=null → 확인 버튼 불가 | `decision.action==null && (UNKNOWN)` → '엄마 확인 필요' 카드로 명시 확인 후 근거 연결 Todo, 자동 승인/중복 금지(`alreadyRemembered`) | `ProbeScreensRenderTest` uncertain 카드, `AccessibilityLayoutTest.detailUncertainNoticeKeepsConfirmReachable` | DONE |
| J | '자동 생성' vs '자동 확정하지 않음' 불일치, 챗봇 설명 잔존 | 온보딩/설정 문구를 실제 기능으로 통일 | 문구 수정 (`ProbeScreens.kt:105`, 설정 카드), grep 검증 | DONE |
| K | 저장 결과 미대기로 창 닫힘 | 성공 시에만 닫기, 실패 시 입력 유지+오류/재시도 | `ProbeScreensRenderTest` 실패 주입 대화상자 테스트 (비-idle semantics 경로) | DONE |
| L | exact alarm 재허용 시 briefing만 재스케줄 | `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → `TaskReminderScheduler.restoreNow`, 부팅과 동일 경로, 권한 없으면 inexact fallback | `TaskReminderSchedulerTest.syncFallsBackToInexactAlarmWhenExactPermissionIsRevoked` | DONE |
| M | 전체 삭제 중 키 삭제 직후 종료 → 암호화 잔존물 고아화 | `ResetMarker`(평문 prefs)가 키 파괴 전 armed, 전 단계 성공 시에만 해제; pending 중 startup이 cleanup 재개, `acceptConsent`/`completeSetup`/`deferSetup` 거부 | `ResetMarkerTest.markerSurvivesUntilCleanupCompletes` | DONE (에뮬레이터 실기기 전체-리셋 재현은 격리 정책상 NOT_RUN) |
| N | 접근성 테스트가 실제 화면 미검증, 역행 순서·무이름 컨트롤 허용 | `ChildProfileScreen` 360dp·200%·가로 추가, `announceable`을 text/cd/state-desc로 엄격화(무이름 Checkbox 실제 결함 발견→수정), 탐색 순서는 표시된 컨트롤 기준 상향/좌향 역행 금지 | `AccessibilityLayoutTest` 21개 전체 (childProfile×2, todoNeedsReview, detailUncertain, todaySourceError 포함) | DONE |

제약(전 항목 공통): 새 의존성 없음, 광범위 재설계 금지, 필요한 상태 migration은 테스트로 검증. 실제 기기 전체 초기화 금지 — 파괴 테스트는 에뮬레이터 격리 원칙. 합성 fixture/단순 reload를 실제 문서/프로세스 종료 검증으로 표현하지 않음.

## 미완료/외부 의존

- **M 실기기 재현**: `ResetMarker` + startup resume + enrollment gate는 Robolectric 수준 검증. 실제 프로세스 kill 시나리오는 에뮬레이터 격리에서만 수행 가능 — 이번 범위에서 NOT_RUN.
- **K 대화상자 테스트의 비-idle 경로**: Robolectric에서 커서 blink가 Compose idle을 점유해 `waitForIdle` 기반 API가 hang — 등록된 Compose root의 semantics를 직접 조회/호출하는 경로로 우회. CI 에뮬레이터 동작은 instrumentation이 아닌 unit suite이므로 영향 없음.
