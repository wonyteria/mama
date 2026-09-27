# MAMA 1.1 Play Console 대응 정리

versionCode 14 / versionName 1.1.0 · targetSdk 36 / minSdk 33 / compileSdk 36 · `kr.mom.probe` 정식 패키지.
각 항목은 실제 구현과 대조해 작성했다. 검증 불가·미입력 항목은 NOT_RUN/BLOCKED로 표시하고 추측으로 채우지 않는다.

## 권한 선언과 사용 사유

| 권한 | 실제 사용 | 선언 사유(Play Console 양식용) |
|---|---|---|
| `POST_NOTIFICATIONS` | 부탁 알림·브리핑 알림 게시 | 앱의 핵심 기능인 챙길 일 알림 전달 |
| `SCHEDULE_EXACT_ALARM` | `setExactAndAllowWhileIdle`로 부탁 알림·브리핑을 정시에 울림(거부 시 inexact `setAndAllowWhileIdle` fallback) | 사용자가 정한 시각의 알림 정확도 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | `CalendarProviderGateway`가 사용자 지정 로컬 캘린더에 학교 일정을 읽고, 앱이 만든 이벤트만 삽입·수정·삭제(소유 이벤트 추적 `owned:` 마커) | 학교 일정을 기기 캘린더에 반영하는 사용자 요청 기능 |
| `INTERNET` | 학교 공식 웹사이트 공개 게시물 조회, e알리미·하이클래스 웹 세션 조회 | 사용자가 연결한 소스의 공지 수집 |
| `RECEIVE_BOOT_COMPLETED` | `TaskReminderReceiver`/`SourceSyncScheduler`가 재부팅 후 알람·동기화 재예약 | 재부팅 후 알림 예약 복구 |
| `BIND_NOTIFICATION_LISTENER_SERVICE`(서비스 선언) | `ProbeNotificationListener`가 사용자가 allowlist로 고른 앱의 알림만 수집 | 아래 prominent disclosure 참고 |
| `com.android.alarm.permission.SET_ALARM` | `ExternalAlarmGateway`가 `AlarmClock.ACTION_SET_ALARM` intent로 사용자가 정한 알람을 시스템 시계 앱에 등록 | 사용자 요청 시 시계 앱 알람 등록 |
| `USE_FULL_SCREEN_INTENT` | **미사용** | 전용 알람/통화 앱이 아니므로 정책상 사용 불가. 권한·`setFullScreenIntent`·설정 유도를 모두 제거했다(1.1) |

## 전체화면 인텐트 정책 대응 (완료)

- 기준: Google Play는 `USE_FULL_SCREEN_INTENT`를 전용 알람/통화 앱으로 제한한다. MAMA는 해당 카테고리가 아니므로 1.1에서 전면 제거했다.
- 구현: manifest 권한 삭제, `TaskReminderScheduler`·`BriefingReminders`의 `setFullScreenIntent` 삭제, `BriefingSettingsActivity`의 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 버튼·문구 삭제. 울림 알림은 high-priority heads-up(`mom-assistant-alarm` 채널·alarm 사운드·CATEGORY_ALARM·30초 timeout)으로만 동작하고 사용자 탭 시에만 Activity가 열린다. 백그라운드 Activity 직접 시작 없음.
- 검증: `RingingNotificationContractTest`가 게시된 Notification 객체로 두 경로의 `fullScreenIntent==null`·contentIntent·채널·timeout·action·manifest 권한 부재를 확인한다.

## 알림 접근 권한 prominent disclosure / consent (초안)

