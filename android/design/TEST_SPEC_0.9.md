# 0.9 푸시 없는 자동 조회 수락 시험

선행 설계: `NO_PUSH_IMPLEMENTATION_0.9.md`. 대상은 성남정자초등학교 초등 2학년 QA 프로필이며 실명·실제 학번·쿠키·API 키는 fixture/로그에서 제외한다.
시험 증거는 A 공식 출처 계약 → B 비식별 실제 형식 fixture → C 저장/판단 통합 → D 실기기 무푸시 조회로 구분한다.
이번 문서는 시험 계획이다. 테스트/빌드/네트워크 인증/기기 실행을 수행한 결과가 아니다.

## 1. 출처 계약 확보

| ID | 사전 확인 | 통과 증거 |
|---|---|---|
| A1 | 나이스 공식 학교/학사일정 정상 응답 | 필드명·학년 flag 값·학교 코드·head RESULT·전체 row count를 원응답과 대조한 schema 기록 |
| A2 | 나이스 정상0건/API오류/sample 제한 | 오류와 0건 구조, sample page1/5건 제한을 공식 근거와 fixture로 구별 |
| A3 | 학교 세 게시판 목록/상세 | 실제 HTML의 게시물 ID·본문 경계·게시일·첨부 URL 구조, 인증 없는 공개 범위 |
| A4 | e알리미 실제 인증 조회 | 모모 WebView에서 root가 관찰한 허용 URL·학교/자녀 scope·인증 DOM·공지 목록/detail·로그인 만료 구조 |
| A5 | 개인정보 제거 | 실명/반번호/연락처/인증 cookie/query token 없는 fixture; 원본은 repo에 보관하지 않음 |

A4가 없으면 e알리미 성공 경로 구현·테스트가 완료됐다고 말할 수 없다. UNSUPPORTED 상태 시험은 실제 개인 공지 수집 성공 시험의 대체가 아니다.

## 2. 나이스 adapter 회귀

| ID | 입력/상황 | 기대 결과 |
|---|---|---|
| N1 | 학교 일치, 2학년 flag 해당 | 학교·2학년 근거와 EVENT 날짜를 보존, 관련 일정 1건 |
| N2 | 다른 학교의 같은 행사/학교명 | scope 불일치 거절, 현재 학교 저장·브리핑 0건 |
| N3 | 5학년만 해당/2학년 비해당 | 대상 외, 2학년 agenda/부탁/즉시 알림 0건 |
| N4 | 모든 학년 해당 | 전학년 근거로 관련 일정 1건, 필수 준비를 임의 생성하지 않음 |
| N5 | flags 누락/미지 값/모두 비해당 | UNKNOWN 또는 명시적 대상 외; 전학년으로 추정 0건 |
| N6 | API flag와 본문 대상 충돌 | issue+자동 행동 보류 |
| N7 | total=201, pSize100 | 3페이지까지 순서대로 조회·201 row 검증·중복 없음 |
| N8 | 2페이지 실패/최대페이지 초과/total 변동 | PARTIAL, 완전 조회 시각 갱신 없음, 누락된 기존 일정 삭제 없음 |
| N9 | 키 미설정 sample | PARTIAL/시험 조회 표시, page=1/5건을 전체로 주장하지 않음 |
| N10 | HTTP200 + API ERROR/로그인 HTML/비JSON/schema 누락 | ERROR 또는 검증된 AUTH_REQUIRED, SUCCESS_EMPTY 금지 |
| N11 | 공식 정상0건 | SUCCESS_EMPTY, 검증한 범위·성공 시각 기록 |
| N12 | date-only·월말·연말·시간대 변경 | Asia/Seoul 정확한 행사 날짜, 가짜23:59 기한·내년 이동 0건 |

## 3. 학교 HTML adapter 회귀

