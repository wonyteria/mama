# 0.9 PUBLIC B — 제한 독립 소스 검토

검토자: GPT-6 Astra High. 2026-09-15 22:54~22:57 KST 읽기. 판정: **수정 필요**.

범위는 NeisPublicClient(compatibility API 포함), SchoolWebsiteParser/Client, PublicSourceFetchers 및 대응 tests다. core A/D, 개인 인증 adapter, HWP 바이트 추출기·그 향후 통합은 검토하지 않았다. Gradle/ADB/폰/실제 HTTP 요청을 실행하지 않았다.

검토 중 SchoolWebsiteClient가22:56:31에 변경됐다. 최초 지적 후 `issues += detail.issues`, cursor resume, 페이지 상한 PARTIAL이 추가된 것을 직접 읽었으며 아래 판정에 반영했다. source freeze 전체 또는 새 테스트 통과를 의미하지 않는다.

## B1 / P1 — NEIS compatibility가 API 오류를 정상0건으로 반환

위치: `NeisPublicClient.kt:89-106` upcomingEvents.

HTTP200 JSON `{"RESULT":{"CODE":"ERROR-290"}}`를 받으면 INFO-200 분기를 제외한 결과 코드를 검사하지 않고 rows(empty)를 Success(emptyList)로 반환한다. head.RESULT 오류도 검사하지 않는다. 현재 다른 caller가 compatibility API를 쓸 수 있으므로 새 fetch만 고쳐도 전체 계약은 충족되지 않는다. findExactSchool도 API 오류/schema를 단순 학교 없음 또는 fallback 결과로 처리하며 정확한 코드/학교명 없는 단일 row를 채택할 수 있다.

기대: compatibility와 새 fetch가 동일한 top-level/head RESULT·schema 판정 함수를 사용한다. ERROR/미지 응답은 Failure, 검증된 INFO-200만 정상0건이다. 키 있는 compatibility 일정 조회도 첫100행만 반환하는 제한을 표현해야 한다.

## B2 / P1 — NEIS pagination의 뒤 페이지가 이전 자료를 지우거나 거짓 complete

위치: `NeisPublicClient.kt:157-190,244-259`.

- page1에 정상 rows를 모은 뒤 page2가 INFO-200이면 새 SUCCESS_EMPTY(totalCount0,complete=true)를 반환해 이미 모은 items를 모두 버린다. 뒤 페이지0건은 전체 범위0건의 증거가 아니다.
- list_total_count가 없으면 상한10페이지/1000행을 읽어도 상한 issue를 만들지 않아 complete=true가 된다. 페이지가 반복돼 같은 rows를 반환해도 fetchedRows만 증가해 전체를 읽었다고 판단할 수 있다.
- `top.RESULT ?: head.RESULT`는 둘을 함께 검사하지 않아 한쪽 정상 값이 다른 쪽 ERROR를 가릴 수 있다. RESULT 누락도 rows만 있으면 통과하며, row의 날짜/학교 코드/필수 제목 유효성 확인 없이 VERIFIED를 만든다.

기대: 전체0건은 첫 정상응답·전체count 근거에서만 판정한다. 불일치/누락 count, 중복 페이지, cap 도달은 기존 items를 보존한 PARTIAL/ERROR이며 complete=false여야 한다. 실제 응답 office/school code와 scope를 대조하고 깨진 row를 검증 완료로 만들지 않는다.

## B3 / P1 — 학교 scope 및 상세 원문 identity의 대조가 부족

위치: `SchoolWebsiteClient.kt:145-157`; `SchoolWebsiteParser.kt:120-139,205-210`.

학교 client는 officialHost/path가 null이면 통과한다. 따라서 검증되지 않은 다른 학교 scope도 성남정자초의 고정 URL을 읽고 그 요청 scope의 자료로 반환할 수 있다. 상세 parser는 nttViewForm 문자열 존재만 검사한 뒤 HTML 전체의 첫 th.title/tr.cont를 선택하며 실제 form의 bbsId/nttSn과 요청 entry를 대조하지 않는다.

실제1953062 fixture의 `#nttViewForm`에는 hidden bbsId=12359, nttSn=1953062가 있다. 다른 itemId를 요청했는데 이 HTML을 받으면 현재는 요청한 ID의 원문이라고 라벨을 붙인다. 현재 client test도 모든 detail URL에 동일1953062 HTML을 반환해 이 불일치를 통과시킨다.

기대: 이 adapter는 정확히 검증된 canonical school/host/path만 허용한다. DOM 추출을 실제 form 안으로 제한하고 form의 게시판/게시물 식별과 요청을 확인한다. DOM contract 오류가 있는 notice를 VERIFIED로 만들지 않는다. 구조 누락이 batch PARTIAL이어도 그 record 자체를 검증한 원문으로 승격해서는 안 된다.

