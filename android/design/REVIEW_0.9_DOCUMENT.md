# 0.9 Document E — HWP5/HWPX 제한 보안·정확성 검토

검토자: GPT-6 Astra High. 읽기 시각: 2026-09-15 23:20:34 KST. 판정: **수정 필요 — 현재 extractor를 untrusted 첨부 자동 처리에 승인하지 않음**.

범위는 document/의 네 source와 DocumentTextExtractorTest다. 실제 공개 fixture `parent-class-notice-1951993.hwp`가265,216바이트로 존재하는 것은 확인했지만 extractor/Gradle/ADB/기기를 실행하지 않았다. 다운로드 B 통합과 이미지/OCR SDK는 범위 밖이다.

## E1 / P1 — DIFAT 순환·중복이 입력 상한을 훨씬 넘는 allocation으로 증폭

위치: `CompoundOleReader.kt:47-61,108-134,210-234`.

DIFAT loop에는 visited/cycle 검사·FAT sector ID uniqueness·실제 파일 sector 수에 맞는 count 검증이 없다. 작은 파일의 DIFAT sector가 자기 자신을 가리키고 같은 유효 FAT sector ID를 반복하면 최대16,384회 동안 같은127개 ID를 추가할 수 있다. 공격자 numberOfFatSectors를 크게 주면 약200만개의 중복 FAT sector를 읽으려 하며 `ArrayList<Byte>(fatSectorIds.size * sectorSize)`가 약1GiB 원소 용량(참조 배열은 더 큼)을 요청한다. 5MiB input limit은 이 OOM 경로를 막지 않는다. DIFAT offset 계산도 Int 곱셈이라 큰 sector ID가 wrap될 수 있다.

readStandardChain의 expectedSectors는 초기 capacity에만 쓰이고 실제 loop의 바이트/선언 크기 제한으로 쓰이지 않는다. directory sibling traversal은 비정상 깊이에 대한 명시 제한 없이 재귀여서 긴 단방향 tree가 stack을 소진할 수 있다.

기대: 파일 크기로부터 가능한 sector/FAT/DIFAT count를 먼저 제한하고 중복/순환/잘못된 terminator를 거절한다. allocation 곱셈은 Long checked arithmetic으로 검증하고 실제 출력 byte budget을 loop에서 지킨다. directory 순회는 bounded iterative traversal 또는 엄격한 depth 한계를 사용한다. Error를 나중에 catch하는 것만으로 OOM을 해결하지 않는다.

## E2 / P1 — ZIP entry cap 뒤 closeEntry가 나머지 압축 데이터를 끝까지 해제

위치: `HwpxTextExtractor.kt:15-38,80-94`; `DocumentTextLimits`.

XML entry를2MiB에서 잘라 readCapped가 반환해도 바로 `zip.closeEntry()`를 호출한다. ZipInputStream.closeEntry는 읽지 않은 entry 나머지를 읽어 끝까지 진행하므로 매우 크게 팽창하는 ZIP에서는 cap 이후에도 해제가 계속된다. BinData/미지원 entry는 처음부터 아무 제한 없이 closeEntry로 drain한다. 전체 해제4MiB 한계도 HWPX에서는 사용하지 않는다.

기대: 모든 entry(비텍스트 포함)의 실제 해제 바이트를 전역 budget으로 계산한다. cap에 도달하면 해당 ZipInputStream 전체 처리를 종료해야 하며 closeEntry로 잔여를 무제한 해제하지 않는다. entry count256만으로 ZIP bomb을 차단했다고 보지 않는다. ZipException/CRC/잘린 ZIP은 구조화한 실패/부분 결과로 반환해야 한다.

## E3 / P1 — 잘림·손상·출력 초과를 COMPLETE로 보고

위치: `Hwp5TextExtractor.kt:96-125,151-176`; `CompoundOleReader.kt:47-48,118-134,147-149`; `HwpxTextExtractor.kt:99-118`; `DocumentTextExtractor.complete`.