| ID | 입력/상황 | 기대 결과 |
|---|---|---|
| H1 | 공식 실제 목록과 상세 fixture | ID·제목·날짜·본문·첨부 링크가 원문과 일치 |
| H1b | a.nttInfoBtn[data-id], #nttViewForm th.title/tr.cont와 중첩 table | 실제 selector 범위와 전체 본문 보존, 첫 닫는 tr에서 본문 절단 0건 |
| H2 | nav/로그인 안내/footer/채용 목록 혼재 | 본문에 섞지 않음, 구조 미확인이면 ERROR/PARTIAL |
| H3 | 초2/초1~2/전학년/초5 안내 | 각각 관련/관련/관련/대상 외. 필수성은 별도 판단 |
| H3b | 실제 학교 게시물1953062의 6학년 HTML 공지 | HWPX 첨부 존재와 별개로 HTML 근거 보존, 초2 할 일/기본 브리핑 0건 |
| H3c | 초1~2 맞춤형과 수익자 방과후가 섞인 게시판 | 게시판 이름만으로 모든 글을 초1~2에 배정하지 않음 |
| H4 | 선택 방과후·희망자 신청 | OPTIONAL, 자동 신청/필수 준비 0건 |
| H5 | 교직원 선거·채용·강사 모집 | 부모 행동 브리핑/자동 부탁 0건 |
| H6 | 제목만 있고 ‘첨부 참조’ | 첨부 미독 상태, 날짜·물품·비용 생성 0건 |
| H7 | HWP/HWPX/PDF/이미지 링크 | 형식과 원문 링크 보존, 실제 읽지 않은 내용 완료 표시 0건 |
| H8 | 외부/비HTTPS/자격증명 포함 링크 | 다운로드 차단 또는 안전한 원문 안내, token 로그 없음 |
| H9 | 고정공지 재등장/과거 글 개정/페이지 중복 | stable identity 유지, 바뀐 revision만 commit |
| H10 | 목록 상한·일부 상세 timeout | PARTIAL/cursor, 전체 수집 성공으로 표시하지 않음 |

## 4. e알리미 인증 adapter 회귀

| ID | 입력/상황 | 기대 결과 |
|---|---|---|
| E1 | 실제 형식의 인증 공지+일치 학교/자녀 scope | 해당 공지 원문·첨부 상태 저장, source receipt 기록 |
| E2 | cookie 없음/만료/HTTP200 로그인 페이지/인증 redirect | AUTH_REQUIRED, 마지막 성공 유지, 재로그인 안내 한 묶음 |
| E3 | 임의 cookie만 있음 | 인증 성공·공지 수집 성공으로 승격 0건 |
| E4 | 다른 학교/동명이인/자녀 범위 불명 | 거절/보류, 해당 아이 기록으로 저장 0건 |
| E5 | 403/깨진 schema/새 페이지 형식 | 확인된 원인으로 ERROR/AUTH_REQUIRED, 정상0건 금지 |
| E6 | 다른 앱 로그인만 존재/허용 origin 밖 DOM 이동 | 타 앱 cookie 차용 0건, 범위 밖 DOM 결과 수집 0건, credential 노출 0건 |
| E7 | 공식 로그인 후 재실행 | 실제 authenticated fetch 성공 전까지 FETCHED 금지 |
| E8 | 조회 중 설문/출결/신청 버튼 존재 | 읽기 요청 외 mutation 0건 |
| E9 | WorkManager background WebView/화면 전환/취소 | Main dispatcher·timeout·파기·늦은 결과 차단, 전경 성공만으로 background 성공 주장 금지 |

## 5. 저장·작업·일정·브리핑 통합

| ID | 입력/상황 | 기대 결과 |
|---|---|---|
| I1 | 동일 batch 3회/worker와 manual 동시 실행 | 동일 item/revision 1건, 자동 부탁/알림도 중복 0건 |
| I2 | 원문 개정으로 날짜/준비물 변경 | 최신 개정 표시, 출처 이력 보존, 기존 완료·삭제 상태 부활 0건 |
| I3 | record transaction 성공 후 checkpoint 저장 실패 | 상태는 미완료, 재조회 안전, 중복/성공 오표시 0건 |
| I4 | transaction 저장 실패 | 성공 시각/cursor 전진 없음, 브리핑에 미저장 자료 노출 없음 |
| I5 | 동의 철회/전체 삭제/source 해제/학교 변경 중 늦은 응답 | commit·부탁·즉시 알림·캐시 부활 0건 |
| I6 | 14일 만료·반복 동일 fetch·사용자 삭제 | raw retention 무기한 연장 없음, 사용자 삭제된 item 즉시 재생성 없음 |
| I7 | collectionEnabled 앱알림 off, 공개 source on | 정책상 허용된 공개 조회 동작; Android 알림 access 우회는 없음 |
| I7b | NotificationListener 미승인/미연결·선택 앱0개, 공개 source on | 유효한 일반 동의·학교 scope로 학교웹 조회/저장/브리핑 성공 |
| I8 | 암호화 record reload/process 재시작 | 같은 source·행사일·학년 근거·상태 복원, 평문 민감정보 DB/로그 없음 |
| I9 | 공개 2학년 일정만 있고 push 0건 | 오늘/내일 agenda가 저장소→홈→대화→해당 브리핑에 동일하게 표시 |
| I10 | 적용 일정이 INFORMATIONAL | required 필터에 의해 사라지지 않음, 자동 부탁·즉시 알림은 만들지 않음 |
| I11 | 명시된 필수 물품/날짜/적용 대상 | shared NoticeDecision→TaskPlanner 통과, 같은 공지/부탁 브리핑 중복 0건 |
| I12 | e알리미/학교웹 동일 title, 다른 내용 | 제목만으로 병합하지 않음. 동일 공식 item/원문 링크가 있으면 중복 표시 억제 |
| I13 | OFFLINE/AUTH_REQUIRED/PARTIAL + 기존 snapshot | 마지막 성공 시각·미확인 범위 유지, 최신/전체 확인 문구 금지 |
| I14 | 정상0건 vs 미조회 vs API오류 | UI·대화가 세 상태를 구분 |

