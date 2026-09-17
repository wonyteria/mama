# 0.9 푸시 없는 자동 조회·자녀 선별·저장·브리핑 구현

상태: Astra 설계. GPT-5.5 구현 전에 `TEST_SPEC_0.9.md`와 함께 적용한다. 이 단계에서 소스·빌드·기기 설정은 바꾸지 않는다.
최우선 목표는 새 push가 없어도 선택된 출처를 실제 조회하고, 성남정자초등학교 초등 2학년 범위로 선별하여 암호화 저장한 같은 자료를 홈·대화·아침/저녁 브리핑에 쓰는 것이다.
자녀 실명은 이 문서·공개 검색·로그·fixture에 사용하지 않는다. 신규 의존성은 도입하지 않는다.
상위 작업은 현재 release의 NEIS 키 설정 여부만 확인했고 미설정이었다. 따라서 실제 동작의 주 경로는 키 없이 읽는 학교 공식 웹이며, NEIS 전체 조회는 향후 키가 설정된 경우의 보강 경로다.
현재 0.8.1의 캘린더/잠금 해제/선택 흐름 수정은 별도 담당 소유다. 이 구현에서 되돌리거나 함께 재설계하지 않는다.

## 1. 반드시 연결할 세 경로

| 출처 | 0.9 목표 | 성공으로 간주하지 않을 것 |
|---|---|---|
| 나이스 공개 학사일정 | 정확한 학교 코드, 학년별 행사 조건, 요청 범위의 페이지를 읽고 저장·일정 브리핑 | 학교 검색 성공, API error를 0건, 첫 5건을 전체로 표시 |
| 학교 공식 공개 게시판 | 확인된 공지·가정통신문·초1~2 방과후 목록/본문/첨부 링크를 읽고 저장·관련성 판단 | 메뉴 존재, 제목만으로 본문 완료, HWP/OCR 성공 추측 |
| e알리미 개인 공지 | 모모 소유 WebView의 실제 관찰·검증된 인증 DOM에서 학교/자녀 범위를 확인하고 조회·저장 | 다른 앱 cookie 차용, 쿠키 존재, 로그인 완료 버튼, 임의 endpoint |

공개 경로와 개인 경로를 각각 완료 증거로 보고한다. 개인 endpoint 검증이 안 되면 다른 경로 작업은 계속하되 e알리미까지 완료됐다고 보고하지 않는다.
e알리미의 허용 URL·인증 DOM 표식·학교/자녀 필드·목록/상세 구조는 상위 작업의 실제 인증 화면 검증 결과를 adapter 입력 계약에 고정한다. 검증되지 않은 private API·차단된 JS asset을 조회하거나 추측하지 않는다.
등하교 메뉴만으로 개인 공지 계약을 출결로 확장하지 않는다. 하이클래스·타 학교 사이트·미디어 일괄 조회는 검증된 별도 adapter가 생기기 전 `UNSUPPORTED`다.

## 2. 실행과 상태 계약

`SourceSyncScheduler`는 연결 성공 즉시, 명시적 지금 확인, 네트워크 조건을 둔 6시간 주기, 마지막 시도 이후 30분 이상 지난 전경 복귀에서 같은 source work를 요청한다.
수신 push는 유일한 trigger가 아니다. 한 source의 실행은 unique work+mutex로 직렬화하고 같은 연결 generation의 중복 실행을 합친다.
학교/자녀/연결 범위가 바뀌면 source generation을 올리고 이전 실행을 취소한다. 늦은 응답이 새 자녀에 commit하지 못하도록 재검사한다.
주기는 정확한 시각 보장이 아니다. 정기 브리핑 시 snapshot이 오래됐으면 조회를 enqueue하고 마지막 성공 시각과 오래된 자료임을 표시한다.
7초 BroadcastReceiver 안에서 여러 HTTP 요청을 완료하려 하지 않는다. 조회는 WorkManager가 하고 브리핑은 저장된 snapshot을 읽는다.
초기 운영 제한은 source run 120초, HTTP connect 10초/read 15초, 공개 응답 1MiB, 전체 run 5MiB다. 제한 도달 시 PARTIAL/오류를 남긴다.
네트워크 실패만 제한된 지수 backoff로 재시도한다. AUTH_REQUIRED·UNSUPPORTED·schema 변경을 무한 재시도하지 않는다.

