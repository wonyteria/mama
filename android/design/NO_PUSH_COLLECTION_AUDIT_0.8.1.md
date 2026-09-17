# 푸시 없는 정보 수집 점검 — 0.8.1

판정: **푸시가 없어도 내 아이에게 맞는 공지·등하교·미디어를 자동 수집해 저장·브리핑하는 경로는 현재 구현되어 있지 않다.**
실제 비푸시 네트워크 조회는 나이스 공개 학교 검색과 공개 학교 일정이다. 화면에서 조회해 임시 표시하는 범위이며, 개인 공지 수집이나 자녀별 일정 선별 완료를 뜻하지 않는다.
이번 점검은 소스 읽기와 호출 경로 검색만 수행했다. 서버 응답·개인 계정·실제 자녀 자료·ADB·브라우저·빌드·테스트는 실행하지 않았다.
API 키 값·쿠키·토큰을 조회하지 않았다. 다른 담당자의 잠금 해제/앱 선택 수정과 분리하여 이 문서만 작성했다.

## 1. 수집 경로별 전체 연결 상태

| 경로 | 실행 계기 → 네트워크/출처 | 자녀 매칭 | 저장 → 표시 | 현재 판정 |
|---|---|---|---|---|
| 선택 앱 알림 | `onNotificationPosted` → Android 전달 알림, 별도 앱 조회 없음 | 수집 뒤 NoticeDecision의 학교급·학년 문구 규칙 | 암호화 ProbeRecord → 홈/보관함/브리핑/대화 | 새 푸시 의존, 비푸시 수집 아님 |
| 나이스 학교 검색 | 연결 화면 스위치 → `schoolInfo` GET | 입력 학교명 후보 선택, 학교 코드 확보 | 연결 metadata 암호화 → 연결 상태 | 실제 수동 조회 코드, 개인 공지와 별개 |
| 나이스 공개 일정 | MainActivity 구성/연결 상태 변경 → `SchoolSchedule` GET | 학교 코드만 사용, 학년·반·학생 매칭 없음 | Compose 메모리 `neisEvents` → 홈 개수/첫 일정 | 실제 전경 조회 코드, 영구 수집·맞춤 브리핑 아님 |
| 웹 세션 점검 | 12시간 WorkManager → 로컬 CookieManager 확인 | 없음 | SiteConnection.lastCheckedAt 또는 재로그인 상태 | 쿠키 존재 점검, 네트워크 개인 공지 수집 아님 |
| e알리미/하이클래스 웹 | 사용자가 연결 열기 → 공식 WebView 페이지 | 사용자가 사이트에서 로그인, 앱은 인증/자녀 조회 안 함 | origin·사용자 완료 표시·쿠키 → 시험 연결 UI | 실제 웹 열기, 개인공지 ingestion·인증 갱신 미구현 |
| 나이스 학부모서비스 | 연결 catalog available=false | 없음 | 미지원 표시 | 미구현 |
| 등하교/활동 미디어 | main 소스에 전용 조회·adapter 없음 | 없음 | 전용 사건·미디어 저장/UI 없음 | 설계 문서만 존재 |
| 공유 첨부/OCR | main 소스에 import/OCR 실행 경로 없음 | 기존 설계만 존재 | 첨부 원문·OCR 저장 없음 | 계획 단계, 현재 비푸시 획득 수단 아님 |
| CalendarProvider | 엄마 일정 명령 → 선택 캘린더 읽기/쓰기 | 명령 목적지 기준 | 생성 일정 receipt | 0.8 일정 실행 기능이며 학교 공지 수집과 별개 |

## 2. 나이스 공개 일정의 실제 흐름

