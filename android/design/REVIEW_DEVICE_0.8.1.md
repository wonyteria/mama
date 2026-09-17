# 0.8.1 실기기 후속 변경 — 제한 소스 검토

검토자: GPT-6 Astra High. 읽기 시각: 2026-09-15 21:56:22 KST.

**판정: 이번 네 파일 변경의 제한 소스 검토 통과. 새 P1/P2 지적 없음.** 기존 0.8 Schedule/Alarm의 종결 범위는 재검토하지 않았다. 검토자는 빌드·ADB·휴대폰 조작을 수행하지 않았다.

## 확인한 경로

- `AlarmContent.kt`: 선택적인 onAskMomo callback이 있을 때만 `모모에게 부탁` 버튼을 표시한다. 잠금 화면에도 버튼 이름은 일반 문구이고, 원문·자녀·일정 정보를 추가로 노출하지 않는다. 기존 잠금 상태의 행동/상세/상태 redaction은 유지된다.
- `TaskAlarmActivity.kt:211-244`: 버튼은 현재 KeyguardManager 상태를 다시 확인한다. 잠겨 있으면 정상 시스템 잠금 해제를 요청하고, 성공 callback에서도 현재 잠금 상태를 다시 확인한 뒤 경로에 재진입한다. 취소/오류 callback은 상태만 갱신하며 AgentActivity를 열지 않는다. 잠금 해제 후 자기 occurrence의 intent notification ID와 TTS만 정지하고 AgentActivity를 열어 현재 화면을 종료한다.
- `BriefingActivity.kt:229-273`: 같은 fresh keyguard 경로를 사용한다. 이동 전 stopAlarm은 기존 occurrence/generation 검증을 수행하는 stopActive를 통하며 demo는1710+slot을 유지한다. 새로운 이동 코드에 blanket cancel 또는 다른 task/slot 취소는 없다.
- 두 이동 경로에는 task 완료·calendar 변경·예약 생성 코드가 없다. `모모에게 부탁`은 대화 화면 진입이며 기존 부탁을 완료로 처리하지 않는다. 원문 payload를 AgentActivity intent로 전달하지 않는다. AgentActivity의 기존 동의/설정 gate가 후속 대화를 처리한다.
- `ClayTheme.kt:58-99`: 고정 밝은 테마와 system bar의 어두운 아이콘을 맞춘다. Activity를 context wrapper에서 찾아 해당 window/view에 keyed DisposableEffect를 적용한다. preview/edit mode는 건너뛰고 effect 종료 때 원래 status/navigation 색상·아이콘·contrast 값을 복원한다. 이번 코드에는 전역 window/activity 저장이나 dispose 없는 composition 부작용을 발견하지 않았다. minSdk33에서 사용하는 system-bar API 범위도 충족한다.

## 증거와 남은 확인

검토 당시 네 파일 수정 시각은 ClayTheme21:54:37, AlarmContent21:52:11, TaskAlarmActivity21:52:35, BriefingActivity21:52:51이었다. 실제 잠금 전체화면이 이전 빌드에서 표시됐다는 사실은 상위 작업의 기기 관찰이며, 이 새 변경에서 검토자가 직접 재현한 결과는 아니다.

새 빌드의 실제 기기에서는 밝은 system bar 가독성, 잠긴 상태의 버튼→잠금 해제 성공/취소, 자기 소리만 정지하는 대화 이동, 대화 입력/뒤로 가기와 큰 글자 하단 제어를 상위 담당자가 확인해야 한다. targetSdk36의 system bar 렌더링은 소스 색 지정만으로 실화면 결과를 보장하지 않으므로 새 스크린샷 판정을 따른다. 최종 테스트·APK·배포 증거도 빌드 담당자와 기기 담당자가 별도로 보유한다.

이 변경은 알람 화면에서 기존 로컬 에이전트에 접근하는 기능이다. 생성 AI/OCR·개인 웹·외부 calendar/clock 앱 호환 전체를 검증하거나 완전한 AI 비서가 됐다는 의미는 아니다.

---

## 실기기 잠금 해제 후 이동 보정 — 2026-09-15 22:10:23 KST 제한 검토

판정: **PendingUnlockAction의 1회 실행·fresh 잠금 검사는 확인, 종료/이탈 시 오래된 이동 요청 취소 P2 1건 OPEN.** 이전 PASS는 이전 변경의 소스 판정이며, 이번 실기기 후속 보정의 결과를 미리 보증하지 않는다.