| 상태 | 정의 |
|---|---|
| NEVER / RUNNING | 아직 조회 안 함 / 실제 실행 중 |
| FETCHED | 요청 범위·자녀/학교 scope·응답을 검증하고 모든 수집 record를 commit함 |
| SUCCESS_EMPTY | 검증된 정상 응답이 요청 범위 0건임; 오류/로그인 HTML과 구별 |
| PARTIAL | 일부 페이지/상세/첨부를 확보 못함, sample 제한, run 상한 등 완전성 부족 |
| AUTH_REQUIRED | 인증 만료/권한 미승인/로그인 응답을 검증함; 재로그인 또는 공식 승인 필요 |
| UNSUPPORTED | 검증된 adapter/읽기 계약이 없음 |
| OFFLINE | 네트워크 이용 불가; 기존 snapshot은 stale로 남음 |
| ERROR | API 오류, 깨진 schema, 저장 실패 등 원인이 구별되는 실패 |

`lastAttemptAt`, `lastSuccessAt`, `lastCompleteAt`, `coverageWindow`, `seenCount`, `matchedCount`, `storedCount`, `attachmentState`, `reasonCode`를 분리한다.
lastSuccessAt은 실제 응답 검증+저장 후에만 갱신한다. lastCompleteAt은 FETCHED/SUCCESS_EMPTY의 완전한 범위에서만 갱신한다.
FETCHED이면서 특정 첨부가 미확보라면 문서 완전성을 별도 표시한다. 수집 batch 완료가 문서 의미 검증 완료를 뜻하지 않는다.
checkpoint 저장은 records transaction 성공 후에만 한다. 상태 저장 실패/중간 종료는 재조회하되 idempotent ingestion으로 중복을 막는다.

## 3. 작은 공통 자료형과 소유 인터페이스

| 계약 | 핵심 필드/책임 |
|---|---|
| SourceScope | sourceId, kind, canonical schoolCode/officialHost, child schoolLevel/grade, connectionGeneration, consentEpoch |
| SourceFetcher | `suspend fetch(scope, checkpoint): SourceFetchResult`; 저장·알림·설정 변경 금지 |
| SourceFetchResult | typed status, items, coverage/complete, cursor, evidence/issue; Cookie/키 값 없음 |
| FetchedNotice | sourceId/itemId/revisionHash, publishedAt nullable, firstSeenAt, title/body, canonical origin URL, audienceFacts, dateFacts, attachments |
| SourceIngestor | `ingest(batch, scope): IngestReceipt`; scope 재검증→암호화 transaction→신규/변경 identity만 반환 |
| RecordSourcePolicy | 저장된 record의 활성 출처·현재 학교·자녀 적용 범위·삭제·만료를 모든 소비자에서 공통 판정 |
| SourceSyncStateStore | 기존 ProbeCrypto로 암호화한 상태/checkpoint; 조회 성공과 연결 metadata를 분리 |

`ProbeRecord`에 뒤에 optional sourceMetadata를 추가해 기존 알림 JSON과 호환한다. 기존 Android 알림은 기본 sourceKind=NOTIFICATION으로 읽힌다.
비푸시 record는 실제 출처 kind와 stable itemId를 갖는다. 가짜 Android 알림을 발행해 listener로 재수집하거나 임의 실제 packageName을 붙이지 않는다.
비푸시의 비어 있는 notification 전용 필드를 결정 근거로 쓰지 않는다. identity는 `sourceId + itemId`, revision은 검증한 원문/typed facts hash다.
본문의 magic text로 sourceKind·VERIFIED·학년 적용을 승격하지 않는다. typed facts는 해당 adapter가 검증한 schema 범위에서만 전달한다.
공식 원문에 stable ID가 없으면 그 사실을 명시한다. NEIS는 확인된 공식 key가 없을 때 학교+날짜+행사명으로 보수적 identity를 만들고 제목 변경을 취소/정정으로 추측하지 않는다.

