# 0.9 최신 통합 소스 검토 — 제한 최종 확인

2026-09-16 00:39:25 KST. 검토자 GPT-6 Astra High. **판정: 아래 남은 P1/P2 수정 필요.** 이전 CORE C1~C8/PUBLIC R1~R5/DOCUMENT DE1~DE4만 추적했고0.8.1 종결 기능이나 새 포맷으로 범위를 넓히지 않았다. 소스 읽기와 이 문서 작성만 수행했으며 Gradle/ADB/폰/악성 입력 실행은 하지 않았다.

상위 작업 제공 증거: compile 성공, PC unit suite 통과, 실제 Galaxy 문서3개/SourceIngestFlow9개 시험 통과, 알림 권한 없이 production 학교 worker가 공개6개 기록을 fetch/저장하고 PARTIAL 상태를 남김. 이는 중요한 실제 실행 증거이나 아래 다른 실패 interleaving·encoding edge case를 검증한 증거로 확장하지 않는다. 고정 게시물 ID의 현재 목록 이동 때문에 live assertion을 수정 중이라는 상태도 구분한다.

## 해결 확인

- CORE C1: production Worker→SourceSyncRunner가 source mutex 안에서 repository readiness를 제한 시간 기다린다. ConnectorRepository는 생성 시 동기 load다. source scope를 기다림 전에 고정하던 조기 종료는 해결됐다.
- CORE C3 일부: NEIS CONNECTED 검사, 실제 unique work cancel/reset 호출, source 자동 부탁 보류, 홈·대화·브리핑의 activeRecords 정책 적용, AUTH_REQUIRED→REAUTH_REQUIRED 전환을 확인했다.
- CORE C4: canCommitRecords는 빈 PARTIAL/FETCHED를 배제하므로 키 없음/네트워크 실패0건이 ingest receipt 성공을 얻어 lastSuccessAt을 갱신하던 재현은 해결됐다.
- CORE C5: record.truncated와 attachment 불완전성으로 contentState를 내리고 buildAction을 차단한다. typed/body 대상 충돌 보류와 공통 SourceAudienceEvaluator가 생겼다.
- CORE C7 일부/C8: 여러 EVENT 날짜를 flatMap으로 보존, snapshotsFlow 관찰, 홈의 memory NEIS fallback 제거, source별 identity/batch 중복 억제와 previous firstSeenAt 보존을 확인했다. `일정 보기`는 현재 inbox 진입이다.
- PUBLIC R2/R3/R5: COMPLETE 첨부+미독 첨부는 PARTIAL, NEIS0건 코드/row/count 모순과 누락 학교 code 검사, 각 학년 flag typed fact 보존을 확인했다. R4는 checkpoint generation/window 검사와 front page overlap, 현재학년도 pinned/미래 게시일 제외가 생겼다.
- DOCUMENT DE1/DE2/DE3: directory entry도 budget read, outer HWP section loop 중단과 remaining inflate budget, 초과 declared stream 크기 거절이 생겼다. v4 거절/CJK 보존/strict raw deflate/컨테이너 cycle 제한도 유지된다.

## F09-1 / P1 — UTF-8 사전검사와 SAX byte autodetection의 해석 불일치

위치: `document/HwpxTextExtractor.kt:parseXmlText`.

현재 UTF-8 decoder는 malformed bytes를 거절하지만 NUL은 유효하게 해석한다. BOM 없는 UTF-16LE의 ASCII XML(`<\0?\0x\0m\0l...`)은 UTF-8 decoder에서도 오류 없이 NUL 포함 문자열이 된다. DOCTYPE/ENTITY regex는 글자 사이 NUL 때문에 선언을 찾지 못한다. 그 문자열을 UTF-8 bytes로 다시 만들면 원래 UTF-16 byte pattern이 유지되고, SAX byte InputSource는 XML encoding autodetection으로 UTF-16 문서를 읽을 수 있다. Android에서 disallow-doctype flag가 지원되지 않는 조건은 이번 root 수정이 명시적으로 허용했으므로 내부 entity 선언에 대한 사전 차단을 우회할 수 있다.

기대: 검증한 문자열을 StringReader/character InputSource로 SAX에 전달하여 encoding 재감지를 없애거나, NUL/다른 encoding 선언을 명시적으로 거절해 검증과 파싱의 문자를 일치시킨다. UTF-8로 올바르게 검사된 declaration-free XML에서 best-effort flags를 defense-in-depth로 사용하는 취지는 타당하다. 정상 Galaxy HWPX 및 UTF-8 DTD test3개 통과는 이 encoding mismatch를 다루지 않는다.

