# 0.8 알람 화면·모모 예약 독립 검토 — 1차

검토자: GPT-6 Astra High. 읽기 기준 2026-09-15 08:43:26 KST. 판정: **수정 필요**.

범위: AlarmContent, TaskAlarmActivity, BriefingActivity/Reminders, TaskReminderScheduler, AssistantTaskStore와 관련 테스트. Calendar/Schedule lane은 다시 읽지 않았다. 소스와 테스트 읽기만 수행했고 Gradle/휴대폰/실제 알림을 실행하지 않았다. contrast 토큰은 상위 작업이 측정·수정 중이라 중복 지적하지 않는다.

## A1 / P1 — 내용 보기·TTS 실행 전에 숨은 공지를 모두 읽음 처리

위치: `BriefingActivity.kt:100,140-143,199-220`; `AlarmContent.kt` 내용 보기 callback.

접힌 화면의 첫 공지만 markSeen하는 기본 effect는 개선됐다. 그러나 내용 보기를 누르면 shownNoticeRows 3개를 모두 markSeen하고 즉시 AssistantTasksActivity 또는 MainActivity로 이동·finish한다. AlarmContent 내부 details=true가 실제로 그려지기 전에 부모 callback이 화면을 닫으며, 부탁 목록으로 이동한 경우 공지 2개는 그 화면에도 없다.

소리로 듣기도 TTS.speak 전에 3개를 모두 markSeen한다. speak가 실패하거나 사용자가 첫 문장 중 소리 끄기/화면 종료하면 아직 읽지 않은 공지가 다음 브리핑에서 사라진다. enqueue 성공도 전체 발화 완료가 아니다.

기대: 실제 표시한 공지 단위로만 열람 처리한다. 상세를 이 화면에 실제 표시하고 범위를 관리하거나 대상 상세 화면에서 표시를 확인한다. 음성은 성공 완료한 공지 utterance ID 단위로 처리하고 중단/오류는 미열람으로 남긴다. 저장소 helper의 목록 수 검사만으로 이 UI 경로를 검증할 수 없다.

## A2 / P1 — 이전/중복 FIRE가 최신 브리핑 occurrence를 덮어씀

위치: `BriefingReminders.kt:196-249,307-332,371-410`.

Receiver는 slot·generation·enabled만 검사하고 FIRE/SNOOZED_FIRE의 occurrence ID가 현재 예약에 해당하는지 확인하지 않는다. FIRE이면 다음 정기 schedule을 먼저 만들고 카운터를 초기화한다. notify도 전달받은 occurrence를 검증하지 않고 saveActiveOccurrence로 저장한다.

재현: snooze A→B 예약 후 옛 A FIRE/SNOOZED_FIRE callback을 다시 전달하거나, 다음 occurrence C가 활성인 동안 B callback이 늦게 도착한다. 같은 generation이면 통과해 최신 active를 옛 ID로 바꾸고 같은 710+slot 알림을 다시 발행할 수 있다. stop/snooze 함수의 stale 검사는 발행 경계의 중복을 막지 못한다.

기대: 현재 예약된 occurrence의 원자적 claim을 먼저 확인하고 한 번만 발행한다. 현재 발생 건과 다음 정기 예약의 ID/시각을 별도 보존해야 한다. 현재 FIRE 이후 schedule이 만든 다음 예약 필드를 saveActiveOccurrence가 지우는 구조도 함께 조정한다.

## A3 / P2 — task snooze 예약 실패에서 상태 rollback 없음

위치: `AssistantTaskStore.kt:243-249,280-299`; `TaskReminderScheduler.kt:151-171`; `TaskAlarmActivity.kt:134-150`.

snooze는 먼저 암호화 commit과 StateFlow 갱신으로 active occurrence를 지우고 snooze count를 증가시킨 뒤 TaskReminderScheduler.sync를 호출한다. schedule의 exact 및 fallback AlarmManager 호출이 실패하면 exception이 올라가 UI는 실패를 알리지만 이전 active/카운터는 이미 바뀐 상태다. 같은 화면에서 재시도하면 Stale가 되고 다음 load에서 갑자기 예약될 수도 있다.

기대: 저장과 시스템 예약의 상태를 분리해 예약 실패를 명확히 보존하거나 이전 occurrence/카운터로 rollback한다. 기존 알림의 소리 끄기 제어는 계속 가능해야 한다. 현재 fake 순수 reducer 테스트는 실제 저장 후 sync 실패를 검증하지 않는다. briefingsnooze의 rollback 코드도 실제 오류 주입과 rollback commit 실패까지 별도 검증이 필요하다.

## A4 / P1 — 열람 처리가 다른 slot/새 알림까지 취소

위치: `BriefingReminders.kt:180-189`.

