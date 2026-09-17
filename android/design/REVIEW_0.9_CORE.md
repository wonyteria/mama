# 0.9 CORE A/D — 초기 독립 소스 검토

검토자: GPT-6 Astra High. 읽기 기준: 2026-09-15 23:05~23:08 KST. 판정: **수정 필요**.

범위: sync/*, SourceIngestor/RecordSourcePolicy, ProbeRepository/Models의 비푸시 경로, typed NoticeDecision 및 홈/대화/브리핑 연결. B 공개 adapter와 E 문서 추출기·개인 인증은 별도 검토이며 여기서 다시 승인하지 않는다. 기존0.8.1 종결 기능을 재개방하지 않았다. Gradle/ADB/폰/실제 네트워크 요청을 실행하지 않았다. 코어 담당자가 테스트·grade2 preset 제거를 진행 중이라는 점을 반영하여 그 알려진 preset 수정 자체는 별도 blocker로 중복 제기하지 않는다.

## C1 / P1 — cold WorkManager 실행이 저장소 로드 전에 성공 종료

위치: `SourceSyncScheduler.kt` SourceSyncWorker.doWork→SourceScopeFactory.scopeFor; `ProbeApplication.kt` 등록 경로.

Worker는 repository/connector 초기 로드를 기다리지 않고 scopeFor를 호출한다. scopeFor는 !isReady면null이고 Worker는 즉시 Result.success로 끝난다. 앱 process가 없는 상태에서 정기 작업으로 처음 깨어나 새 ProbeRepository가 비동기 로딩 중이면 네트워크 조회를 한 번도 하지 않고 작업이 완료된다. 전경 manual 조회가 성공해도 무인 주기 조회가 됐다는 증거가 아니다.

기대: 제한 시간 안에서 실제 저장소 및 connector scope 준비를 기다린 뒤 유효 source를 결정한다. 초기 로딩과 진짜 비활성/동의 없음은 구별해야 한다. source mutex를 기다린 뒤에도 최신 scope/generation을 재확인한다.

## C2 / P1 — source 해제/reset과 fetch·ingest·checkpoint가 하나의 권한 경계로 묶이지 않음

위치: SourceSyncWorker.doWork, SourceSyncStateStore.markRunning/recordResult/reset, ProbeRepository.ingestSourceLocked 및 MainActivity disconnect/reset.

Worker의 source generation 검사는 ingest 전에 한 번뿐이다. repository는 consentEpoch/학교/학년은 검사하지만 현재 연결 활성 여부와 connectionGeneration은 검사하지 않는다. 실제 재현: worker가 기존 generation을 확인한 뒤 repository mutex 앞에서 대기 → UI가 NEIS disconnect/bumpGeneration/clearSourceRecords → worker ingest. clearSourceRecords는 captureEpoch를 바꾸지 않으므로 old scope가 다시 들어갈 수 있다.

SourceSyncStateStore의 markRunning/recordResult에는 consent/source generation commit gate가 없다. reset 후 늦은 응답·timeout·예외 처리도 새 state/checkpoint를 만들 수 있다. 이전 scope로 mutex를 기다린 job이 새 연결의 상태를 덮을 수 있다. 상태 저장소 decrypt/JSON 실패를 emptyMap으로 바꾸는 것도 generation/checkpoint 소실을 숨긴다.

기대: 활성 source·현재 canonical scope·source generation을 actual records/state commit lock 안에서 확인한다. 늦은 실패 상태도 reset 뒤 쓰지 않는다. 상태 읽기 실패는 fail closed여야 한다. 현재 repository records의 동의/학교 guard와 암호화 transaction 자체는 유효한 부분이나 source 연결 해제까지 대체하지 못한다.

## C3 / P1 — 연결 활성 정책 미사용·취소 없는 worker·이전 자동 부탁 잔존

위치: SourceScopeFactory.scopeFor/neisScope, SourceSyncScheduler, DefaultRecordSourcePolicy, SourceRecordSelectors, MainActivity의 source 해제/학교 변경.

- NEIS scopeFor는 connection 객체 metadata만 읽고 CONNECTED 상태를 요구하지 않는다. periodic worker는 enabledSourceIds를 경유하지 않으므로 DISCONNECTED 연결의 남은 metadata로 조회할 수 있다.
- WorkManager의 periodic/one-time 작업 cancel 경로가 없으며 전체 reset/source disconnect도 실제 unique work를 취소하지 않는다. AUTH_REQUIRED/UNSUPPORTED 상태도 다음 periodic/foreground enqueue에서 다시 호출된다.
- DefaultRecordSourcePolicy는 정의만 있고 홈·대화·브리핑 소비자에서 쓰지 않는다. SourceRecordSelectors는 현재 학교/source 활성/authorization/generation을 받지 않고 raw records를 읽는다. UI의 eager clear가 실패하거나 늦은 ingest가 들어오면 옛 학교·출처 자료가 표시된다.
- source 해제·학년 변경은 records만 지우고 그 source에서 만든 AUTO_NOTICE task는 보류/재평가하지 않는다. 자동 부탁은 source opaque ID만 연결돼 있어 records 삭제 후에도 다른 학교/대상 행동이 홈·브리핑에 남을 수 있다.

기대: 수집·표시·자동 부탁의 현재 source 정책을 공통으로 적용하고 실제 WorkManager cancel/재로그인 재개 조건을 연결한다. 학년만 바뀐 경우 기존 typed 사실로 재평가할 수 있어야 하며 모든 자료를 삭제하고 빈 화면으로 만드는 방식은 오프라인 재평가를 대체하지 않는다.

## C4 / P1 — 조회를 하지 않았거나 실패한 PARTIAL이 lastSuccessAt 갱신

위치: SourceSyncStateStore.recordResult와 ProbeRepository.ingestSourceLocked.

MISSING_API_KEY PARTIAL(items0, 실제 HTTP0)도 canCommitRecords=true여서 ingestor가 빈 transaction 후 committedAt을 준다. recordResult는 committed && canCommitRecords만으로 lastSuccessAt을 fetchedAt으로 갱신한다. 네트워크 실패로 부분 결과0건인 경우도 같다. 기록할 새 row가 없어도 검증된 정상0건은 성공일 수 있지만, 시도 자체가 없는 경우/전부 실패한 경우와 같지 않다.

기대: 응답 검증+저장 성공, 일부 실제 응답 성공, 미조회/실패를 분리해 성공 시각을 갱신한다. lastCompleteAt만 보수적으로 유지하는 것으로 lastSuccessAt의 거짓 갱신을 정당화할 수 없다. bumpGeneration 뒤 이전 학교의 성공 시각도 새 scope 완료처럼 보이지 않아야 한다.

## C5 / P1 — typed source 경로가 잘린 내용·충돌의 기존 행동 gate를 우회

위치: `NoticeDecision.kt:476-550`, SourceSyncCodecs.decodeRecordSourceMetadata, SourceRecordSelectors/DefaultRecordSourcePolicy.

decideSourceRecord는 metadata.contentState와 record.truncated를 검사하지 않고 sourceText가 있으면 buildAction한다. metadata가 PARTIAL/ATTACHMENT_MISSING이면 coordinator의 최종 gate는 저장을 막지만 화면/후보는 여전히 REQUIRED가 된다. 더 직접적으로 record.truncated=true인데 metadata.VERIFIED이면 coordinator도 검증 완료로 받아 자동 행동을 만들 수 있다.

typed audience와 실제 본문 대상이 충돌해도 UNKNOWN/issue로 보류하지 않는다. 현재 테스트 `sourceMetadataCanExcludeOtherGradesWithoutReadingBodyText`는 typed가 본문을 무조건 이기는 결과를 정답으로 삼아 설계 N6와 반대다. 빈 audience는 APPLIES, grade=null도 APPLIES branch에 들어갈 수 있으며 policy는 fact.applicability를 무시하고 범위만 본다. metadata decoder의 알 수 없는 contentState 기본값이 VERIFIED인 것도 실패 시 승격이다.

기대: adapter facts는 실행 명령이 아닌 검증 근거로 취급한다. unknown/잘림/충돌은 보류하고, decoder 실패를 VERIFIED로 만들지 않는다. 필수 행동은 applicability·의무·정확한 날짜·내용 완전성의 동일 gate를 통과해야 한다.

## C6 / P1 — 새 agenda가 hero를 차지해도 숨은 notice를 읽음 처리

위치: `BriefingActivity.kt:122-145,166-168`.

새 firstAction은 agenda를 우선하지만 visiblyRenderedNoticeRows는 noticeSummaries가 있으면 무조건 첫 공지1개다. 오늘 agenda A와 필수 공지 B가 함께 있으면 화면에는 A만 보이는데 effect가 B를 markSeen한다. 상세/TTS에서 추가 rows를 보수적으로 미열람 처리했던0.8 보호를 새 agenda 통합이 깨뜨린 것이다.

기대: 실제 hero로 표시한 record가 notice인 경우에만 그 notice를 읽음 처리한다. agenda/notice/task를 합친 실제 표시 projection과 건수를 하나로 만들고 동일 출처 중복도 제거한다.

## C7 / P2 — stored agenda의 날짜·대상·상태 표시가 소비자마다 다름

위치: SourceRecordSelectors.agenda, LocalAgentEngine.agendaAnswer, MainActivity HomeScreen 호출, ProbeScreens 일정 카드, SourceSyncStateStore/ConnectionsScreen.

- agenda는 한 record의 여러 EVENT 중 minOrNull 하나만 반환한다. 오늘/내일 행사가 같은 공지에 있으면 저녁 브리핑은 오늘 날짜를 고른 뒤 내일 필터에서 그 공지를 제외해 내일 일정을 놓친다.
- OPTIONAL 프로그램/미해석 metadata도 적용 flag+EVENT만 있으면 학교 agenda에 올라온다. 참여 선택 전 행사를 확정된 개인 일정처럼 우선 표시하지 않아야 한다.
- 홈은 sourceAgenda가0이면 기존 remember neisEvents로 fallback한다. 대화/브리핑에는 없는 미저장 자료가 홈에만 보일 수 있다. 일정 카드는 첫 항목/건수만 있고 요청된 저장 일정 전체 보기 action은 없다.
- SourceSyncStateStore에 observable 상태가 없고 MainActivity는 enqueue 직후 snapshots를 읽는다. 작업 완료 뒤 화면에 있는 sourceSnapshots를 갱신하는 연결이 없다. 홈/대화/브리핑도 마지막 성공/partial/auth/stale를 같은 snapshot으로 표현하지 않는다.

기대: 모든 EVENT 날짜를 보존한 단일 저장 projection, 실제 source status 관찰, 전체 일정 보기와 사실적인 빈 상태를 연결한다. 기존 메모리 NEIS 값을 별도 진실로 유지하지 않는다.

## C8 / P2 — revision·retention의 ingestion 경계 잔여

위치: `ProbeRepository.kt:327-360,372-378,414-435`.

sourceItemToRecord map은 batch 시작 시 한 번 만든다. 같은 batch 안에 동일 item의 서로 다른 revision 두 개가 있으면 둘 다 insert되고 둘 다 최신 후보로 남을 수 있다. changed row의 firstSeenAt도 이전 item의 값을 이어받지 않고 매 fetcher 값으로 다시 시작한다. item.sourceId/origin과 batch scope가 일치하는지도 record마다 확인하지 않는다.

기대: batch 내 identity/revision 충돌을 먼저 검증하고 한 identity의 일관된 최신 상태만 publish한다. 원문 firstSeen/lastFetched 의미를 분리해 재조회·개정으로 원문 보관기간이 무기한 연장되지 않게 한다. body의 source magic text와 무관한 typed metadata provenance도 item 단위로 검증한다.

## 확인한 적절한 출발점

- SourceSyncScheduler는 실제 WorkManager의6시간 periodic/unique one-time+NETWORK_CONNECTED를 등록하고 public fetcher registry는 Application에서 실제 구현을 연결한다. fake push로 listener에 재투입하지 않는다.
- 공개 ingest의 조건에는 NotificationListener 권한/selectedPackages/collectionEnabled가 직접 들어가지 않는다. 기존 Android capture는 원래 canCapture gate를 유지한다. 다만 public consentEpoch가 captureEpoch여서 listener disconnect에 불필요하게 무효화되는 결합은 분리할 필요가 있다.
- source item/revision ID는 별도 digest를 쓰며 body·metadata를 기존 ProbeCrypto로 암호화한다. 동일 revision 재수집은 recordExists로 건너뛰고, source item tombstone은 새 revision에도 적용된다.
- 기록 transaction이 성공한 receipt 뒤 checkpoint를 쓰는 순서는 존재하며 ERROR/AUTH에서 기존 success timestamp를 보존하는 기본 방향은 맞다. 위 PARTIAL0건과 source reset 권한 문제는 별도다.
- INFORMATIONAL EVENT를 agenda로 받는 새 경로와 날짜 없는 행사에 임의23:59를 만들지 않는 typed 날짜 모델은 있다. 실제 하나의 current source projection으로 묶는 작업은 아직 필요하다.

## 검증 한계와 필요한 근거

현재 확인한 테스트는 codec/selector/metadata pure tests 중심이다. cold worker→scope→실제 fake fetch→암호화 ingest→checkpoint→UI 상태, source disconnect와 ingest 경합, reset 뒤 늦은 state write, agenda+notice 혼재 표시를 검증하지 않는다. 새 테스트를 추가 중이라는 설명은 실행 증거가 아니다.

모든 항목은 이번 새0.9 경로의 caller를 따라 확인한 것이며 캘린더/알람0.8.1 전체를 다시 평가한 것이 아니다. HWP byte 연결·개인 e알리미 authenticated fetch·실기기 무푸시 결과는 이 소스 검토의 완료 증거로 대체할 수 없다.
