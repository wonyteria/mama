# 1.1 안정성 후보 검증 기록

feature/1.1-reliability-rebuild · versionCode 14 / versionName 1.1.0 · `origin/main`(1.0.2) 위에 DESIGN.md의 1.1 신뢰성 항목만 재적용한 후보다. 아래는 자동 검증·에뮬레이터·실기기·사용자 조사를 구분한 현재 상태다. 실행하지 않은 항목은 NOT_RUN으로 남긴다.

## 자동 검증 (PC, 최신 실행)

`cd android && ./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`

- 단위 테스트 XML 합계(testsuite 속성): tests=270, failures=0, errors=0, skipped=2 — 268 통과 + 2 skip. skip은 외부 공개 fixture opt-in 테스트다. Robolectric 화면 테스트는 프로덕션 Composable을 실제 렌더한다.
- `AccessibilityLayoutTest` 16/16 통과, `ProbeScreensRenderTest` 19/19 통과.
- lint 오류 0. debug APK·androidTest APK 조립 성공.
- release 조립은 서명 정보 없이 실행하면 지정된 fail-closed 메시지로 실패한다.
  - `Release signing is not configured. Set MAMA_RELEASE_STORE_FILE, ...`
  - `Debug signing is never used for release builds.`
- release `BuildConfig.java`에 `NEIS_API_KEY` 필드가 없다(grep 0건). APK에 운영 비밀 키를 싣지 않는다.

## 접근성·소형 화면 회귀 (자동, 신규)

`AccessibilityLayoutTest` 16개: 360dp 너비·글꼴 200%·가로 방향에서 온보딩(시작/자녀/앱 선택/알림 허용), 오늘, 할 일, 연결, 근거·수정내역 대화상자, 알람의 핵심 조작이 스크롤로 도달 가능하고 표시되는지 확인한다. 모든 클릭 가능 노드는 TalkBack 이름(역할·문구·설명·상태), 최소 48dp 터치영역, 시각 순서와 일치하는 traversal 순서를 검사한다.

이 회귀에서 실제 결함을 찾아 최소 변경으로 고쳤다.

- 할 일 완료 Checkbox의 터치영역이 24x24dp였다 → 48dp로 확장.
- 동의 행(ConsentRow)이 34dp였다 → 48dp로 확장.
- 본문 수준의 `TextButton`/`OutlinedButton`이 40dp였다 → 공통 `Modifier.minTouchTarget()`으로 48dp 보장.
- 하단 탭이 선택 상태를 TalkBack에 알리지 않았다 → `selectable`의 `Selected` 의미 부여.
- 읽지 않은 소식이 색 점으로만 표시돼 TalkBack이 읽지 못했다 → 카드에 `읽지 않은 소식` stateDescription 추가.

## 에뮬레이터 계측 (실행됨)

`./gradlew :app:connectedDebugAndroidTest` · `mama_qa_api35` AVD · Android 15(API 35) ARM 이미지.

- JUnit XML(testsuite 속성, 권위) 기준: tests=18, failures=0, errors=0, skipped=2 — 즉 16 통과 + 2 skip.
- Gradle/UTP 콘솔은 같은 실행에서 `Finished 20 tests`라고 표시한다. XML testsuite 합계(testcase 18건)와 콘솔 표시(20)는 일관되게 `tests + skipped`만큼 어긋나며, 이는 UTP가 skip을 테스트 케이스와 별도 이벤트로 중복 집계하는 표시 문제다. 완료 수를 과장하지 않기 위해 이 문서와 CI 요약은 항상 XML 속성을 사용한다(18 total / 16 pass / 2 skip).
- skip 2개는 외부 HWP/HWPX 공개 fixture를 `externalFilesDir/qa-input/`에 넣어야 하는 opt-in QA 테스트다. fixture 파일은 저장소에 없으므로 의도된 `assumeTrue` skip이다.
- NEIS 운영 동기화 비활성(`PRODUCTION_SYNC_ENABLED=false`) 검증과 온보딩 드라이버, 알람/스누즈/수집 흐름을 포함한다.

CI의 `instrumentation` 작업은 GitHub 호스팅 API 35 x86_64 에뮬레이터에서 같은 `connectedDebugAndroidTest`를 실행하고(KVM 가속, AVD 스냅샷 캐시, 부팅·작업 타임아웃), 실행 후 `androidTest-results/connected/*.xml`의 testsuite 속성을 합산해 tests/passed/failures/errors/skipped를 GitHub Step Summary에 기록한다. failures 또는 errors가 1 이상이면 작업이 실패하고, 실패 시 XML·HTML 보고서를 업로드한다. 콘솔 총계는 신뢰하지 않는다.