markSeenRecords/legacy markSeen은 여전히 모든 slot의 710,711,712를 취소한다. slot0에서 첫 행 하나를 열람해도 다른 slot 알림까지 제거한다. 오래된 Activity의 읽음 effect가 최신 occurrence가 발행된 뒤 실행되면 stopActive의 occurrence/generation 검사를 우회하고 새 알림을 취소한다.

기대: 열람 상태 변경은 notification cancellation과 분리한다. 소리/표시 취소는 검증된 자기 occurrence+notification ID에만 적용한다. demo=1710+slot과 실제710+slot을 구분한 stopActive 개선은 유지한다.

## A5 / P2 — 재부팅/시간 변경 복원이 snooze 예약과 체인 한도를 보존하지 않음

위치: `BriefingReminders.kt:107-140,250-287,307-315,371-383`.

restore는 각 slot에 schedule(resetSnooze=true)를 호출한다. 이 함수는 active/scheduled occurrence를 덮어쓰고 snoozeCount/Minutes를 0으로 만든다. 영속화된 10분 뒤 delayed 예약을 다시 AlarmManager에 등록하는 경로는 없다. 따라서 재부팅하면 snooze가 소실된다. 시간 변경처럼 기존 delayed PendingIntent가 살아 있는 경우에는 오래된 delayed callback이 리셋된 체인 카운터로 들어올 수 있으며 A2와 결합한다.

기대: 정기 다음 브리핑과 pending snooze를 별도 복원하고 동일 체인의 3회/60분 한도를 보존한다. 이미 지난 발생 건을 새 날짜로 몰래 바꾸지 않는다. 활성 레코드 필드를 채운 뒤 snooze 함수만 호출하는 테스트는 복원 전체 경로의 증거가 아니다.

## A6 / P2 — snooze 성공 화면의 큰 예약 시각이 원래 시각

위치: `TaskAlarmActivity.kt:104-114`; `BriefingActivity.kt:145-154`.

두 화면 모두 snoozedUntil 분기에 새 nextAt을 가지고 있지만 dateText/scheduledTimeText에는 원래 scheduledAt을 전달한다. 예를 들어 07:00 알림을 10분 미루면 본문에는 07:10이지만 큰 시계와 예약 시각 라벨은 07:00이다.

기대: 성공 화면의 주 시각/날짜는 실제 저장한 nextAt을 표시한다. 다음 발생 Activity에 전달하는 scheduledAt 경로는 새 시각을 사용하므로 이 지적은 현재 성공 화면의 잘못된 값에 한정한다.

## 확인한 동작과 보호 경계

- AlarmContent는 locked 상태에서 제목/행동/건수/상태/상세를 안전한 문구로 대체하며 완료/TTS를 숨긴다. 날짜·예약 시각 외 개인정보를 숨기는 코드 경로와 관련 렌더 테스트가 있다. 실제 기기 잠금 전환은 미실행이다.
- TaskAlarmActivity/BriefingActivity의 초기 unlocked=false, onResume 및 focus 복귀 때 fresh KeyguardManager 확인이 있다. 상세/TTS/완료의 사용자 입력에서도 fresh keyguard를 확인한다. 소리 끄기/onStop는 완료를 호출하지 않는다.
- task 완료는 실제 occurrence를 검사하고 완료 전 task snapshot을 보존한 뒤 그 activeAlarmNotificationId를 취소한다. 저장 과정에서 active 필드가 지워진 뒤 legacy ID만 취소하던 위험은 이 완료 경로에서 막는다. 다른 task는 완료하지 않으며 외부 캘린더/외부 시계 앱 삭제를 하지 않는다.
- task 알림의 발행 ID는 task+occurrence에서 계산한다. 과거 Activity의 onStop는 자기 intent ID만 취소한다. briefing stopActive는 generation+active occurrence를 비교하고 demo ID도 구분한다. A4의 별도 cancellation 우회는 남아 있다.
- task fire는 expectedOccurrenceId를 검사하고 consume을 저장한 뒤 notify한다. notify=false이면 제한된 자동 retry 경로가 있으며 snooze count와 retry count를 분리했다. 순수 helper만으로 실제 NotificationManager 실패·재부팅·프로세스 종료를 검증했다고 보지 않는다.
- 0.7 task의 occurrence 없는 레코드는 expectedOccurrenceId에서 legacy token을 계산하며 load→sync가 token을 넣은 예약으로 다시 만든다. 기존 delivered intent 자체가 token 없이 들어오면 receiver에서 return하므로 업그레이드 직후의 재예약/발화는 실제 integration 시험이 필요하다. 소스만으로 실기기 무누락을 보장하지 않는다.
- 기본 briefing의 expired/distant/completed/suspended 제외와 linked-source 중복 제거는 기존 공통 projection을 사용한다. 새 문구는 `곧 챙길 일`이다.
- AlarmContent는 스크롤 영역과 하단 주 제어를 분리하고 버튼 최소 높이를 둔다. 320dp/2x font test와 screenshot 생성 코드가 있으나 검토자가 실행하지 않았다. 작은 화면/글자 확대/스크린리더 최종 판정은 상위 렌더 및 기기 증거가 필요하다.
- briefing 알람 모드는 setFullScreenIntent와 30초 timeout을 적용한다. task 알림은 일반 notification 경로이므로 전체화면 자동 표시/지속 울림을 구현했다고 주장할 수 없다. 실제 전달·절전·음성 엔진 동작도 미검증이다.