## B4 / P1 — 학교 페이지·시간 범위 완전성의 잔여

위치: 최신 `SchoolWebsiteClient.kt:62-68,90-139,188-201`.

최초 버전의 cursor 무시/5페이지 상한 뒤 complete 문제는22:56 수정에서 일부 해결됐다. 그러나 `(listPage.totalPages ?: page) <= page`는 페이지 총수가 누락된 schema를 마지막 페이지로 취급한다. cursor로 중간부터 재개한 실행도 coverage.pageStart는1이고 이전 페이지·coverageWindow/sourceGeneration 검증 근거 없이 complete를 만들 수 있다. 페이지 번호 검증도 상세 수집 뒤에 수행해 잘못된 페이지의 items가 먼저 쌓인다.

최근14일 filter는 하한만 확인해 미래 게시글을 포함하며 pinned는 학년도 제한 없이 과거 글도 모두 포함한다. 반복된 고정 공지를 같은 run에서 dedup하지 않아 상세30건 budget을 낭비하고 진행을 늦출 수 있다.

기대: 요청한 페이지·정상 totalPages/0건을 먼저 검증한다. complete는 실제 확인한 coverage와 checkpoint 세대/범위에 맞아야 한다. 이어읽기와 겹치는 최근 목록 재확인을 함께 유지하고, pinned 현재 학년도·게시일 상한 및 per-run identity dedup을 적용한다.

## B5 / P2 — 근거 없는 audience를 schoolWide APPLIES로 승격

위치: `SchoolWebsiteParser.kt:212-248`; `NeisPublicClient.kt` audienceForGrade.

학교 parser는 학년 근거가 없으면 APPLIES+schoolWide=true를 반환하면서 원문에 없는 `게시물 본문에서 특정 학년 제한을 찾지 못했어요`를 evidence로 넣는다. 실제 제한을 읽지 못한 것과 전학년 근거는 다르다. child grade 미등록인데 명시 학년 range가 있으면 UNKNOWN 대신 INELIGIBLE로 판정한다. 단순 마감/납부/제출 keyword만으로 REQUIRED를 만드는 부분도 부정문에 취약하다.

NEIS fact는 현재 child grade에 대한 한 개 판정만 저장하고 전체학년 flag는 evidence 문자열에만 둔다. 자녀 학년 변경 시 typed 사실만으로 재평가하려는 계약에는 실제 각 학년 적용 범위를 보존하는 편이 맞다. 원문 없는 대상/의무를 임의로 확정하지 않는다.

기대: 모르는 대상은 UNKNOWN, schoolWide는 명시 근거가 있을 때만 true. evidence는 실제 span/공식 field 값이어야 한다. `제출할 필요가 없습니다` 같은 부정문을 필수 행동으로 승격하지 않는 회귀가 필요하다.

## B6 / P2 — 전송 크기·URL 경계의 정확한 보장 부족

위치: `NeisPublicClient.kt:152`; `SchoolWebsiteParser.isAllowedSchoolUrl`; HTTP backend.

redirect 자동 추적 비활성, HTTPS/host/path 제한, connect/read timeout, 응답 readNBytes 상한은 확인했다. 그러나 NEIS runBytes는 실제 읽은 byte가 아니라 JSONObject 재직렬화 길이라 큰 공백 등으로 wire 총5MiB 제한을 초과할 수 있다. School URL allowlist는 port를 제한하지 않아 동일 host의 비표준 port도 통과한다.

기대: HTTP 결과가 실제 수신 byte 수를 전달하여 run budget을 계산하고, 관찰된 HTTPS 기본/443 port만 허용한다. 이후 HWP 다운로드 경로도 같은 검증을 통과해야 하며 지금 링크 보존만으로 첨부 다운로드/추출을 승인하지 않는다.

## 확인한 수정과 긍정 근거

- 새 NEIS fetch에서 API 키 없음은 PARTIAL+MISSING_API_KEY/SAMPLE_LIMITED, complete=false이며 sample5건을 전체로 저장하지 않는다. 이번 버전은 키 없는 실제 조회를 하지 않으므로 실제 일정 수집 성공이라고 보고할 수 없다.
- 1953062 fixture의 중첩 tr.cont는 tag depth로 보존된다. grade regex의 숫자 경계 및 `(?!도)`가2026학년도 끝6을 학년으로 오인하지 않도록 한다. 실제 fixture 제목/본문6학년을2학년 대상 외로 판정하는 테스트가 있다.
- 1951993 fixture는 HTML body가 비고 HWP 링크만 있어 ATTACHMENT_MISSING/LINK_ONLY다. 이미지·HWP를 읽었다고 말하는 구현은 현재 B 소스에 없다.
- 최초 SchoolWebsiteClient는 detail.issues를 batch에 올리지 않아 HWP-only가 FETCHED/complete가 될 수 있었으나,22:56:31 버전은 이를 합쳐 PARTIAL로 전파한다. 이 지적의 해당 누락은 해결 확인했다.
- PublicSourceFetchers는 실제 두 fetcher를 registry에 등록한다. 저장·알림·동의 설정을 B fetcher에서 직접 변경하는 경로는 발견하지 않았다.