## F09-2 / P1 — briefing hero와 읽음 처리 범위가 여전히 불일치

위치: `reminder/BriefingActivity.kt:115-127,146,168-170`.

visibleNoticeRows를 전체3행 잔여 budget으로 잘랐지만 visiblyRenderedNoticeRows는 그 목록 전체다. AlarmContent는 firstAction 한 행만 hero에 그린다. agenda가0개이고 notice가3개이면 숨은2개까지 읽음 처리한다. agenda가1개와 notice가1개이면 hero는 agenda지만 notice도 읽음 처리한다.

기대: 실제 hero가 notice인 경우에만 그 한 record를 markSeen한다. 상세/음성의 미표시 추가 공지를 보수적으로 유지하기로 한 기존 계약을 유지한다. agenda budget 계산은 읽음 범위의 증거가 아니다.

## F09-3 / P1 — source markRunning이 reset 뒤 상태를 다시 만들 수 있음

위치: `sync/SourceSyncRunner.run`, `SourceSyncStateStore.markRunning/requireSnapshotMatchesScope`.

recordResult는 missing state를 거절해 기존 늦은 결과 경로가 개선됐다. 그러나 runner가 valid scope를 만든 뒤 markRunning 호출 전에 대기하는 동안 reset이 source state를 clear하면, markRunning은 allowMissing=true로 예전 scope를 받아 새 encrypted state를 만든다. 같은 window에서 새 fetch도 시작될 수 있다. markRunning의 lock 내부 검사는 저장소 snapshot만 보고 현재 consent/활성 source를 보지 않는다. cancelWork는 cooperative cancellation이며 이 synchronous commit 전에 scope check를 원자화하지 않는다.

기대: 시작 상태 commit도 현재 source/authorization을 실제 commit lock 안에서 검증하고 reset과 공유하는 경계를 사용한다. reset 이전에 commit하면 reset이 제거하고, reset 이후라면 old scope를 거절해야 한다. 최신 late-result tests가 이 pre-markRunning gap까지 다루는지 별도 확인한다.

## F09-4 / P1 — oversized 첨부 예외에서 수신 byte가 누락돼 run budget 우회

위치: `connector/SchoolWebsiteClient.kt:enrichAttachmentText`, `UrlConnectionSchoolWebsiteHttp.getBytes`.

정상 응답은 remaining bytes를 전달한다. 그러나 production getBytes는 limit+1까지 실제 읽고 초과 시 예외를 던진다. enrichment catch는 bytesRead를 더하지 않고 다음 첨부로 continue한다. 따라서1MiB를 조금 넘는 첨부10개를 연속 받으면 각1MiB 이상을 읽고도 남은5MiB budget이 전혀 줄지 않는다. 중간 IO 실패에서도 이미 받은 bytes가 같은 방식으로 빠진다.

기대: HTTP 결과/예외가 실제 수신 바이트 수를 전달하거나, 상한 초과/불명 읽기 실패에서는 안전하게 해당 run 첨부 loop를 중단한다. 정상첨부 budget 개선과 실패 분기의 global accounting을 함께 지켜야 한다.

## F09-5 / P2 — 학년만 바뀌어도 typed snapshot 전체 삭제

위치: `MainActivity.kt:child 저장 changedScope 분기`.

학년만2→6으로 바뀌어도 cancelAll/bumpGeneration/clearSourceRecords를 모든 source에 적용한다. 공개 자료의 typed all-grade facts가 이제 저장돼 있어도 오프라인에서는 전부 사라져 현재 학년으로 재평가할 수 없다. 이는 원래 C3의 학년만 변경 시 기존 사실 재평가 요구가 남은 부분이다.

기대: 학교/인증 출처 변경과 자녀 학년 변경을 구분하고 공개 canonical school snapshot은 새 child로 공통 evaluator가 다시 판단하게 한다. 개인 source의 실제 자녀 scope 변경은 별도 연결 generation을 유지한다.

## F09-6 / P2 — 원래 agenda/완전성 표시 잔여