- raw deflate가 finished=false인데 needsInput/needsDictionary이면 break 후 성공 bytes를 반환한다. 압축 tail을 자른 파일이 앞의 정상 paragraph를 갖고 있으면 손상 표시 없이 COMPLETE가 될 수 있다.
- CFB chain이 allocator 범위 밖/잘못된 음수 sentinel로 끝나도 조용히 짧은 bytes를 돌려준다. directory cycle/out-of-range도 일부 항목을 말없이 건너뛴다. 선언 stream 크기2MiB cap 역시 issue 없이 trim한다.
- HWP record의 불완전 extended header/끝의1~3bytes는 issue 없이 loop를 끝낸다. `offset+size`는 Int overflow를 허용해 범위 검사를 우회한 뒤 String 생성에서 예외가 날 수 있다.
- HWPX TextHandler는100,000자에 도달하면 조용히 추가 characters를 무시하며 CONTENT_LIMIT_EXCEEDED issue를 만들지 않는다. 잘린 text를 COMPLETE로 반환한다.
- text/decompression 한계는 HWP section 또는 XML entry마다 초기화된다. 여러 section/entry의 합계가100,000자를 크게 넘을 수 있다. HWP는 초과 paragraph 전체를 먼저 추가한 뒤 cap을 검사해 한 paragraph만으로도 출력 상한을 넘는다.

기대: 컨테이너·압축·record가 정상 종료됐을 때만 complete. global decompression/text budget을 공유하고 모든 잘림·불완전 chain을 PARTIAL/UNSUPPORTED issue로 남긴다. 범위는 `size <= bytes.size-offset` 같은 checked 방식으로 검증한다. 자동 판단의 신뢰도를 위해 partial을 숨기면 안 된다.

## E4 / P1 — XML 보안 설정 실패를 무시하여 Android 파서에서 DTD 차단을 보장하지 못함

위치: `HwpxTextExtractor.kt:55-78`.

factory.setFeature를 모두 runCatching으로 무시한다. Android의 SAX 구현이 disallow-doctype-decl 등 기능을 지원하지 않으면 DOCTYPE/내부 entity 확장 방어가 실제 적용되지 않은 채 parse한다. entityResolver의 빈 응답은 외부 entity 접근에는 방어가 되지만 내부 expansion을 막지 않는다. TextHandler가100k 이후 return해도 파서 자체의 확장 작업은 계속된다.

기대: 필수 보안 설정이 적용되지 않으면 fail closed하거나 DOCTYPE를 인코딩까지 고려해 확실하게 금지한 좁은 XML reader를 사용한다. parser/문서 깊이·전체 character work에도 한계를 둔다. 현재 JVM test의 file:///etc/passwd/root: 검사와 XML_PARSE_FAILED는 실제 Android SAX feature 지원의 증거가 아니다. 실제 파일/네트워크 외부 entity를 읽는 시험은 검토자가 수행하지 않았다.

## E5 / P2 — CFB v4를 허용하면서 v3 offset/size 규칙으로 읽음

위치: `CompoundOleReader.kt:85,136-139,191-192,211,224`.

majorVersion4/4096-byte sector를 허용하지만 sector offset은512+sid*sectorSize로 계산한다. v4에서 sector0의 시작은4096-byte header sector 뒤이며 이 계산은 다른 위치를 읽는다. directory stream size도 하위32비트만 읽는다.

기대: v4의 header/sector offset·64비트 size를 정확히 검증하거나 현재 범위를v3로 명시하고 UNSUPPORTED_VERSION으로 거절한다. 잘못된 v4 bytes에서 우연히 추출된 텍스트를 COMPLETE로 승격하지 않는다.

## E6 / P2 — 문자·XML 구조를 실제 본문으로 해석하는 규칙이 너무 넓음

위치: `Hwp5TextExtractor.kt:128-150`; `HwpxTextExtractor.kt:32,49-53,99-129`; DocumentTextExtractor.detectFormat.

HWP의 isPackedAsciiControlPayload는 CJK 문자의 두 byte가 ASCII printable이면 삭제한다. 유효한 `學`(U+5B78), `校`(U+6821)도 이 조건에 맞으므로 일반 본문 문자까지 사라진다. control record의 정해진 길이/구조를 읽지 않고 문자값 heuristic으로 없애는 것은 안전한 텍스트 추출이 아니다.

HWPX는 모든 ZIP magic을 후보로 받고 Contents/ 아래 모든 XML 및 settings.xml의 모든 characters를 본문으로 모은다. section/namespace/manifest를 확인하지 않아 임의 ZIP의 Contents/fake.xml이나 설정 숫자도 문서 본문으로 COMPLETE 처리할 수 있다. ZIP 물리 순서와 실제 section 순서도 구분하지 않는다.