## 6. 실기기 무푸시 완료 시험

1. QA 앱에서 학교·초등2학년 범위와 source 설정을 확인한다. 기기 알림 수신 기록을 기준 시각부터 추적하되 개인 원문/키를 로그로 출력하지 않는다.
2. 나이스 source `지금 확인` 실행: 실제 HTTP 정상 응답→학년 선별→암호화 commit→source 상태→홈/일정 목록이 이어지는지 확인한다.
3. 학교웹 source `지금 확인` 실행: 실제 공개 게시물 ID/본문 일부/첨부 미독 상태와 저장 record를 대조한다. 조사 도구가 읽은 것으로 앱 동작을 대체하지 않는다.
4. actual request가 없어도 돌아가는 주기 worker는 QA에서 WorkManager 시험 수단으로 trigger를 검증하고, 기기 실행 이력에서 동일 production worker의 FETCHED/EMPTY/PARTIAL 상태와 저장 receipt를 확인한다.
5. 앱 process를 종료한 뒤 오프라인으로 열어 저장된 관련 일정과 마지막 성공 시각이 남는지 확인한다. 이전 `remember neisEvents`로만 보였던 동작과 구분한다.
6. QA 시각/fixture 제어가 가능한 환경에서 저장한 오늘/내일 관련 일정이 실제 브리핑 화면·TTS 텍스트·대화의 같은 날짜 답변에 나타나는지 확인한다. 날짜를 맞추려고 실제 학교 원문을 변조하지 않는다.
7. 앱 새 push 수신 0건과 source fetch로 저장된 record>0을 함께 증거로 남긴다. 특정 source에 실제0건이면 무푸시 정상0건 증거만 인정한다.
8. e알리미는 root의 검증된 로그인 범위에서 실제 authenticated fetch→학교/자녀 대조→공지 저장까지 별도로 확인한다. cookie 점검/메뉴 화면으로 대체하지 않는다.
9. source 해제/동의 철회 뒤 worker를 재실행해 새 네트워크·저장·알림 효과가 없는지 확인한다. 실제 개인계정을 임의 로그아웃·탈퇴시키지 않는다.

## 7. 출시 판단

기존 0.8.1 회귀와 신규 parser/adapter/ingestion/worker/selector 시험, lint·build를 통과한 다음 실제 source별 결과를 표로 보고한다.
필수 완료 행은 나이스 공개 fetch/2학년 선별/저장/브리핑, 학교웹 공개 fetch/본문·첨부 상태/저장/브리핑, e알리미 authenticated fetch/scope/저장이다.
현재 NEIS 키 미설정 상태는 상위 작업에서 확인됐다. 학교웹의 키 없는 실제 수집·2학년 선별·저장·브리핑이 주 완료 경로이고, NEIS는 sample PARTIAL을 정직하게 표시한다.
phone offline, 개인 인증 미확보, sample key 제한, 문서 형식 미지원은 해당 행의 미검증/부분 상태로 기록한다. 전체 성공으로 뭉치지 않는다.
공개 두 source 성공을 개인 source까지 완료했다고 과장하지 않으며, AUTH_REQUIRED를 구현했다는 사실만으로 자동 조회 기능의 핵심 목표가 완성됐다고 마감하지 않는다.
Astra 독립 검토는 API오류를0건 처리, 가짜 자녀 매칭, 쿠키만 인증승격, 개인정보 범위 확대, 중복/부활, stale 표시 누락을 우선 차단한다.