- SourceRecordSelectors는 OPTIONAL_OPPORTUNITY라도 VERIFIED이면 학교 agenda에 포함한다. interest 전 프로그램이 `곧 챙길 일정` hero로 올라오므로 선택 기회와 확정된 학교 행사를 분리해야 한다. 실제 원문 agenda 근거 자체를 지우라는 뜻은 아니다.
- 홈/대화/브리핑에는 실제 snapshot.lastSuccess/partial/auth/stale가 전달되지 않고 연결 화면에만 status가 있다. worker 일부성공/오류를 엄마가 보는 브리핑에서 숨기지 않는 원래 C7 계약은 아직 남는다.
- HWPX namespace 검증이 exact 허용 URI가 아니라 `contains("hancom") && contains("section"/"paragraph")`라 임의 namespace `urn:fake-hancom-section`도 정상본문으로 인정한다. 원래 DE4의 검증된 root/namespace 계약에는 정확한 허용 URI가 필요하다. 추가 포맷 지원 요구가 아니다.

## 결과 보고 경계

실제 public worker의6개 저장 및 PARTIAL은 공개 lane 진행의 근거로 보고할 수 있다. 일부 첨부/그림/OCR 미확인은 그대로 표시해야 하며, 문서 extractor 수정이 모든 HWP/HWPX 의미·표·이미지 이해의 완료를 뜻하지 않는다. 개인 e알리미 authenticated DOM·학교/자녀 대조·실제 공지 수집은 아직 미완료다. 이는 공개 lane의 진실한 부분 결과를 무효화하지 않지만 개인 경로까지 성공했다고 묶어 보고할 수 없다. OCR 의존성은 승인/추가되지 않았다.

---

## F09 제한 종결 확인 — 2026-09-16 00:51:25 KST

**판정: 추적한 P1 F09-1~4와 F09-5 CLOSED. 공개 수집·문서 처리의 이번 안전 수정 소스는 통과. F09-6 중 출처 상태 안내의 소비자 범위는 P2 잔여로 구분한다.** 전체 AI/개인 인증 경로 완료 판정이 아니다.

- F09-1 CLOSED: UTF-8 오류 및 NUL을 거절하고 DTD/entity 선언을 검사한 **동일 문자열**을 StringReader로 SAX에 넘긴다. byte encoding 재감지 경로가 제거됐으며 보안 flag 미지원은 이 사전 차단 뒤의 추가 방어에 한정된다. 정확한 Hancom section/paragraph URI allowlist도 적용됐다.
- F09-2 CLOSED: production visiblyRenderedNoticeRows는 initiallyVisibleBriefingNoticeCount를 사용한다. agenda가 없고 실제 hero가 notice일 때만1개, agenda hero면0개를 markSeen한다. 숨은 추가 공지를 읽음 처리하지 않는다.
- F09-3 CLOSED, 지적한 시작 commit 경합: markRunning의 synchronized monitor 안에서 production runner가 넘긴 fresh isCurrentScope callback을 검사한다. reset도 같은 state store monitor를 사용한다. 시작 직후와 fetch/ingest 후 scope 재검사·matching stale state discard가 추가됐다. test helper만 아니라 실제 runner→store 호출을 읽었다.
- F09-4 CLOSED: attachment HTTP 예외에서 이미 읽은 길이를 모르면 그 요청에 허용했던 전체 maxAttachmentBytes를 차감한다. 반복 oversized/부분 read 실패도 다음 첨부의 남은 budget을 감소시켜 이전 무제한 반복 경로를 막는다.
- F09-5 CLOSED: grade/level-only 변경은 작업 취소·자동 부탁 보류 후 공개 typed snapshot을 보존한다. source generation bump와 records 삭제는 학교 변경에만 적용한다. 기존 공통 audience evaluator가 현재 자녀 범위로 재평가한다.
- F09-6 일부 CLOSED: OPTIONAL_OPPORTUNITY는 학교 agenda에서 제외하고 실제 학교 행사를 선택 기회와 구분한다. HWPX substring namespace 허용을 제거했다. Home은 snapshotsFlow의 PARTIAL/ERROR/OFFLINE 안내를 카드에 표시한다.

### 남은 P2 — 상태 안내가 여전히 일부 경로에만 있음

Home의 sourceStatusMessage는 activeSourceScopes의 snapshot만 읽는다. AUTH_REQUIRED 응답 뒤 runner가 connector를 REAUTH_REQUIRED로 바꾸면 그 source는 activeScopes에서 빠져, 실제 재로그인 필요 상태가 Home 안내에서 사라질 수 있다. 재조회 가능한 활성 출처와 사용자에게 오류를 알릴 연결 출처를 같은 집합으로 쓰지 않아야 한다.

