# 1.1 안정성 후보 검증 기록

feature/1.1-reliability-rebuild · versionCode 14 / versionName 1.1.0 · `origin/main`(1.0.2) 위에 DESIGN.md의 1.1 신뢰성 항목만 재적용한 후보다. 아래는 자동 검증·에뮬레이터·실기기·사용자 조사를 구분한 현재 상태다. 실행하지 않은 항목은 NOT_RUN으로 남긴다.

## 자동 검증 (PC, 최신 실행)

`cd android && ./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`

- 단위 테스트 전체 통과, 실패 0. Robolectric 화면 테스트는 프로덕션 Composable을 실제 렌더한다.
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

- 20개 실행, 18개 통과, 0 실패, 2개 skip.
- skip 2개는 외부 HWP/HWPX 공개 fixture를 `externalFilesDir/qa-input/`에 넣어야 하는 opt-in QA 테스트다. fixture 파일은 저장소에 없으므로 의도된 `assumeTrue` skip이다.
- NEIS 운영 동기화 비활성(`PRODUCTION_SYNC_ENABLED=false`) 검증과 온보딩 드라이버, 알람/스누즈/수집 흐름을 포함한다.

CI의 `instrumentation` 작업은 GitHub 호스팅 API 35 x86_64 에뮬레이터에서 같은 `connectedDebugAndroidTest`를 실행하고(KVM 가속, AVD 스냅샷 캐시, 부팅·작업 타임아웃), 실패 시 XML·HTML 보고서를 업로드한다.

## signed release (NOT_RUN)

- `signed-release` CI 작업은 `MAMA_RELEASE_KEYSTORE_BASE64`·`MAMA_RELEASE_STORE_PASSWORD`·`MAMA_RELEASE_KEY_ALIAS`·`MAMA_RELEASE_KEY_PASSWORD` secrets가 모두 있을 때만 keystore를 임시 파일로 복원해 `assembleRelease`·`bundleRelease`를 빌드하고 `apksigner`/`jarsigner`로 서명을 검증한다. 자격 증명은 저장소에 기록하지 않는다.
- secrets가 없는 환경에서는 이 작업이 건너뛰고, `verify` 작업의 음성 검사가 계속 fail-closed 계약을 강제한다. 현재 저장소에는 이 secrets가 설정되어 있지 않아 성공 경로는 실행한 적이 없다.

## 실기기 (NOT_RUN)

실제 휴대폰에서는 아직 검증하지 않았다.

- 실제 학교·학원 앱 알림 수집과 lane별 상태 정확성.
- 알림 접근 권한 회수·재부여 흐름.
- 재부팅·프로세스 종료 후 할 일·스누즈(연속 4회 이상)·읽음 상태 유지.
- 잠금화면 개인정보 보호(`FLAG_SECURE`)와 원본 알림 숨김(기본 OFF, 경계 유지).
- 완료·미루기·알람 울림의 실기기 동작.

## 사용자 조사 (NOT_RUN)

- 실제 부모 5명 이상, 7~14일, 중요 공지와 대조한 알림 300건 이상 수집 실험은 시작하지 않았다. G0 완료로 주장하지 않는다.

## 의도적 미구현·비활성

- 나이스 운영 동기화: 운영 API 키를 APK에 넣을 수 없고 안전한 서버 프록시가 없으므로 `NeisPublicClient.PRODUCTION_SYNC_ENABLED=false`다. 연결·수동 조회 UI는 비활성이며 `안전한 운영 연동 준비 중 · 현재 자동 조회 미지원`으로 표시한다.
- 원본 알림 숨김: 기본 OFF, `NotificationHidingPolicy` 경계를 유지한다. 실기기 안전 근거가 생기기 전까지 켜지 않는다.
- e알리미·하이클래스 웹의 개인 공지 자동 조회: 미구현이며 UNSUPPORTED로 표시한다.

## 한계

- Robolectric 화면 검증은 실제 Compose 렌더이지만 물리 기기 캡처가 아니다. OS 키보드·권한 화면·제조사 알림 UI는 실기기에서 다시 확인한다.
- CI 에뮬레이터 실행은 설치·권한 왕복을 대체하지 않는다.