`MainActivity.kt:465`의 `onConnectNeis`가 `findExactSchool(settings.schoolName)`을 호출한다.
`NeisPublicClient.kt:34`는 학교 검색 HTTP 요청과 후보 해석을 구현한다. 인증 정보의 존재 여부에 따라 페이지 크기를 달리하며, 이 점검에서는 실제 인증 설정을 확인하지 않았다.
검색에 성공하면 학교 코드를 포함한 metadata와 `CONNECTED`를 저장한다(`MainActivity.kt:469`). 이 순간은 학교 검색 성공이지 공개 일정 조회 성공이 아니다.
`MainActivity.kt:355`의 `LaunchedEffect(connectorState.sites["neis-public"])`가 `refreshNeisEvents`를 호출한다.
`refreshNeisEvents`는 학교 코드로 `upcomingEvents`를 호출하고 성공하면 화면 메모리의 `neisEvents`에 할당한다(`MainActivity.kt:155`, `:165`, `:168`).
이 Effect는 주기 scheduler가 아니며 앱이 배경에 있어도 계속 실행되는 작업이 아니다. 동일 composition의 단순 resume·날짜 변경만으로 조회하도록 설계되어 있지 않다.
`NeisPublicClient.kt:59`는 기본 오늘부터 30일 범위, `pIndex=1`, 샘플 모드 5건/그 외 100건으로 요청한다.
행에서 `AA_YMD`, `EVENT_NM`, `EVENT_CNTNT`만 `NeisEvent`로 옮긴다. 학년 대상 정보나 전체 페이지 수를 저장·필터링하지 않는다.
기준 날짜는 `LocalDate.now()`의 기기 기본 시간대다. 기존 행동 판단의 Asia/Seoul 기준과 일관되는지 확인이 필요하다.
`MainActivity.kt:112`의 `remember` 목록은 process 재생성 뒤 유지되는 저장소가 아니다. `ProbeDatabase`에는 나이스 일정용 테이블이 없고 일반 알림 저장으로 변환하는 호출도 없다.
`HomeScreen`에는 `schoolEvents`로 전달되며 `ProbeScreens.kt:299`에서 개수와 첫 일정 또는 기본 문구를 보여준다. 전체 일정 열람·선별 완료를 나타내는 별도 성공 증거가 없다.
`LocalAgentContext` 입력은 records/tasks/childProfile이고 neisEvents가 없다. `BriefingReceiver`도 저장된 알림과 부탁만 읽는다. 나이스 일정이 대화·예약 브리핑에 들어가는 연결은 없다.

## 3. 웹 점검과 로그인 표시의 실제 의미

`ProbeApplication.kt:28`은 `website-session-health`를 12시간 주기로 등록하고 네트워크 연결 제약을 건다.
그러나 `WebsiteHealthWorker.doWork`는 e알리미/하이클래스의 SESSION_READY/CONNECTED 상태만 골라 `hasStoredSession`을 호출한다.
`WebsiteSessionManager.kt:11`은 허용 호스트의 쿠키 문자열이 비어 있는지를 확인한다. HTTP 요청·서버 세션 검증·refresh token 갱신을 하지 않는다.
쿠키가 있으면 `markSessionChecked`, 없으면 `requireReauth`다. WorkManager `Result.success()`는 이 로컬 점검의 완료이지 새 공지를 읽었다는 증거가 아니다.
`ConnectorRepository.kt:55`는 lastCheckedAt만 갱신한다. 이 값을 “마지막 공지 수집 성공”으로 사용하면 잘못된 표시가 된다.
`WebsiteLoginActivity.kt:99`는 사용자의 “로그인 마쳤어요” 버튼 뒤 `markObserved(..., true)`를 호출한다. origin만 저장하고 SESSION_READY가 된다.
실제 인증된 개인정보 GET·자녀 ID 대조·공지 목록 읽기·첨부 획득·cursor 갱신이 이어지지 않는다. 페이지 내부에서 사이트가 동작해도 모모 저장소 ingestion으로 연결되지 않는다.
현재 `ConnectionsScreen`의 SESSION_READY 문구는 “사용자가 완료로 표시 · 인증 미확인”이며 하단에도 개인공지 자동 조회·로그인 갱신 미지원이라고 설명한다. 이 정직한 경계는 유지해야 한다.
`websiteStatus(CONNECTED)`의 “정보 확인됨”은 실제 웹 조회 증거가 생겼을 때만 써야 한다. 현재 production에서 웹 CONNECTED로 승격하는 읽기 구현은 확인되지 않았다.

## 4. 내 아이 매칭이 이뤄지는 곳과 이뤄지지 않는 곳

알림 수집 `ProbeRules.canCapture`는 동의·선택 package·알림 접근·ongoing/group summary 등을 검사한다. 해당 알림이 특정 자녀의 공지인지 인증하는 절차는 아니다.
`NoticeDecisionEngine`은 이미 저장된 알림의 명시된 학교급·학년 범위와 설정값을 대조한다. 범위가 없으면 일반 공지로 APPLIES 처리하는 현재 코드가 있다.
따라서 이는 알림 내용의 제한적 관련성 분류다. 실제 학교 계정의 내 자녀·반·담임 연결 검증과 같지 않다.
나이스 일정은 이 판단 엔진을 거치지 않는다. 학교만 같으면 다른 학년 일정도 현재 표시 목록에 남을 수 있다.
`ConnectorRepository`의 별도 child 구조와 `ProbeSettings`가 있으며 학교 연결은 주로 후자를 갱신한다. SiteConnection.childId는 개인 서비스가 반환한 검증 자녀 ID가 아니다.
등하교 사건의 child ID·기관·eventTime 검증과 미디어 게시물의 접근 범위 모델은 `ATTENDANCE_AND_MEDIA_PLAN.md`의 계획이다. 현재 main 소스에는 이 adapter들이 없다.
“우리 아이에게 맞는 정보 수집 완료”를 입증하려면 실제 수집 단계의 계정/학교/자녀 범위와 내용의 적용 대상 범위가 모두 확인되어야 한다.