## 4. 기존 암호화 저장소와 중복·삭제

기존 `probe_records` 암호화 payload를 재사용하고 repository에 비푸시 전용 ingest API를 추가한다. listener의 동의·선택 package 검사를 느슨하게 만들지 않는다.
공개 source는 활성 connector 설정으로, notification source는 selectedPackages와 알림 권한으로 수집한다. 푸시 권한이 없어도 승인된 공개 웹 조회는 가능해야 한다.
비푸시 수집의 필요 조건은 유효한 일반 동의·활성 웹 source·canonical 학교/자녀 scope다. NotificationListener 권한·연결 플래그·selectedPackages가 비어 있음을 차단 조건으로 사용하지 않는다.
같은 item/revision은 재조회해도 record·부탁·알림을 추가하지 않는다. 새 revision은 기존 원문을 보존하거나 교체하되 한 identity의 최신 상태만 기본 표시한다.
신규 revision 처리에도 기존 완료·사용자 삭제 상태가 자동 부활하지 않게 한다. 사용자가 삭제한 source item은 현재 14일 경계 동안 동일/재수집 개정에서 숨긴다.
동일 e알리미 공지와 학교 웹 공지는 원문 링크/명시된 문서 식별이 일치할 때만 연결한다. 제목만 같은 문서를 영구 병합하지 않는다.
반복 fetch로 receivedAt을 매번 갱신해 원문 보관을 무기한 연장하지 않는다. lastFetchedAt은 별도다.
upcoming 일정은 날짜·제목·학년 근거만 담는 최소 정규화 snapshot으로 매 조회 검증하며 불필요한 원문을 14일 넘게 보존하지 않는다. 만료 뒤 다시 확보한 자료와 사용자 삭제는 구별한다.
일정 삭제/취소는 완전한 동일 범위 재조회 또는 공식 취소 근거가 있을 때만 반영한다. PARTIAL·오류·로그인 만료 때 목록에서 사라졌다고 삭제하지 않는다.
모든 records commit·TaskPlanner 후속 효과 전에 consentEpoch/sourceGeneration을 재검사한다. reset은 work·snapshot·checkpoint·캐시·예약을 함께 정리한다.

## 5. 나이스 공개 일정 adapter