## 후속 확인

A1은 실제 상세 표시와 TTS 중단/실패 callback, A2/A5는 첫 FIRE→다음 정기 예약→snooze→재부팅/이전 intent 재전달, A3는 암호화 저장 성공 뒤 AlarmManager 실패를 겨냥한 검증이 필요하다. 현재 helper tests와 정적 render tests가 이 production 연쇄를 대신하지 않는다.

개인 calendar lane, 생성 AI/OCR/개인 웹/실제 등하교 서비스 검토로 범위를 확장하지 않았다. 휴대폰은 offline이므로 새 빌드가 실제로 울렸거나 사용자 앱과 동작했다는 판정은 없다.

---

# Alarm 2차 검토 — 2026-09-15 08:56:19 KST 읽기 기준

판정: **수정 필요 — A1 표시 확인과 A3 예약 추적의 직접 잔여 2건**. A1~A6 수정 및 새 task opt-in full-screen 경로만 읽었다. Calendar lane은 읽지 않았고 Gradle/렌더/휴대폰을 실행하지 않았다.

| 항목 | 상태 | 실제 소스 확인 |
|---|---|---|
| A1 숨은 공지 열람 | OPEN, 부분 해결 | 상세 화면 이동/finish가 없어졌고 TTS enqueue에서 markSeen도 제거됐다. 하지만 상세 버튼 즉시 markSeen이 여전히 남아 실제 표시 확인 callback이 없다. |
| A2 stale/duplicate fire | CLOSED, 지적 재현 | Receiver가 consumeScheduledFire로 해당 regular/snooze token·시각·generation을 원자적으로 소비한 뒤에만 발행한다. 중복/옛 token은 기록이 없어 거절된다. notify도 explicit occurrence와 현재 active를 대조한다. 다음 정기 schedule은 현재 active와 카운터를 지우지 않는다. |
| A3 task snooze rollback | OPEN, 부분 해결 | save(sync=false)→trySchedule 실패 시 original을 저장하고 Failed를 반환해 원 active/카운터를 복원한다. 단, 성공한 예약의 SCHEDULED_IDS 추적 누락이 새로 생겼다. |
| A4 blanket cancel | CLOSED | markSeenRecords/markSeen에서 모든 slot NotificationManager.cancel 호출이 제거됐다. 열람 변경과 자기 알림 정지가 분리됐다. |
| A5 재부팅/시간 복원 | CLOSED, 지적 재현 | regular와 snooze token/time을 별도 보존·재예약하며 15분 grace 안은 동일 token/원 예약 시각으로 now+1초 이후 복원한다. 오래된 snooze는 제거하고 다음 정기 예약은 체인 카운터를 리셋하지 않는다. |
| A6 snooze hero | CLOSED | 두 Activity의 성공 분기가 nextAt의 날짜·시각을 사용하고 라벨을 `다음 알림`으로 바꾼다. |

## C1 / P1 — 실제 상세 표시 전에 읽음 처리하는 잔여

`AlarmContent`의 실제 signature에는 구현 보고서가 설명한 `onDetailsShown`이 없다. 버튼 callback은 `details=true; onShowDetails()`를 즉시 실행한다. `BriefingActivity.kt:198-202`는 그 callback에서 shownNoticeRows 3개를 전부 markSeen한다. 작은 화면에서 상세 섹션은 viewport 아래에 추가되므로 버튼을 누르고 스크롤 없이 닫으면 2개 공지를 보지 않았는데 읽음 처리된다.

상세가 화면 안에 실제 나타난 범위를 확인한 뒤 공지별로 처리하거나, 최소한 버튼 callback과 표시 완료 callback을 분리해야 한다. composition에 추가된 사실만으로 viewport 표시 완료라고 볼 수 없다. TTS 중단/실패에서 숨은 공지를 읽음 처리하던 경로는 제거돼 해결됐다.

## C2 / P2 — 성공한 snooze 예약이 전체 reset 취소 목록에 등록되지 않음