## 검증 공백

현재 NEIS tests는 키 없음과 grade flag 부재만 다루고 API-error/두 페이지/누락 count/row scope mismatch를 다루지 않는다. 학교 client fixture는 같은 상세를 여러 ID에 돌려주는 방식이라 원문 identity 대조 증거가 되지 않는다. 수정 후 위 재현을 HTTP fixture로 검증하고 실제 raw1953062/1951993 fixture를 유지해야 한다.

source A/D와 실제 e알리미 인증은 이 문서의 승인 범위가 아니다. HWP/HWPX byte extraction은 별도 E 및 B 통합 뒤 새 범위로 검토해야 한다. 실제 네트워크·수집 저장·브리핑 통합 성공, 실제 자녀 적용률, 자동 주기 성공은 상위 담당자의 실행 증거가 필요하다.


---

# PUBLIC B 2차 제한 검토 + HWP download 연결 — 2026-09-15 23:25:30 KST

판정: **수정 필요**. B1~B6 수정과 B의 실제 첨부 download→extractor 결과 반영 경로만 읽었다. Document E 알고리즘은 별도 수정 중이며 이번에 승인하지 않았다. HTTP/Gradle/ADB/폰을 실행하지 않았다.

| 원 항목 | 상태 | 확인 결과 |
|---|---|---|
| B1 API 오류→빈 성공 | CLOSED, 오류 판정 | compatibility와 fetch가 top/head RESULT를 검사하고 ERROR/미지 code를 실패로 처리한다. compatibility upcomingEvents는 아직 첫 페이지 preview 수준이며 전체30일 수집 API로 사용하면 안 된다. |
| B2 NEIS 완전성 | OPEN, 부분 해결 | 뒤 page INFO-200에서 기존 items 보존, missing/변동 total PARTIAL, 중복 identity 탐지, 실제 byte count와 날짜/명시 학교 mismatch 검사 확인. 모순0건과 누락 학교 code는 잔여. |
| B3 학교 scope/상세 identity | CLOSED, 지적 재현 | 정확 학교명/host/path 요구, form 안으로 추출 제한, bbsId/mi/nttSn 대조가 생겼다. 불일치 상세는 items에 넣지 않는다. |
| B4 페이지/coverage | OPEN, 부분 해결 | current/totalPages 선검사, 상한 PARTIAL, cursor 재개+앞페이지 재확인, per-run pinned dedup 개선. checkpoint 세대/범위 및 resume complete 근거가 남는다. |
| B5 대상 근거 | OPEN, 부분 해결 | 학교의 근거 없음/미등록 grade는UNKNOWN이고 가짜 schoolWide/evidence를 제거했다. NEIS 저장 fact는 여전히 현재 child grade에만 묶여 있다. |
| B6 네트워크 경계 | OPEN, 새 download 직접 파급 | HTTPS443/redirect 금지/response cap 및 NEIS 실제 byte accounting은 확인. 첨부 여러 개의 전역 budget 검사가 늦다. |

## R1 / P1 — 한 게시물의 여러 첨부가 전체 run byte budget을 우회

`SchoolWebsiteClient.enrichAttachmentText`는 remaining run budget을 인자로 받지 않고 모든 attachment를 순서대로 download/extract한다. caller는 enrichment가 전부 끝난 뒤에야 bytesRead를 더하고5MiB를 확인한다. 한 게시물에 각각1MiB 이하 HWP가10개면 적어도10MiB를 읽고 모두 추출한 뒤 중단한다. 첨부 수 상한도 없다. per-response1MiB는 전체run5MiB를 대신하지 않는다.

기대: 남은 실제 byte/time budget을 download loop와 HTTP 읽기에 전달하고, 다음 첨부를 요청하기 전에 잔량을 확인한다. cap 도달은 현재까지의 검증한 text만 PARTIAL로 보존하고 더 다운로드하지 않는다. 이 문제는 E 내부 decompression cap과 별개다.

## R2 / P1 — 일부 첨부만 COMPLETE여도 원문 전체를 VERIFIED로 승격