- 온보딩 동의 화면에서 수집 목적을 설명한 뒤 사용자가 명시 동의(`consent` + `consentVersion`)하고, 수집 대상 앱을 사용자가 직접 골라(`selectedPackages`) OS 권한 화면에서 허용한다.
- 실제 수집은 `ProbeRules.canCapture`가 동의·온보딩·collectionEnabled·allowlist·OS 접근 권한을 모두 확인한 후에만 동작한다 — allowlist 밖 패키지의 알림은 읽지 않는다(`nonAllowlistedSourceIsNeverCapturedWhileCollectionIsClosed` 기기 테스트).
- Play Console 양식 초안(수정 검토 필요 — 최종 문구 NOT_RUN):
  - 수집 목적: "사용자가 선택한 학교·학원 앱의 알림을 읽어 중요 공지를 할 일과 브리핑으로 정리합니다."
  - 데이터 처리: "알림 텍스트는 기기에서만 처리되고 암호화되어 저장되며 14일 후 자동 삭제됩니다. 서버로 전송하지 않습니다."
  - 사용자 통제: "설정에서 수집 대상 앱을 바꾸거나, 앱 내 전체 삭제로 저장된 데이터를 지울 수 있습니다."

## Data Safety 초안 (Play Console 양식 — 구현 대조됨)

| 항목 | 답변 초안 | 근거 |
|---|---|---|
| 데이터 수집 여부 | 예 | 알림 텍스트·자녀 정보를 기기에 수집·저장 |
| 위치/연락처/식별자 등 | 해당 없음 | 수집 코드 없음 |
| 개인정보 — 이름/기타 | 자녀 이름(별칭), 학교명·학년·학교급 | `ProbeSettings`(암호화 저장) |
| 앱 활동 — 기타 사용자 콘텐츠 | 수집된 알림 텍스트(제목·본문·첨부 메타) | `ProbeRecord`(Room, 암호화 payload) |
| 기기 외 전송 | 없음(단, 아래 네트워크 사용 참고) | 알림 내용·자녀 정보를 보내는 코드 없음 |
| 전송 시 암호화 | 해당 없음(전송 없음) | — |
| 저장 데이터 암호화 | 예 | `probe_settings`·`probe_records` encryptedPayload |
| 삭제 요청 | 앱 내 전체 삭제(`runFullReset`) + 14일 자동 만료 | `deleteAll`·`RETENTION_MS`(14일) |
| 제3자 SDK/광고/분석 | 없음 | 광고·analytics 의존성 없음 |

네트워크 사용 정직 구분: 앱은 (a) 사용자가 연결한 학교 공식 웹사이트의 **공개** 공지를 GET으로 조회하고, (b) e알리미/하이클래스 웹은 WebView 로그인 세션(쿠키는 기기 내)으로 조회한다. 수집된 알림 텍스트와 자녀 정보는 어느 요청에도 실어 보내지 않는다. NEIS 운영 동기화는 `PRODUCTION_SYNC_ENABLED=false`로 비활성 — 실제 NEIS 요청 없음.

## 대상 API·호환

- targetSdk 36 · minSdk 33 · compileSdk 36 — Play의 대상 API 요구 충족.
- `canUseFullScreenIntent` 의존 제거로 API 34+ FSI 제한 영향 없음. `unsafeCheckOpNoThrow` → `checkOpNoThrow`, API 35+ edge-to-edge 대응은 `VERIFICATION_1.1.md`의 API 36 정리 절 참고.

## 스토어 리스팅·출시·롤백 (상태)

- [NOT_RUN] 스토어 등록정보·스크린샷·출시 노트 초안 — 아직 작성하지 않았다.
- [NOT_RUN] 단계적 출시 비율·사전 출시 보고서 — secrets 부재로 서명 빌드 경로 자체가 NOT_RUN.
- [NOT_RUN] 롤백: 1.1 DB 스키마(version 1)는 1.0.2와 동일하나 1.0.2 설치로 덮어쓰는 경로의 실증은 없다 — 데이터 호환성 수동 확인 필요.
- [BLOCKED] 개인정보처리방침 공개 URL·개발자 법적 명칭·문의 이메일 — 사용자 입력 필요(아래 초안 참고). URL 없이 제출 불가.
- [NOT_RUN] Play Console의 알림 접근 권한 선언 양식 제출·승인 — 제출 자체가 아직 없다.