## 실기기 계측 (실행됨 · SM-S926N)

`ANDROID_SERIAL=R3CX40BMCYV ./gradlew :app:connectedDebugAndroidTest` · Samsung SM-S926N, Android 16 / API 36, QA 빌드 1.1.0-qa(versionCode 14).

- JUnit XML(권위) 기준: tests=21, failures=0, errors=0, skipped=2 — 즉 19 통과 + 2 skip. 콘솔은 `Finished 23 tests`로 표시됐다(위와 동일한 UTP 집계 문제; 21+2=23).
- 통과에는 OS 경유 E2E가 포함된다: `cmd notification post`로 com.android.shell 패키지의 합성 알림을 실제 게시 → 알림 청취자 콜백 → 암호화 레코드 저장 → 후보 분석 → 자동 할 일 생성까지 확인했다(`postedSyntheticNotificationIsCapturedThroughSystemListenerIntoTaskStore`). 또 다른 테스트는 `ProbeRepository.capture`에 합성 StatusBarNotification을 직접 주입해 동일 저장소·플래너 경로를 검증한다. QA/debug 패키지와 합성 알림만 사용하며 사용자 알림이나 정식 앱 데이터는 읽지 않는다.
- `syntheticHwp5AssetReportsEmbeddedBinaryPartialOnDevice`가 저장소 체크인 합성 HWP5 fixture(BinData 포함, sha256 검증)로 내장 바이너리 건너뜀 보고를 기기에서 확인한다 — 공개 fixture 없이도 HWP5 부분 추출 계약을 기기에서 검증.
- skip 2개는 에뮬레이터와 동일한 opt-in 공개 fixture 테스트다.
- QA 앱 cold launch 후 프로세스 유지·즉시 crash/ANR 없음을 확인했다.

남은 실기기 항목(여전히 NOT_RUN):

- 실제 학교·학원 앱 알림 수집과 lane별 상태 정확성 — 실제 부모 기기 필요.
- 알림 접근 권한 회수·재부여 흐름.
- 재부팅·프로세스 종료 후 할 일·스누즈(연속 4회 이상)·읽음 상태 유지.
- 잠금화면 개인정보 보호(`FLAG_SECURE`)와 원본 알림 숨김(기본 OFF, 경계 유지).
- 완료·미루기·알람 울림의 실기기 동작.
- 24시간 이상 사용 중 crash/ANR 감시.

## 1.1 출시 체크리스트

스토어 제출 전 수동·외부 절차는 `android/design/RELEASE_CHECKLIST_1.1.md`에 항목별로 분리했다(서명 검증, versionCode/versionName, 권한, Data Safety, 개인정보처리방침, 데이터 보관·삭제, 백업, 롤백, 알림 접근 고지, 제출 전 수동 점검). 각 항목은 실행 증거 없이 체크하지 않는다.

## signed release (NOT_RUN)

- `signed-release` CI 작업은 `MAMA_RELEASE_KEYSTORE_BASE64`·`MAMA_RELEASE_STORE_PASSWORD`·`MAMA_RELEASE_KEY_ALIAS`·`MAMA_RELEASE_KEY_PASSWORD` secrets가 모두 있을 때만 keystore를 임시 파일로 복원해 `assembleRelease`·`bundleRelease`를 빌드하고 `apksigner`/`jarsigner`로 서명을 검증한다. 자격 증명은 저장소에 기록하지 않는다.
- secrets가 없는 환경에서는 이 작업이 건너뛰고, `verify` 작업의 음성 검사가 계속 fail-closed 계약을 강제한다. 현재 저장소에는 이 secrets가 설정되어 있지 않아 성공 경로는 실행한 적이 없다.

## 실기기 수동 QA 절차 (알림 청취자)

CI/개발 에뮬레이터에서 알림 청취자 권한의 자동 부여가 항상 가능한 것은 아니다. 수동 절차:

1. QA 빌드 설치: `adb install app/build/outputs/apk/debug/app-debug.apk`(`kr.mom.probe.qa`).
2. 권한 부여(자동): `adb shell cmd notification allow_listener kr.mom.probe.qa/kr.mom.probe.service.ProbeNotificationListener`.
   - 자동 부여가 거부되는 기기/빌드에서는: 설정 → 알림 → 특수 앱 접근 → 알림 접근 → MAMA(QA) 허용.
3. 계측 실행: `ANDROID_SERIAL=<id> ./gradlew :app:connectedDebugAndroidTest`.
4. `NotificationPipelineDeviceTest`가 청취자 부여를 자체적으로 시도하고, 실패 시 정직하게 skip한다. 테스트가 post한 합성 알림(`회신안내`)은 `cmd notification post`에 취소 명령이 없어 트레이에 남을 수 있다 — 손으로 밀어 제거하면 된다(QA 기기 전용).
5. 실서비스 알림(학교 앱 등)으로의 수집 검증은 별도 실기기 절차로, 여전히 NOT_RUN이다.

## HWP/HWPX fixture 커버리지

- 결정론적 합성 fixture: `SyntheticHwp5`(단위 테스트 소스셋)가 CFB/OLE·BodyText·BinData 스트림을 코드로 생성한다. 저작권·개인정보가 없고 원격 다운로드에 의존하지 않는다.
- `DocumentTextExtractorTest`는 합성 HWP5(압축·비압축)·HWPX로 기본 파서 경로를 skip 없이 검증하고, `syntheticHwp5EmbeddedBinaryReportsPartialAndSkipsBinData`가 `BinData/` 스트림 존재 시 `EMBEDDED_BINARY_SKIPPED`+PARTIAL을 확인한다.
- `SchoolWebsiteClientTest.extractsSyntheticHwp5AttachmentThroughRealExtractor`는 실제 추출기로 합성 첨부를 통과시켜 동일 계약을 커버한다.
- 기기에서는 `androidTest/assets/synthetic-parent-notice.hwp`(위 생성기로 만든 결정론적 파일, sha256 고정)가 skip 없이 실행된다.
- 실제 공개 문서 fixture(`device-qa/public-fixtures/`)는 저작권 검토 대상으로 opt-in을 유지한다 — `assumeTrue` skip 2건은 의도된 상태다.

## API 36 호환성 정리

- `AppNotificationAccess`: deprecated `AppOpsManager.unsafeCheckOpNoThrow` → `checkOpNoThrow`(API 29+, minSdk 33이라 안전). 실패는 여전히 catch에서 null("알 수 없음")로 처리해 "차단"으로 오인하지 않는다.
- `ClayTheme`: deprecated `Window.statusBarColor`/`navigationBarColor`/`isStatusBarContrastEnforced`/`isNavigationBarContrastEnforced`를 API 35+(edge-to-edge 강제로 무시되는 범위)에서 읽지도 쓰지도 않도록 `legacyBarStyle`/`applyClayBarBackground`/`restoreBarStyle`로 격리했다. API 34 이하에서는 동일 동작을 유지하고, 테마 복원 경로도 이전 상태를 그대로 되돌린다. 밝은 아이콘 플래그(`isAppearanceLight*`)는 전 API에서 `WindowCompat` 컨트롤러로 유지.

## 사용자 조사 (NOT_RUN)

- 실제 부모 5명 이상, 7~14일, 중요 공지와 대조한 알림 300건 이상 수집 실험은 시작하지 않았다. G0 완료로 주장하지 않는다.

## 의도적 미구현·비활성

- 나이스 운영 동기화: 운영 API 키를 APK에 넣을 수 없고 안전한 서버 프록시가 없으므로 `NeisPublicClient.PRODUCTION_SYNC_ENABLED=false`다. 연결·수동 조회 UI는 비활성이며 `안전한 운영 연동 준비 중 · 현재 자동 조회 미지원`으로 표시한다.
- 원본 알림 숨김: 기본 OFF, `NotificationHidingPolicy` 경계를 유지한다. 실기기 안전 근거가 생기기 전까지 켜지 않는다.
- e알리미·하이클래스 웹의 개인 공지 자동 조회: 미구현이며 UNSUPPORTED로 표시한다.

## 한계

- Robolectric 화면 검증은 실제 Compose 렌더이지만 물리 기기 캡처가 아니다. OS 키보드·권한 화면·제조사 알림 UI는 실기기에서 다시 확인한다.
- CI 에뮬레이터 실행은 설치·권한 왕복을 대체하지 않는다.