`AssistantTaskStore.kt:251-254`의 새 성공 경로는 `save(next,sync=false)`와 `TaskReminderScheduler.trySchedule`이다. trySchedule은 AlarmManager만 예약하며 scheduler의 SCHEDULED_IDS preference는 갱신하지 않는다.

실제 연쇄: 처음 fire의 consume→save→sync는 remindAt=null이 된 task ID를 SCHEDULED_IDS에서 제거한다. 이어 snooze 성공은 그 ID를 다시 등록하지 않는다. 이 상태에서 즉시 전체 reset하면 store.clear→sync(empty)는 이전 등록 ID가 없다고 보아 실제 snooze AlarmManager 예약을 cancel하지 않는다. 나중 fire의 consent/기록 gate가 내용 노출을 막을 수 있지만, 시스템 예약 자체를 지운다는 reset 계약은 충족하지 않는다.

성공한 trySchedule의 예약 추적도 같은 경로에서 갱신하고 실패/rollback 때 정리해야 한다. 이 지적은 새 rollback 수정의 직접 결과이며 다른 기능 확장이 아니다.

## Task opt-in 알람 경로 확인

TaskReminderScheduler는 사용자가 고른 BriefingReminders.alarmMode와 alarmPermissions가 모두 충족될 때만 알람 채널·CATEGORY_ALARM·setFullScreenIntent·30초 timeout·FLAG_INSISTENT를 적용한다. 일반 task reminder에는 그 flag/FSI를 적용하지 않는다. TaskAlarmActivity도 ringing intent일 때만 showWhenLocked/turnScreenOn을 요청한다.

공개 notification은 generic 내용이며 Activity의 locked redaction/fresh keyguard/완료 occurrence 검사는 유지된다. 정지는 완료와 분리되고 task notification ID가 occurrence별로 다르므로 과거 task Activity가 새 occurrence의 ID를 직접 취소하지 않는다. 이 코드 확인은 실제 Android FSI 허용·잠금 전환·소리/진동 반복·30초 종료가 기기에서 검증됐다는 뜻이 아니다.

## 남은 검증 범위

A2/A5의 소비/복원, A3의 실제 저장·AlarmManager 실패·rollback, 새 FSI와 잠금 상태는 통합 검증이 필요하다. helper와 정적 render test 존재만으로 실행 성공을 승인하지 않았다. Calendar B 수정/전체 빌드·새 unit tests·PC render·실기기 결과는 각 담당자 증거를 별도로 따라야 한다.

---

# Alarm 제한 종결 검토 — 2026-09-15 09:01:48 KST

**판정: 0.8 알람/모모 예약 lane의 이번 소스 검토 항목 A1~A6 및 C1/C2 CLOSED.** 최종 빌드·unit/render·실기기 검증은 아직 별도이며 이 판정으로 대신하지 않는다. Calendar 수정은 이번에 읽지 않았다.

- C1/A1: BriefingActivity의 markSeenRecords 호출은 guard가 있는 접힌 첫 공지 effect에만 남았다. 상세 버튼과 TTS는 추가 공지를 읽음 처리하지 않는다. 따라서 상세를 열었지만 스크롤하지 않았거나 음성을 중단해도 숨은 공지가 조용히 소실되지 않는다. 보수적으로 남은 공지는 이후 브리핑에서 다시 보일 수 있다. 이는 현재 의도한 제한이며 실제 viewport/발화 완료 추적까지 구현됐다는 뜻이 아니다.
- C2/A3: production trySchedule은 OS 예약 성공 뒤 registerScheduled로 SCHEDULED_IDS에 task ID를 commit한다. 등록이 실패하면 방금 만든 PendingIntent를 AlarmManager에서 취소하고 false를 돌려준다. snoozeAlarmOccurrence는 이 false에서 원래 task/active occurrence/카운터를 복원한다. 성공 시 registry가 있으므로 전체 reset의 store.clear→sync(empty)는 해당 task를 찾아 실제 예약을 취소한다. cancel도 registry ID를 제거한다.
- 앞선 A2 stale token 소비, A4 열람/알림 취소 분리, A5 regular/snooze 독립 복원과 15분 grace, A6 새 예약 hero 값, 명시적 task 알람 모드의 FSI/30초/INSISTENT 제한에 대한 CLOSED 판정은 유지한다.

검토자가 수행한 것은 변경된 실제 caller·예약·기록·취소 경로의 읽기다. Android AlarmManager/NotificationManager/SharedPreferences 실패를 기기에서 주입하거나 실제로 재부팅·잠금·울림을 검증하지 않았다. 작은 화면·글자 확대·접근성·최종색대비는 상위 PC render 및 실기기 증거를 별도로 따른다. 전체 AI 비서/OCR/외부 calendar 또는 시계 앱 연동의 완료 승인이 아니다.