`enrichAttachmentText`에서 HTML body가 비고 COMPLETE 텍스트 하나가 있으면 `!sawUnsupported`만 보고 VERIFIED로 바꾼다. 다른 attachment가 PDF/image라 skip되어 LINK_ONLY이거나 HTTP 실패인 경우 sawUnsupported는 false인 채 남는다. 즉 `완전 추출한 HWP A + 읽지 못한 첨부 B`가 VERIFIED일 수 있다. issues/batch PARTIAL만으로 문서 contentState의 거짓 승격을 상쇄하지 못한다.

기대: attachment-only 문서의 VERIFIED는 필요한 모든 첨부의 상태를 반영한다. LINK_ONLY/실패/미지원이 하나라도 남으면 문서-level PARTIAL/ATTACHMENT_MISSING이다. E가 명시 PARTIAL text를 돌려준 경우 이를 PARTIAL_EXTRACTION으로 유지하는 현재 분기는 맞다.

## R3 / P1 — NEIS 모순된0건 및 누락 scope field를 정상화

`resultCodes.all(INFO-200)`에서 실제 rows 존재 여부를 확인하지 않고 EMPTY로 반환한다. `list_total_count=0`인데 valid row가1개인 경우에는 fetchedRows>=total에서 끝나 FETCHED+complete=true가 될 수 있다. scheduleRowIssue는 office/school field가 blank일 때 검사를 건너뛰어 scope 확인이 불가능한 row도 VERIFIED로 만든다.

기대: INFO-200/total0/row count의 상호 일치 및 공식 필수 학교 필드를 검증한다. 누락/모순이면 items를 정상0건으로 덮지 않고 PARTIAL/ERROR로 남긴다. 앞선 B2의 뒤 페이지 처리 개선은 그대로 유지한다.

## R4 / P2 — resume checkpoint와 현재 coverage의 결합이 미검증

fetch는 checkpoint.cursor를 읽지만 checkpoint.sourceGeneration/coverageWindow가 현재 source와 일치하는지 확인하지 않는다. resume 중간 페이지부터 읽어도 coverage.pageStart는1이며 일부 중간 페이지를 현재 실행에서 건너뛴 사실과 이전 검증 근거를 complete에 반영하지 않는다. cursor 재개와 front page overlap 자체는 개선됐으나 모든 현재 범위를 읽었다는 증거와 같지 않다. 미래 게시글도 pinned이고 같은 학년도면 window 상한을 우회하는 분기가 남는다.

기대: checkpoint 사용 가능 scope/window를 검증하고, cumulative coverage 또는 현재 실행의 실제 coverage를 정확히 기록한다. 정확히 확인하지 않은 범위에 complete를 붙이지 않는다.

## R5 / P2 — NEIS의 grade 재평가용 typed 사실이 부족

NEIS audienceForGrade는 현재 grade2의 Y/N 판정 하나만 fact로 저장한다. 예를 들어2=N,6=Y 응답을2학년 scope에서 저장하면 typed fact는 gradeStart=gradeEnd=2와INELIGIBLE뿐이다. 이후6학년으로 바뀌어도 저장된 typed facts만으로6=Y를 재평가할 수 없다. 전체 flags는 evidence 설명 문자열에만 있다.

기대: adapter가 공식 각 학년 flag를 typed 범위/조건으로 보존하여 core가 현재 child와 비교하도록 한다. 이를 다시 문자열 parsing으로 해결하지 않는다.

## 실제 HWP 연결에서 확인한 부분

학교 상세 identity 확인 후, HTML/script에 실제 있는 URL 중 같은 board의 `/upload/snjj-e/na/bbs_<board>/` 아래 hwp/hwpx만 download한다. HTTPS443·허용 host·query/fragment 없음·magic prefix·redirect 비활성·응답 byte cap이 적용된다. root가 확인한1951993의 실제 literal URL 및 browser 파일 동일 hash는 root의 네트워크 증거이며 검토자가 새 요청을 보낸 것은 아니다.

명시 PARTIAL 추출 결과는 body에 읽은 text를 보존하되 PARTIAL_EXTRACTION과 issue/evidence를 전달한다.1951993의 부분 HWP 텍스트가 읽혔다고 해서 OCR/그림·전체 문서 완료로 승격하는 것으로 승인하지 않았다. 실제 extractor의 안전성은 `REVIEW_0.9_DOCUMENT.md` E1~E6 해결 후 별도 판정이다.

테스트 주의: 새 SchoolWebsiteClientTest는 `nowProvider=1_779_900_000_000L`(2026-05-28 KST)에 fixture 게시일2026-09-15를 사용한다. 새 미래 게시일 filter에서 해당 행이 빠져 HWP 통합 분기에 도달하지 못할 수 있다. 실제 QA clock과 fixture 날짜를 맞춘 뒤 실행해야 한다. 이번 확인은 timestamp 계산 및 소스 읽기이며 새 JUnit/실제 download 테스트를 실행하지 않았다.