기대: HWP control payload는 구조/길이에 따라 건너뛰고 일반 Unicode를 보존한다. HWPX는 검증한 본문 section/root/namespace의 텍스트만 추출하고 문서 순서를 유지한다. 미지원 구조·이미지·식·도형 정보는 명시적인 부분 상태다.

## 확인한 긍정 경계와 미검증 사항

- public API의 입력5MiB guard, HWP FileHeader5.x 검증, password/distribution flags의 UNSUPPORTED 반환은 있다. 보호를 우회하는 구현은 보지 않았다.
- BinData가 있으면 EMBEDDED_BINARY_SKIPPED와 PARTIAL로 내려가는 경로가 있다. 이를 OCR/그림 의미 이해 완료라고 하지 않는다.
- 일반 FAT/miniFAT stream chain에는 visited 집합이 있어 그 특정 chain cycle은 예외 처리된다. E1은 DIFAT/tree와 allocation의 다른 경로다.
- HWP raw deflate는 nowrap Inflater와4MiB per-stream 제한을 사용한다. E2/E3의 전체 budget/정상 종료 문제와 구분한다.
- XML 외부 entityResolver는 빈 source를 반환한다. E4의 Android feature 실패/내부 expansion 문제를 해결했다는 뜻은 아니다.
- 실제 공개1951993 fixture를 대상으로 한 테스트가 있으나 파일이 없으면 assumeTrue로 건너뛴다. 이번 workspace에는 파일이 있지만 검토자는 시험을 실행하지 않았다. 테스트의 기대값은 PARTIAL+본문 일부+embedded binary 미해석이며 전체 내용/OCR 성공이 아니다.

필요한 회귀는 DIFAT self-cycle/duplicate FAT/count overflow, miniFAT out-of-range/short chain, 깊은 directory, truncated raw deflate, oversized/global multi-section text, skipped-entry ZIP bomb, corrupt CRC, unsupported SAX security feature, valid Unicode 보존이다. 새 라이브러리나 이미지 SDK 도입 없이 현재 제한 parser를 안전하게 좁혀야 한다. B의 실제 download/ingest 연결은 별도 freeze 이후 검토해야 한다.

---

# Document E 2차 제한 검토 — 2026-09-15 23:33:10 KST

판정: **E1/E4/E5 CLOSED, E2/E3/E6 부분 해결 후 잔여 수정 필요**. 새 포맷/다운로드/이미지 SDK로 범위를 넓히지 않았다. 생산 네 클래스를 읽었으며 Gradle/ADB/실기기/악성 파일 실행은 하지 않았다.

| 항목 | 상태 | 확인한 변경 |
|---|---|---|
| E1 CFB 메모리 증폭 | CLOSED, 최초 재현 | 실제 파일 sector 수로 FAT/DIFAT count를 제한하고 DIFAT visited/chain 길이/종결·FAT duplicate 검사, directory depth128/cycle 검사가 생겼다. ByteArrayOutputStream으로 바뀌고 chain expectedSectors를 실제 loop에서 지킨다. |
| E2 ZIP bomb | OPEN | 비텍스트 파일도 global ZipInflateBudget으로 읽고 명시 closeEntry는 제거됐다. directory entry만 budget read를 건너뛰어 내부 drain 경로가 남는다. |
| E3 손상/잘림/전체 상한 | OPEN, 부분 해결 | raw Inflater finished·dictionary/no-progress/잔여 bytes 확인, overflow 없는 record bounds, shared text budget과 issue가 생겼다. HWP 전체 inflate 중단 및 선언 크기 clamp가 남는다. |
| E4 XML 보안 fail-open | CLOSED, 보안 방향 | DOCTYPE/ENTITY 사전 거절과 필수 SAX feature 적용 실패 시 parse 중단, 외부 entityResolver 유지. 실제 Android SAX가 해당 feature를 지원해 정상 HWPX가 읽히는지는 예정된 기기 QA 증거가 필요하다. |
| E5 CFB v4 | CLOSED | 잘못된4096 offset 지원 대신 명시 UNSUPPORTED_VERSION으로 거절한다. |
| E6 일반 문자/본문 범위 | OPEN, 부분 해결 | 學/校를 삭제하던 heuristic은 없어졌고 일반 CJK를 보존한다. bare ZIP magic만으로 HWPX라 하지 않으며 파일 경로도 좁혔다. XML root/namespace 검증은 아직 없다. |