확인한 개선: helper는 pending이며 현재 KeyguardManager가 unlocked일 때만 실행하고 action 전에 pending=false로 만든다. 성공 callback·decorView.post·onResume·focus 복귀가 겹쳐도 이미 소비한 요청은 다시 실행하지 않는다. cancelled/error·명시 소리 끄기·snooze·onDestroy는 pending을 지운다. 새 이동에서도 기존 자기 알림 정지 경로만 사용하며 완료/원문 전달/인증 우회는 없다.

**G1 / P2 — 화면 종료/이탈 뒤 늦은 callback의 이동을 차단하지 않음.** TaskAlarmActivity.continuePendingAskMomo와 BriefingActivity의 동등 함수는 helper를 호출하기 전에 isFinishing/isDestroyed/현재 foreground 상태를 확인하지 않는다. onStop는 pending을 보존하고 onDestroy에서만 지운다. 잠금 해제 대기 중 Back으로 Activity 종료를 시작했으나 아직 파괴되지 않은 동안 늦은 success callback/post가 도착하면 AgentActivity를 열 수 있다. 다른 앱으로 이탈한 Activity에도 pending이 남아 이후 무관한 잠금 해제/복귀에서 지연 이동할 수 있다.

기대: 명시 Back/앱 이탈에서 요청을 취소하고, 종료 중/파괴된 Activity 및 background에서는 navigation을 실행하지 않는다. 정상 시스템 credential UI로 인한 pause/stop까지 일괄 취소하면 원래 실기기 문제를 되살릴 수 있으므로, 요청을 보존한 정상 인증 완료는 fresh unlocked+foreground 복귀에서 한 번만 처리한다. lifecycle 경계의 검증이 필요하며 현재 helper 순수 테스트만으로 이 integration을 입증하지 못한다.

검토자는 ADB/빌드/폰을 실행하지 않았다. 외부 알람 선호 handler 수정은 읽기 시점에 아직 `prepare(..., handler)`의 명시 null 전달 경로였으므로 이 문서에서 해결됐다고 판정하지 않았다. 해당 lane의 freeze 뒤 별도 제한 확인이 필요하다.

---

## G1·선호 알람 앱 제한 종결 — 2026-09-15 22:13:19 KST

**소스 판정: G1 CLOSED, 명시 null로 선호 handler가 무시되던 문제 CLOSED.** 이번 읽기는 이 두 변경에 한정하며 빌드/폰을 실행하지 않았다.

두 알람 Activity의 continuePendingAskMomo는 isFinishing/isDestroyed이면 실행하지 않고 resumed+window focus가 모두 있어야 helper에 진입한다. helper가 fresh keyguard를 확인하고 실행 전에 pending을 소비한다. 명시 앱 이탈(onUserLeaveHint), 인증 취소/오류, 소리 끄기/snooze 및 destroy에서 pending을 지운다. 정상 credential UI의 pause 동안에는 unlockingForAskMomo=true인 요청을 보존하고, 복귀 시 한 번만 이동한다. 따라서 G1의 종료 중/background 늦은 callback 실행 경로는 차단됐다.

기기 검증에서 특별히 볼 항목: onUserLeaveHint는 무조건 pending을 취소한다. 현재 삼성 기기의 정상 credential handoff가 이 callback까지 발생시키는지는 소스만으로 확정할 수 없다. 해당 순서가 실제 발생하면 정상 잠금 해제 후 이동이 다시 취소될 수 있으므로, root는 정상 인증 성공/취소/Home 이탈을 각각 확인해야 한다. 이는 현시점의 확인된 source blocker가 아니라 플랫폼 callback 순서의 미검증 사항이다. 성공 callback 직후 pause/focus 복귀 순서도 동일 테스트에 포함한다.

AgentActivity.launchExternalAlarm은 prepare 전에 `explicit ?: alarmGateway.selectedHandler()`를 resolveExternalAlarmHandlerForPrepare로 계산한다. 명시 선택이 있으면 이를 우선하고, 없으면 저장한 선호 handler를 전달한다. 기존 gateway는 그 handler가 현재 설치된 ACTION_SET_ALARM 처리자인지와 요청 날짜의 next occurrence를 다시 검사한다. null이 default 인자 평가를 막아 매번 앱 선택으로 돌아가던 원인은 이 production caller에서 해결됐다. 실제 시계 앱 전달/저장 성공은 이 소스 판정으로 대신하지 않는다.

최종 실제 화면·잠금 해제 후 대화 이동·선호 시계 앱 재사용·빌드/테스트는 각각 root와 빌드 담당자의 증거를 따른다. 새 이동은 인증을 우회하거나 부탁을 완료 처리하지 않으며 자기 알림 정지 경계를 유지한다.