공식 학사일정 설명은 학년별 행사 여부를 제공하며 sample은 page=1/size=5로 제한된다. [공식 명세](https://open.neis.go.kr/portal/data/service/selectServicePage.do?cateId=A0005&infId=OPEN17220190722175038389180&infSeq=2&page=1&rows=10&sortColumn=&sortDirection=).
현재 웹 문서의 동적 출력 필드 표는 조회 도구에서 비어 있었다. 학년 필드의 정확한 key/값은 상위 작업의 공식 원응답과 대조한 fixture로 고정한 뒤 구현한다.
2학년 적용은 API의 2학년 행사 flag가 명시적으로 해당임을 보일 때만 APPLIES다. 다른 학년 Y를 일반 학교 전체로 확대하지 않는다.
다른 학년만 해당이면 INELIGIBLE, 해당 flag 누락/미지 값이면 UNKNOWN이다. title의 ‘학교’나 자녀 설정 숫자만으로 포함하지 않는다.
공식 전체학년 근거가 있거나 모든 학년 flag가 해당이면 학교 공통 일정으로 포함한다. 모든 flag가 비해당일 때 전체학년으로 역해석하지 않는다.
학교 공식명뿐 아니라 교육청/학교 코드로 scope를 비교한다. 같은 이름의 다른 학교는 자동 채택하지 않는다.
앞으로 30일을 Asia/Seoul 날짜 기준으로 조회하고 `list_total_count`와 읽은 row 수를 대조해 pIndex를 진행한다. 상한은 10페이지/1,000행이며 넘으면 PARTIAL이다.
sample key 제한을 우회해 전체 조회인 척하지 않는다. 키 없으면 PARTIAL+“최대 5건 시험 조회”; 정상 전체 수집을 위해 실제 키가 필요한 상태를 표시한다.
HTTP status·JSON schema·top-level RESULT·head.RESULT를 함께 검사한다. 공식 정상 코드/정상 0건만 성공이고 ERROR/미지 응답/잘못된 key는 빈 성공이 아니다.
캘린더 날짜는 EVENT 역할의 date-only로 보관한다. 시간 없는 행사에 23:59 마감·준비 알람을 만들지 않는다.

## 6. 성남정자초 공개 게시판 adapter

검증한 host는 `snjj-e.goesn.kr`, 학교 path는 `/snjj-e/`다. [공지](https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttList.do?bbsId=12354&mi=14296), [가정통신문](https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttList.do?bbsId=12359&mi=14305), [초1~2 방과후](https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttList.do?bbsId=12570&mi=14704) 세 게시판만 시작한다.
목록의 게시물 ID·제목·게시일·공지 고정 여부·상세 URL을 추출하고 새로운/변경된 상세 본문과 첨부 링크를 읽는다. 관련 없는 네비게이션/채용 목록을 본문으로 저장하지 않는다.
상위 작업의 실제 DOM 관찰 계약: 목록 `a.nttInfoBtn[data-id]`와 해당 행의 날짜, 상세 `#nttViewForm th.title`의 제목, `#nttViewForm tr.cont`의 본문이다. cont에는 중첩 table이 있으므로 첫 `</tr>`까지 자르는 단일 정규식을 쓰지 않는다.
검증 상세 예: [학교 원문 1953062](https://snjj-e.goesn.kr/snjj-e/na/ntt/selectNttInfo.do?mi=14305&bbsId=12359&nttSn=1953062). 6학년 대상으로 확인됐으므로 2학년 기본 브리핑에서 제외한다. HWPX가 함께 있어도 풍부한 HTML 본문에서 읽힌 사실은 사용할 수 있다.
신규 의존성 없이 이 사이트에서 관찰한 HTML 구조에 한정한 parser를 작성한다. `Html.fromHtml`의 표시 텍스트/링크 보조 사용은 가능하나 표 구조·본문 범위를 보장하지 않는다.
구현은 관찰된 selector를 사용하는 제한된 WebView DOM reader 또는 동등한 범위의 중첩 tag 균형을 보존하는 기본 API parser로 한다. 단순 HTML 전체 strip과 제목 검색만으로 본문 성공을 표시하지 않는다.
실제 HTML fixture의 본문 container·anchor/data 속성을 근거로 처리하고 임의 JS 실행·범용 regex 스크래핑·모든 학교 지원을 주장하지 않는다. 구조 변경이면 ERROR/PARTIAL이다.
RSS는 링크 존재만 확인됐고 해당 자원 접근이 조회 도구에서 차단됐다. 우회 접근하지 않으며 검증된 HTML 목록/상세 collector를 주 경로로 사용한다.
초기 범위는 최근 14일 게시글과 현재 학년도 고정 공지다. 초기 상한 3게시판×5페이지·상세 30건, 도달하면 PARTIAL/cursor로 다음 실행에 이어 읽는다.
기존 수집 글은 원문 hash로 개정을 확인하고 겹치는 목록 범위를 재조회한다. `lastSeenId` 하나만으로 과거 글 수정·고정공지 변경을 놓치지 않는다.
2학년/초1~2/전학년은 내용 근거로 분류한다. 교직원 선거·채용·강사 모집은 학부모 행동 브리핑 제외, 방과후 희망자 모집은 OPTIONAL 유지다.
검증된 공식 게시물 category가 초1~2라고 명시한 경우 typed audience 근거로 사용할 수 있다. 하지만 ‘초1~2 맞춤형, 수익자 방과후’처럼 여러 범주를 함께 담은 게시판 이름만으로 모든 글을 초1~2에 배정하지 않는다.
e알리미의 홈페이지알리미가 같은 학교 canonical 게시물 URL을 가리키면 검증된 URL/게시물 ID로 묶고 origin 별 조회 상태는 유지한다.
첨부 링크는 원문에 실제 존재하는 HTTPS URL만 정규화·검증한다. 추가 다운로드 domain은 관찰된 학교 공식 제공 경로만 허용한다.
HWP/HWPX/PDF/이미지 첨부 링크를 저장하되 미해석 자료는 ATTACHMENT_MISSING/PARTIAL이다. 본문이 ‘첨부 참조’뿐이면 날짜·학년·준비물을 추측하지 않는다.
0.9에서 새 OCR/한글 parser 의존성을 끼워 넣지 않는다. 이미지·HWP만 있는 내용까지 해결됐다고 주장하지 않는다.

## 7. e알리미 인증 adapter

명세는 상위 작업에서 확인한 실제 authenticated DOM 구조로만 채운다. 미확정 동안 adapter 결과는 UNSUPPORTED이며 가짜 빈 목록을 반환하지 않는다.
최초 로그인은 모모의 공식 URL WebView에서 한다. 이미 설치된 e알리미 앱의 로그인/cookie를 빌리거나 추출하지 않는다. CookieManager는 모모가 소유한 WebView 세션만 사용한다.
DOM adapter는 허용 URL을 렌더하고 확인된 인증·학교/자녀 표식과 본문 container를 읽어 typed 결과로 만든다. 메뉴가 아니라 실제 공지 목록/상세 읽기 성공이 필요하다.
상수인 제한된 DOM 읽기 스크립트만 사용하고 본문 문자열을 JavaScript로 실행하지 않는다. 인증 cookie·비밀번호 input·hidden token을 JSON 결과나 로그로 꺼내지 않는다.
실제 학교/자녀 정보의 신뢰할 수 있는 scope 표식으로 성남정자초·2학년 범위를 검증한다. 이름만 같은 값이나 화면 메뉴로 scope를 확정하지 않는다.
WebView는 Main dispatcher에서 만들고 닫으며 worker의 제한 시간·취소/generation을 따른다. background에서 실제 인증 렌더가 성공하는지 실기기로 검증한다. 전경에서만 읽혔으면 무인 주기 수집 성공으로 보고하지 않는다.
로그인 DOM·인증 redirect 등 확인된 패턴은 AUTH_REQUIRED다. 렌더 실패/변경된 DOM은 ERROR이며 정상 0건으로 만들지 않는다.
세션 갱신은 공식 관찰된 흐름이 있을 때만 수행한다. 주기 Cookie 존재 확인이나 빈 요청을 “로그인 유지”라고 부르지 않는다.
AUTH_REQUIRED는 자동 fetch 재시도를 멈추고 브리핑/연결 화면에 한 번 묶어 알린다. 사용자가 공식 로그인 완료 후 실제 읽기가 성공해야 다시 FETCHED가 된다.
공지 조회는 읽기 범위만이다. 설문 제출·출결 정정·신청·전체 사진 다운로드를 실행하지 않는다.
root가 인증 화면 검증 결과를 주면 이 계약의 구체 DOM schema와 source fixture를 먼저 확정한다. 개인 계정의 실제 데이터는 비식별화하고 원문 cookie/이름을 repo에 넣지 않는다.

## 8. 자녀 선별과 브리핑 통합

canonical child는 ProbeSettings의 학교급·학년과 검증된 학교 코드다. ConnectorRepository의 중복 프로필을 별도 진실로 사용하지 않는다.
grade만 바뀌면 저장된 typed audience로 재평가한다. 학교가 바뀌면 이전 학교 source를 비활성화하고 결과를 새 아이의 소식으로 보여주지 않는다.
기존 `NoticeDecision`에 typed source/audience/date facts를 입력하되 근거·불명 상태를 유지한다. 본문 분석과 API flags가 충돌하면 UNKNOWN+issue다.
적용 가능한 학사일정은 INFORMATIONAL agenda로 홈·대화·브리핑에 포함한다. 기존 `isRequiredForChild()` 필터만으로 모두 사라지지 않게 공통 presentation selector를 만든다.
아침은 오늘 일정과 준비, 저녁은 내일 일정과 가까운 기한을 사용한다. 30일 자료를 매번 모두 읽어주지 않는다.
“일정 보기”는 저장된 관련 일정 전체를 열고, 대화의 오늘/내일/이번 주 조회도 같은 snapshot을 사용한다. 메모리 `neisEvents` 전용 진실을 제거한다.
TaskPlanner는 명시 필수 행동·적용 대상·분명한 날짜/근거에만 사용한다. 행사 자체·선택 프로그램·미해석 첨부를 자동 부탁으로 만들지 않는다.
동일 source item의 공지·일정·부탁은 브리핑에서 중복 제거한다. 사용자 완료/무시 상태와 공지 열람 상태는 유지한다.
출처별 마지막 성공과 stale/partial/auth 상태를 짧게 표시한다. 정상0건과 아직 못 읽음을 구별하며 “모든 앱 확인 완료” 같은 전체 성공 배지를 만들지 않는다.

## 9. GPT-5.5 파일 소유와 병합 계약

### 실제 첨부 확인 뒤 추가한 읽기 범위

공식 공지 1951993은 HTML 본문 없이 HWP 한 파일만 제공한다. 따라서 새 의존성 없이 구현 가능한 읽기 전용 HWP5(일반 텍스트, 압축 포함)·HWPX 텍스트 추출을 별도 담당 E로 추가한다. E는 `document/`의 추출기와 관련 테스트만 소유하고 공개 fetch 담당 B가 확보한 바이트를 받는다. 네트워크·로그인·저장·자동 실행은 E의 책임이 아니다.

공식 파일 형식 명세에 근거해 OLE/ZIP 체인·압축·XML을 검증하고 입력 크기, 체인 길이, 해제 크기, 출력 글자 수를 제한한다. 암호·배포 제한·지원하지 않는 버전이나 스캔 이미지만 있는 첨부는 해석 완료로 표시하지 않는다. 매크로·스크립트·링크 실행이나 보호 해제는 하지 않는다. 날짜·학년은 추출된 원문을 근거로 기존 판단 단계에서 검증한다. 실기기로 받은 공개 HWP는 `device-qa/public-fixtures/`에서만 검증하고 배포 소스에 통째로 묶지 않는다.

이미지/PDF OCR SDK 도입은 별도 승인과 검증이 필요하다. HWP 텍스트 추출 성공을 모든 그림·첨부 해석 성공으로 확대하지 않는다.

| 담당 범위 | 소유 파일/역할 | 다른 범위와의 경계 |
|---|---|---|
| A · 수집 계약/저장 통합 | 신규 `sync/SourceSyncModels.kt`, `data/SourceIngestor.kt`, `data/RecordSourcePolicy.kt`; ProbeModels/ProbeRepository codec·idempotency | 인터페이스 먼저 고정. 다른 담당은 repository 직접 수정 금지 |
| B · 공개 fetch | NeisPublicClient 보강, 신규 `connector/SchoolWebsiteClient.kt`, `connector/SchoolWebsiteParser.kt`, source parser fixtures/tests | `SourceFetcher` 구현만 반환, DB/UI/알림 변경 금지 |
| C · 인증 fetch | 신규 `connector/EalimiNoticeClient.kt`, `connector/EalimiDomReader.kt`, 모모 WebView 세션 수명 관리 | root의 실제 인증 DOM 계약 사용, 다른 앱 cookie·비밀번호·모의 성공 금지 |
| D · 실행/표시 통합 | 신규 `sync/SourceSyncScheduler.kt`, `sync/SourceSyncWorker.kt`, `sync/SourceSyncStateStore.kt`; ProbeApplication/MainActivity/ConnectionsScreen/BriefingReminders/LocalAgentEngine | A/B/C 반환을 통합. 0.8.1 잠금/캘린더 담당 완료 뒤 해당 파일 변경 |

동시 구현은 A의 자료형 계약이 고정된 뒤 B/C를 분리할 때만 가치가 있다. D는 A/B/C의 API에 의존하므로 순차 통합한다.
`TEST_SPEC_0.9.md`의 source→fixture→저장→기기 증거를 확보한 후 Astra가 오류/인증/범위/중복/브리핑을 독립 검토한다.
완료는 공개 두 경로와 e알리미를 각각 판정한다. 미검증 private source·첨부 형식을 숨긴 채 ‘모두 자동 조회 완료’로 마감하지 않는다.