## 5. 먼저 차단해야 할 성공 오표시 위험

| 우선 | 근거 | 사용자가 오해할 수 있는 점 | 다음 수정의 통과 조건 |
|---|---|---|---|
| P0 | 비푸시 개인공지 worker/ingestion 자체가 없음 | 푸시를 놓쳐도 앱이 대신 사이트를 확인한다고 믿음 | 화면에 출처별 ‘새 알림만/공개 일정 전경 조회/개인 조회 미지원’ 구분 |
| P0 | 학교 검색 성공만으로 CONNECTED | 일정 API 실패인데 연결이 켜져 있으니 최신 일정 수집 성공으로 인식 | 학교 식별과 일정 fetch 상태·최종 성공 시각 분리 |
| P0 | `upcomingEvents`는 HTTP 200 JSON의 `INFO-200`만 특수 처리 후 나머지 rows를 Success로 반환 | 오류 JSON/형식 변경의 빈 rows를 일정 0건으로 오인 | 정상 응답 schema·API 성공 코드 검증, error/empty 구분 |
| P1 | pIndex 1만 조회, total/truncation 정보 없음 | 화면 개수를 전체 일정 수로 인식 | pagination 또는 부분조회·완전성 상태 표시 |
| P1 | NeisEvent에 학년·대상 속성 없음, 필터 없음 | 다른 학년 행사도 내 아이의 일정으로 인식 | 대상 데이터 보존·검증된 필터 또는 ‘학교 전체 공개 일정’ 명시 |
| P1 | 결과 메모리만 보관, 실패 시 이전 목록 유지 가능 | 오래된 목록을 최신 결과로 인식 | persisted fetch snapshot + stale/error 시각 상태, 새 학교 전환 시 이전 목록 분리 |
| P1 | 쿠키 점검의 lastCheckedAt 갱신 | 로그인/공지 읽기가 성공했다고 인식 | healthCheckedAt/authVerifiedAt/contentFetchedAt 분리 |
| P1 | 학교 검색 exact 일치가 없으면 단일 근사 후보도 채택 | 실제 입력 학교와 다른 학교가 연결될 수 있음 | 지역·공식 학교명·학교 코드 확인 결과를 1회 제시 |
| P1 | refresh 함수에 동의/세대 재검증이 없고 응답 반영은 학교 코드만 비교 | 동의·연결 변경과 동시에 도착한 결과의 안전 경계가 약함 | 시작/commit 시 동의·연결 generation 검증 |
| P2 | catalog 설명은 학사일정·급식·시간표, client는 학교/일정 endpoint만 있음 | 급식·시간표도 수집된다고 인식 | 실제 endpoint 범위에 맞춰 설명 |

위 위험은 코드상 경로를 근거로 한 판정이다. 실제 서버 오류 재현이나 특정 학교의 잘못된 일정 표시는 이번 점검에서 실행하지 않았다.

## 6. root가 QA 자녀로 수행할 기기 점검

실제 가족 설정을 바꾸지 말고 QA 앱/QA 자녀 프로필을 사용한다. 개인 계정 로그인 없이 가능한 검증부터 수행한다.

