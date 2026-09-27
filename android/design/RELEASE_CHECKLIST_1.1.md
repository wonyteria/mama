# MAMA 1.1 출시 체크리스트

versionCode 14 / versionName 1.1.0 · feature/1.1-reliability-rebuild 후보.
각 항목은 실행 증거 없이 체크하지 않는다. 미실행 항목은 NOT_RUN으로 남긴다.

## A. 저장소 상태 (자동 확인 가능)

- [ ] `git merge-base --is-ancestor origin/main HEAD` — 후보가 최신 1.0.2 위에 있다.
- [ ] `versionCode` > 13, `versionName` = `1.1.0` (`android/app/build.gradle.kts`).
- [ ] `origin/main...HEAD` diff에 의도하지 않은 기능·테스트·문서 삭제가 없다.
- [ ] fresh clean 검증: `cd android && ./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 통과.
- [ ] 계측 테스트 JUnit XML(권위): tests/failures/errors/skipped 합계를 `app/build/outputs/androidTest-results/connected/*.xml`에서 집계. Gradle 콘솔의 `Finished N tests` 표시는 UTP 집계상 XML과 다를 수 있으므로 신뢰하지 않는다.
- [ ] `NeisPublicClient.PRODUCTION_SYNC_ENABLED == false`.
- [ ] `NotificationHidingPolicy` 원본 알림 숨김 기본 OFF 유지.

## B. 서명·아티팩트 (secrets 필요 — 현재 NOT_RUN)

- [ ] `MAMA_RELEASE_KEYSTORE_BASE64`·`MAMA_RELEASE_STORE_PASSWORD`·`MAMA_RELEASE_KEY_ALIAS`·`MAMA_RELEASE_KEY_PASSWORD`가 CI secrets로만 설정돼 있다(저장소에 키/비밀번호 없음).
- [ ] `signed-release` CI 작업이 keystore를 runner 임시 공간에 복원해 `assembleRelease`·`bundleRelease`를 빌드한다.
- [ ] release APK 존재 + `apksigner verify --verbose --print-certs` 통과.
- [ ] release AAB 존재 + `jarsigner -verify` 통과.
- [ ] secrets 없을 때 `assembleRelease`가 두 지정 메시지로 fail-closed:
  - `Release signing is not configured. Set MAMA_RELEASE_STORE_FILE, ...`
  - `Debug signing is never used for release builds.`
- [ ] release `BuildConfig.java`에 `NEIS_API_KEY` 필드 없음.
- [ ] 서명된 APK의 인증서 지문을 릴리스 노트에 기록한다.

## C. 권한·개인정보 선언

- [ ] 매니페스트 권한 검토: `POST_NOTIFICATIONS`, `BIND_NOTIFICATION_LISTENER_SERVICE`(서비스), 인터넷/네트워크, 알람 관련 권한이 선언과 실제 사용에 일치.
- [x] `USE_FULL_SCREEN_INTENT` 미사용 — 전용 알람/통화 앱이 아니므로 Play 정책상 사용 불가. 권한·`setFullScreenIntent`·설정 유도 UI를 전부 제거했고, 울림 알림은 high-priority heads-up + contentIntent 탭 열기만 사용한다(`RingingNotificationContractTest`가 두 경로의 `fullScreenIntent==null`·contentIntent·채널·30초 timeout·action을 Notification 객체로 검증, manifest 권한 부재도 테스트로 확인).
- [ ] `BIND_NOTIFICATION_LISTENER_SERVICE`의 Play Console 선언(알림 접근 권한 사용 앱 양식)을 실제 수집 목적과 일치하게 제출 — prominent disclosure 문구는 `PLAY_CONSOLE_1.1.md` 초안 참고.
- [ ] Play Data Safety 양식: 알림 내용 수집 목적(할 일 생성), 암호화 저장, 기기 외 전송 여부, 사용자 삭제 가능 여부를 실제 구현과 일치하게 기입.
- [ ] 개인정보처리방침 URL 게시: 수집 항목(알림 텍스트, 자녀 학년·학교명), 보관 기간, 삭제 절차, 연락처 포함.
- [ ] 데이터 보관 기간과 자동 삭제 정책을 문서화하고 구현과 대조.
- [ ] 사용자가 앱 내에서 수집 데이터를 삭제할 수 있는 경로 존재 확인.
- [ ] 알림 접근 권한 고지: 설정 흐름에 사용자 안내 문구가 있고, 스토어 심사용 사용 사유 설명을 준비한다(Google Play의 Notification Listener 허가 정책 대응).
- [ ] 백업·복원: `android:allowBackup`/`fullBackupContent` 설정과 암호화된 Room DB의 복원 동작 확인.
- [ ] 롤백 계획: 1.1 설치 후 문제 발생 시 1.0.2로의 롤백 경로(DB 스키마 마이그레이션 역호환 여부)를 문서화.

## D. 기기 검증 (실기기 필수)

- [ ] QA 빌드(`*.qa` 패키지) cold launch·프로세스 유지·즉시 crash/ANR 없음 — SM-S926N / API 36에서 1.1.0-qa 확인됨(증거: VERIFICATION_1.1.md).
- [ ] 실기기 `connectedDebugAndroidTest`를 `ANDROID_SERIAL` 명시로 실행하고 XML 결과를 기록한다. **주의**: Gradle connected 테스트는 종료 정리에서 target QA 패키지(`kr.mom.probe.qa`)를 자동 제거한다 — QA 데이터 보존이 필요한 기기에서는 실행 금지. disposable QA state/격리 전용 기기에서만 실행한다.
- [ ] 합성 알림 → 청취자 캡처 → 후보 분석 → 할 일 생성 E2E가 실기기에서 통과(`NotificationPipelineDeviceTest`).
- [ ] 실제 학교 앱 알림 수집: NOT_RUN(실제 부모 기기 필요).
- [ ] 알림 접근 권한 회수 후 재부여 시 캡처 중단/재개 확인: NOT_RUN.
- [ ] 재부팅·프로세스 종료 후 할 일·스누즈·읽음 상태 유지: NOT_RUN.
- [ ] 잠금화면 `FLAG_SECURE`와 원본 알림 숨김 경계: NOT_RUN.
- [ ] 알람 울림·완료·미루기의 실기기 동작: NOT_RUN.
- [ ] crash/ANR: 설치 후 24시간 이상 사용 중 logcat/Play pre-launch 보고서로 확인: NOT_RUN.

## E. 스토어 제출 전 수동 점검

- [ ] Play Console에서 대상 API(36) 요구사항 충족 확인.
- [ ] 출시 노트(한국어) 작성.
- [ ] 스크린샷·스토어 등록정보 최신화.
- [ ] 단계적 출시(staged rollout) 비율 결정.
- [ ] 사전 출시 보고서(pre-launch report) 결과 확인 후에만 프로덕션 트랙 사용.

## F. 사용자 조사 (외부 활동 — NOT_RUN)

- [ ] 실제 부모 5명 이상, 7~14일 사용, 중요 공지와 대조한 알림 수집 정확도 300건 이상.
- [ ] 위 완료 전까지 "사용성 검증 완료"를 주장하지 않는다.