## DE1 / P1 — directory entry의 nextEntry가 압축 payload를 무제한 drain

`HwpxTextExtractor.kt:31-45`에서 entry.isDirectory이면 readEntryCapped를 건너뛰고 다음 zip.nextEntry로 이동한다. ZipInputStream의 다음 entry 이동은 읽지 않은 현재 entry를 내부적으로 끝까지 소비한다. 이름이 `foo/`이지만 큰 압축 payload가 있는 ZIP entry를 넣으면 global ZipInflateBudget을 전혀 거치지 않는 이전 E2 경로가 남는다.

기대: directory 포함 모든 entry의 실제 bytes를 budget으로 읽거나 directory가 실제 빈 entry인지 안전하게 검증한 뒤 진행한다. cap에서 ZIP 전체를 닫는 기존 개선은 유지한다.

## DE2 / P1 — HWP global decompression cap 뒤에도 다음 section을 계속 해제

`Hwp5TextExtractor.kt:66-102`는 decompressedBytes>4MiB에서 return@forEach를 한다. 이는 전체 forEach 중단이 아니라 현재 section을 건너뛰는 것이므로 다음 section에서 다시 최대4MiB inflate를 수행한다. 텍스트가 적거나 없는 많은 section에서는 textBudget.exceeded가 true가 되지 않아 이후 section을 계속 처리한다.

기대: 전체 해제 budget을 넘으면 outer traversal을 끝내며 각 inflate에 remaining budget을 전달한다. content/section 공유 FAT를 통해 같은 압축 body를 여러 디렉터리에서 참조하더라도 총 작업량이 제한돼야 한다. 문자열 budget도 paragraphs.joinToString의 삽입 개행을 포함한 최종 output 길이로 제한해야 한다(50,000자 paragraph 두 개는 현재 joined text가100,001자다).

## DE3 / P2 — 선언 stream 크기 초과를 조용히 clamp

`CompoundOleReader.sectorsFor`와 trimToDeclaredSize는 여전히 size.coerceAtMost(MAX_STREAM_BYTES)를 사용한다. 선언이3MiB인데 실제 chain이2MiB이면 expectedSectors도2MiB 기준이므로 짧은 선언을 발견하지 못하고 앞쪽 정상 record를 COMPLETE로 읽을 수 있다. FileHeader/section의 입력 선언이 상한을 넘으면 명시 limit issue/unsupported로 처리해야 한다. chain 길이/종결 검증의 추가 자체는 올바르다.

## DE4 / P2 — HWPX로 이름 붙인 임의 XML의 <t>도 본문 COMPLETE

경로는 Contents/(section|header|footer)N.xml로 좁혔지만 TextHandler.startElement는 localName==t만 검사하고 root와 namespace를 확인하지 않는다. `notice.hwpx`의 `Contents/section0.xml`이 `<fake><t>준비물 ...</t></fake>`여도 본문으로 받아 COMPLETE가 될 수 있다. 원래 E6의 검증한 HWPX 본문 구조 계약이 아직 충족되지 않는다.

기대: 이번에 지원한다고 선언한 HWPX section/root/namespace를 검증하고 그 구조의 본문 텍스트만 읽는다. 새로운 포맷 지원이나 일반 XML reader를 요구하는 것이 아니다. HWP control 구조도 현재 처리하지 못하는 자료는 full semantic 이해로 확대하지 않는다.

새 테스트는 duplicate FAT·short miniFAT·v4 거절·truncated deflate·text cap·비텍스트 ZIP budget 등의 방향을 포함한다. 현재 directory-payload ZIP, 전체 HWP budget 이후 다음 section 중단, 초과 선언 크기, fake section namespace를 검증한 실행 증거는 없다. 실제 공개1951993 부분 텍스트/그림 미해석 판정과 Android SAX 호환은 각 담당자 실행 결과를 별도로 따른다. 이 문서는 Document E 알고리즘 전체 또는 B의 전체 첨부 조회 완료 승인이 아니다.
