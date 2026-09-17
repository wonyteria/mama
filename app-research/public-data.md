# 앱 개발 전 공개 데이터·캘린더 접근 실험

실험일: 2026-09-13 KST. **공식 NEIS 서버에서 인증키 없이 실제 JSON 샘플을 받아 파싱했다.** 이것은 작성한 가상 fixture가 아닌 실제 HTTP 응답이다. 다만 받은 5건은 과거 학사일정이므로 현재 다가오는 행사나 보호자 개인 할 일 확보를 검증한 것은 아니다. 제품 앱 개발·기기 캘린더 접근은 하지 않았다.

## 실제 요청과 결과

공식 학사일정 문서는 키 없는 5건 sample과 첫 페이지 고정을 설명하며, 실사용은 인증키를 요구한다. 데이터셋은 학교 주요 행사 날짜·명칭·내용·학년별 대상 여부, 매일 적재, 이용 허락 범위 제한없음을 안내한다. 초당 요청수나 일일 quota는 읽은 문서에서 구체적으로 확인하지 못했으므로 임의 수치를 적용하지 않았다. [NEIS 공식 학사일정 문서](https://open.neis.go.kr/portal/data/service/selectServicePage.do?cateId=A0005&infId=OPEN17220190722175038389180&infSeq=2&page=1&rows=10&sortColumn=&sortDirection=), [공식 개발자 가이드](https://open.neis.go.kr/portal/guide/apiGuidePage.do)

| 순서 | 요청/실행 | 관찰한 결과 |
|---|---|---|
| 1 | web 도구로 keyless SchoolSchedule URL open | 도구의 URL 안전 처리 오류로 응답 열람 실패. 서버 데이터가 없다는 뜻으로 해석하지 않음 |
| 2 | Node 기본 fetch로 [SchoolSchedule 기본 sample](https://open.neis.go.kr/hub/SchoolSchedule?Type=json&pIndex=1&pSize=5) | 2026-09-13 08:30:14 KST. HTTP 200이지만 JSON `ERROR-300` 필수 값 누락. 이 응답을 성공 데이터로 계산하지 않음 |
| 3 | PowerShell Invoke-WebRequest로 schoolInfo sample | 연결 거부. 오류 원인/프록시 설정은 확인하지 않음. 시스템 설정을 변경하지 않음 |
| 4 | Node 기본 fetch로 [schoolInfo sample](https://open.neis.go.kr/hub/schoolInfo?Type=json&pIndex=1&pSize=5) | 08:31:08 KST. HTTP 200, `INFO-000`, 5개 학교. 첫 결과의 공개 교육청/학교코드를 사용 |
| 5 | 동일 코드로 [학교 지정 SchoolSchedule sample](https://open.neis.go.kr/hub/SchoolSchedule?Type=json&pIndex=1&pSize=5&ATPT_OFCDC_SC_CODE=B10&SD_SCHUL_CODE=7010057) | 08:31:08 KST. HTTP 200, `INFO-000`, 5개 행사. 키·로그인·개인정보 입력 없음 |

공개 API 호출은 위 3회 Node GET와 실패한 PowerShell 1회로 제한했다. 검색·공식 문서 열람은 별도이며, 다수 학교/페이지 순회나 인증 제한 우회는 하지 않았다. Node 기본 fetch 요청에서 별도 인증정보·쿠키를 제공하지 않았다.

API 경로 `SchoolSchedule`는 공식 서버 응답으로 실제 동작을 확인했다. 웹 도구로 읽은 공식 상세 페이지에서는 동적 신청인자/출력표 일부가 비어 있었으므로, 그 텍스트만으로 모든 세부 인자가 문서화되어 있다고 주장하지 않는다. 성공 요청의 학교코드는 같은 공식 공개 `schoolInfo` 응답에서 얻었다.

## 받은 실제 공개 행사 샘플

모두 가락고등학교(B10/7010057). 이 학교는 해당 보호자의 학교라고 가정하지 않고 **공개 sample의 첫 학교**로 선택했다. 초등학생 타깃에 대한 학교급 적합성은 이 고등학교 표본으로 입증되지 않는다.

| 행사 날짜 | 행사명 | 대상 학년 표시 | 행사내용 |
|---|---|---|---|
| 2025-03-01 | 토요휴업일 | 1~3학년 Y | 빈 값 |
| 2025-03-03 | 대체공휴일 | 1~3학년 Y | 빈 값 |
| 2025-03-04 | 입학식 | 1학년 Y, 2~3학년 N | 빈 값 |
| 2025-03-04 | 개학식 | 1학년 N, 2~3학년 Y | 빈 값 |
| 2025-03-08 | 토요휴업일 | 1~3학년 Y | 빈 값 |

반환 `AY`는 2025, `LOAD_DTM`은 20260913이었다. **적재일이 오늘이어도 행사일이 미래라는 뜻은 아니다.** 응답은 전체 391건을 표시했으나 5건만 받았고 나머지를 내려받지 않았다. 현재 이후 날짜를 지정한 조회나 전체 미래 행사 누락률 평가는 수행하지 않았다.

정확한 증거 수준: 공식 운영 서버가 반환한 제한 표본이며 우리가 꾸며낸 데이터는 아니다. 그러나 공급자가 sample 데이터를 실제 레코드 그대로 제공하는지 별도 가상 레코드로 구성하는지 명시한 자료는 이번에 확보하지 못했고, 개별 행사 개최 사실을 학교 원문으로 대조하지 않았다. 따라서 **“공식 실응답 표본”**으로 표현하고 “실제 모든 일정이 정확함”으로 확대하지 않는다.

행사명·날짜·학년 플래그는 공개 일정 안내에 유용하다. 이 응답 18개 필드에는 개인 학생, 제출 기한, 준비물, 동의서 회신, 납부 완료 상태 필드가 없었다. 향후 다른 공개 설명 필드에 준비물 문구가 포함될 가능성을 부정하지 않지만, 그런 경우도 개인 회신/납부 상태를 증명하지는 못한다.

## 재현 가능한 증거 파일

- [최초 실패 응답과 메타데이터](public-data/probe-result.json): HTTP 200 / ERROR-300을 보존.
- [학교 조회 원응답](public-data/schoolInfo-response.json): 공식 공개 학교 5건.
- [학사일정 원응답](public-data/SchoolSchedule-response.json): 실제 반환 행사 5건.
- [파싱·요청시각·URL·SHA-256](public-data/school-event-result.json): 최소 행사 필드와 출처.
- [접근 가능 소스 레지스트리](public-data/source-registry.json): 증거 수준, 권한, 한계, 다음 검증.
- [오프라인 검증](public-data/verify-capture.mjs): 해시·원응답 매핑·과거 날짜·키 미사용 등 증거 검증.

`node D:\Codex\mom-agent\app-research\public-data\verify-capture.mjs`는 네트워크 없이 저장 응답을 검사한다. `probe.mjs`와 `resolve-school-sample.mjs`는 실행 시 공개 GET를 다시 수행하는 조사 스크립트다. 반복 수집 서비스나 제품 코드가 아니다.

실행 검증: 세 조사 스크립트의 Node 문법 검사 통과, 저장 응답에 대한 오프라인 검사 **7/7 통과(exit 0)**. [verification.json](public-data/verification.json)에 개별 결과를 남겼다. 해시 비교는 저장한 응답의 일관성 검사이며 서버 진위에 대한 전자서명 검증은 아니다.

초등학교/다가오는 기간으로 좁히는 후속 요청도 검토했다. 그러나 공식 schoolInfo와 학사일정 상세 문서의 텍스트·공개 HTML에서 학교급/날짜 필터의 구체적 인자 표를 확인하지 못했다. 비공식 코드에서 발견한 인자를 문서화된 것으로 가정해 호출하거나 내부 문서 로딩 경로를 추적하지 않고 중단했다. 따라서 **초등학교·현재 이후 날짜·참여 가정 선택 학교의 표본은 아직 확보하지 않았다.**

## Android 기존 캘린더 경로

Android Calendar Provider는 기기에 있는 캘린더와 일정을 조회하는 공식 경로다. Calendars로 캘린더 목록을, Events로 일정, Instances로 반복 일정별 시작·종료 시각을 다룬다. 읽기는 READ_CALENDAR, 직접 변경은 WRITE_CALENDAR가 필요하다. 동기화되지 않거나 Provider에 노출되지 않는 다른 앱 내부 일정은 읽을 수 있다고 가정하지 않는다. [Calendar Provider 공식 문서](https://developer.android.com/identity/providers/calendar-provider)

설계상 판단: 첫 연결은 **이미 쓰는 캘린더를 재활용**하는 것이 타당하다. 제품이 권한을 받은 뒤 사용자가 챙길 캘린더를 선택하고, 선택한 ID의 필요한 기간/필드만 처리한다. 이 선택은 제품의 필터 설계이며 OS가 캘린더 하나만 읽는 권한을 보장한다는 뜻은 아니다. 계정명/캘린더 내용 역시 로그·서버로 자동 전송하지 않는다. 아이나 배우자의 다른 계정 접근·가족 공유 동의도 대신하지 않는다.

READ_CALENDAR는 민감 권한이므로 지원 Android에서 실행 시 허용과 접근 직전 재확인이 필요하다. 거절/철회 시 해당 기능을 줄여 동작해야 한다. 이번에는 권한을 요청하거나 사용자 캘린더를 읽지 않았다. [권한 요청 공식 문서](https://developer.android.com/training/permissions/requesting), [READ_CALENDAR 정의](https://developer.android.com/reference/android/Manifest.permission#READ_CALENDAR)

권한 없는 사용자 확인 흐름이 목적이라면 Calendar Intent로 기존 캘린더 앱을 열어 추가/편집을 맡길 수 있다. 다만 이것은 일정 전체를 비서가 자동 읽는 연결이 아니다. 등록되지 않은 생일·경조사·가족 약속이 저절로 생기지도 않는다. 결제/서류 제출의 완료를 일정 제목만으로 추정해서도 안 된다.

필요한 실기기 검증: 실제 사용하는 앱의 Provider 노출, 선택 ID 유지, 반복·종일·시간대, 변경/삭제, 동기화 지연, 오프라인, 권한 철회, 다계정 분리. 현재 기기/SDK 검증은 하지 않았다.

## 사업에 활용할 판단

**NEIS 공개 일정은 원문 확보 위험을 낮추는 보조 연결 후보로 한 단계 진전했다.** 실제 공개 API 응답과 학년 구분을 확인했다. 그러나 경쟁 공급자도 NEIS를 사용하므로 이것만으로 독점적 차별점이 생기는 것은 아니며, 준비물·동의서·납부 비서의 핵심 가설도 통과하지 않는다.

우선순위는 개인 교육 공지의 본문/변경 접근 검증을 유지한다. 가족 일정 연결은 기존 캘린더 사용률과 노출 여부를 확인한 뒤, 이미 등록된 일정을 다시 입력하지 않게 하는 가치로 평가한다. “모든 가족 일정을 알아서 안다”는 약속은 현재 증거와 맞지 않는다.