또 이번 변경에는 대화·브리핑에 source snapshot 상태/마지막 성공을 전달하는 경로가 없어, 앞서 F09-6에서 지적한 두 소비자의 stale/partial 표시 범위는 아직 남는다. 공개 worker가 실제 자료를 저장했다는 사실을 부정하는 blocker로 확대하지 않으며, 현재 공개 lane 부분 결과는 정확히 보고할 수 있다. 개인 e알리미 인증 DOM 미확보는 별도 미완료 상태이고 성공이라고 표시해서는 안 된다.

검토자는 최신 코드를 읽었으며 새 Gradle/ADB/기기 시험은 실행하지 않았다. 이전 PC suite 통과, 실제 source14/doc3/ingest10 및 production public6건 저장은 상위 작업 제공 실행 증거로 기록한다. 새 변경의 최종 PC suite 및 WorkManager 자체 enqueue→worker 실제 실행 시험은 빌드·기기 담당자가 별도 확인해야 한다. 직접 SourceSyncRunner 시험을 WorkManager 주기 실행 증거로 바꾸어 말하지 않는다. OCR 의존성은 추가되지 않았다.

---

## 0.9 공개 경로 최종 소스 종결 — 2026-09-16 01:09:53 KST

**최종 판정: 이번 검토에서 추적한 F09-1~6의 공개 수집/문서 처리/상태 표시 수정 소스 통과. 남겨둔 P1/P2 검토 항목 모두 CLOSED.** 이는 개인 e알리미까지 완료했다는 판정이 아니며 아래 증거 범위를 유지한다.

마지막 상태 표시를 실제 caller에서 확인했다. SourceStatusPresentation은 활성 scope 목록에 국한하지 않고 전달받은 전체 persisted snapshot의 AUTH_REQUIRED/OFFLINE/ERROR/PARTIAL을 판정한다. MainActivity와 AgentActivity, BriefingActivity는 snapshotsFlow를 관찰하고 동일 presenter에 전체 values를 넘긴다. 따라서 인증 만료로 source가 activeScopes에서 빠져도 재로그인 안내가 사라지지 않는다.

Home은 해당 메시지를 카드에 표시한다. Briefing은 할 일 없는 요약에서 상태를 표시하고, 항목 유무와 관계없이 상세 목록에 수집 상태를 추가한다. 항목이 있는 접힌 요약에서는 행동 건수가 우선되므로 상세의 상태 확인과 구분한다. LocalAgentEngine은 recent/agenda/action/capability 조회 답변에 실제 context 상태 문구를 덧붙인다. 모든 조회가 성공했다는 허위 메시지로 바꾸는 경로는 없다.

앞선 수정의 제한 재확인도 유지했다: NUL 거절+동일 StringReader XML 파싱·정확 namespace, 실제 notice hero 한 건만 열람 처리, lock 내부 fresh scope markRunning, 첨부 실패 allowance 차감, 학년만 변경 시 typed 공개 snapshot 유지, 선택 기회 agenda 제외. 새 상태 표시 때문에 이 보호 경계가 되돌아간 변경은 발견하지 않았다.

### 최종 증거 범위

검토자가 기존 JUnit XML을 읽어 **205 tests / 0 failures / 0 errors / 2 skipped**를 확인했다. 테스트를 새로 실행하지 않았다. `BUILD SUCCESSFUL`(testDebugUnitTest/debugAndroidTest/debug/release/lint), Galaxy14/14 source suite 및 실제 production WorkManager 학교웹 수집·무알림권한 저장 성공은 상위 작업이 실행·제공한 증거다. 직접 runner만 호출한 예전 시험과 새 WorkManager 실행 증거를 구분한다. 실제 HWP/HWPX 부분 해석과 ingest/reset/idempotency 통과도 해당 시험 범위의 근거이며 모든 학교 문서/기기/형식의 완전성을 보장하지 않는다.

개인 e알리미의 authenticated DOM·학교/자녀 일치·개인 공지 수집은 사용자 로그인/검증이 아직 필요하여 **명시적으로 미완료**다. 공개6건 저장/부분 첨부 해석을 개인 source 성공으로 확대하지 않는다. OCR 의존성은 승인·추가되지 않았고, 읽지 못한 그림/첨부는 PARTIAL/미확인 상태를 유지한다. 전체 AI 비서 완성이 아니라 현재 검증된 공개 무푸시 수집·저장·선별·표시와 문서 제한 처리의 완료로 보고한다.