| 시험 | 절차 | 수집 증거와 기대 결과 |
|---|---|---|
| Q1 공개 일정 비푸시 조회 | QA 이름·학교급·학년과 검증 가능한 정식 학교명 설정, 선택 앱의 새 알림 없이 나이스 연결 | 학교 검색/공개 일정 화면은 푸시 없이 조회 가능. 이를 개인공지 자동 수집 성공으로 기록하지 않음 |
| Q2 학교와 일정 성공 분리 | Q1에서 학교 연결 뒤 네트워크 오류 상태를 확인하거나 기존 연결을 오프라인 재진입 | 연결 toggle과 일정 조회 오류/0건 표시가 구분되는지 관찰. 부족하면 현재 false-success 재현으로 기록 |
| Q3 영구 저장 부재 | Q1의 화면 결과를 확인하고 QA 앱 process를 종료, 네트워크 없이 새 process로 열기 | neisEvents는 보존되지 않는 현재 제한 확인. 일반 공지 DB/대화에 영구 수집된 것으로 주장 금지 |
| Q4 자녀 맞춤 여부 | 학교는 유지하고 QA 학년을 2↔5로 바꿔 같은 일정 범위 관찰 | 같은 학교 일정이 그대로면 학교별 조회 증거일 뿐. 서로 다른 학년 대상 자료가 없으면 맞춤 정확도는 미검증 |
| Q5 브리핑·대화 통합 여부 | 나이스 결과만 있고 수집 공지/부탁은 없는 QA 상태에서 브리핑과 관련 질문 확인 | 공개 일정이 입력으로 안 들어오는 현재 gap 확인. “관련 일정 없음”을 전체 학교 조회 결과로 해석하지 않음 |
| Q6 background 실행 역할 | QA WorkManager 상태/실행 이력으로 등록 이름과 실행 결과 확인 | website-session-health success는 쿠키 점검만 의미. 12시간 기다리거나 실행을 강제해도 공지 수집 기능은 생기지 않음 |
| Q7 푸시 없는 개인공지 | 새 push를 보내지 않은 상태로 연결 화면의 웹 capability 문구 확인 | 개인공지 자동 조회 미지원 표시 확인. 실제 개인계정 없이 새 공지 존재/미수집을 실증했다고 쓰지 않음 |
| Q8 UI 실패 경계 | 미연결·학교 검색 실패·오프라인·정상 0건·샘플 제한 각 화면 캡처 | 0개/조회실패/아직조회전/부분결과 구별 여부 기록, 캡처에 실제 자녀·인증정보 노출 금지 |

Q6에서 로그를 읽는다면 worker 이름·상태·건수만 사용한다. URL 전체·KEY query·cookie·개인 원문을 출력하지 않는다.
정상 공개 일정과 개인공지 무푸시 획득은 서로 다른 수락 항목이다. Q1 통과가 Q7 기능의 존재를 입증하지 않는다.

## 7. 다음 구현 때 필요한 자동 회귀

HTTP 200 + 정상 rows / 공식 no-data / API error JSON / key 오류 / 비JSON / 응답 제한 초과를 서로 다른 결과로 검사한다.
전체 101건·샘플 5건·복수 페이지·중복 개정·삭제된 행사에 대해 완전성과 cursor 상태를 보존한다.
학교 변경·학년 변경·동의 철회·연결 해제 중 이전 요청의 늦은 응답이 잘못된 자녀/학교에 저장되지 않아야 한다.
공개 school-wide·학년 한정·대상 미기재·조건 불명 자료의 매칭을 구분한다. 미기재를 개인 자녀 계정 검증으로 승격하지 않는다.
worker가 실제 fetch→검증→암호화 저장→source별 cursor/fetch status→UI→브리핑까지 이어지는 통합 테스트를 작성한다.
현재 connector 테스트는 주로 HTTPS allowlist와 URL만으로 로그인 성공을 단정하지 않는 검증이다. 비푸시 fetch/store/child-matching 통합 검증을 대신하지 않는다.

## 8. 가능한 개발 순서와 완료 주장

1. 현재 조회 범위/실패/부분조회/최종 성공 시각을 정직하게 표시해 잘못된 신뢰부터 줄인다.
2. 이미 구현된 나이스 공개 endpoint의 정상·오류·pagination 해석을 정리하고 학교 전체/학년 대상 범위를 보존한다.
3. 동의·연결·네트워크 제약이 있는 주기 조회를 영구 저장과 연결한 뒤 대화/브리핑이 같은 snapshot을 사용하게 한다.
4. 개인 학교·등하교 서비스는 실제 서비스 이름·공식 읽기 방식·실계정 검증이 준비된 source 하나씩 붙인다. 쿠키 점검을 수집으로 바꾸어 부르지 않는다.
5. 미디어는 새 게시물 metadata와 원문 보기부터, 첨부는 별도 승인된 로컬 OCR 계획에 따른다.

현재 말할 수 있는 결과: “푸시 없는 자동 수집은 아직 완성되지 않았고, 공개 학교 일정의 화면 조회만 있습니다.”
이 문서는 부족한 경로와 검증 계약을 기록한 감사 결과다. 구현 완료·실계정 확인·실기기 무푸시 수집 성공을 보고하는 문서가 아니다.
