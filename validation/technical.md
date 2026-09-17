# Android 비서 기술 검토와 실행 증거

검토일: 2026-09-13 KST. 대상: 기존 PRD의 Android 교육 알림 → 할 일 자동화 P0. 판정: **일부 기능은 공식 API로 구현 가능하나, 학교종이/e알리미의 실제 업무 정보 확보율은 아직 검증되지 않았다. Android 앱이 작동한다고 판정할 단계가 아니다.**

## 1. 사업을 좌우하는 기술 조건

알림을 읽는 것과 학교 공지 원문을 확보하는 것은 다르다. NotificationListenerService는 게시·제거된 Notification과 출처·식별 정보를 받는 API다. 학교 서비스 내부 문서 전체를 제공하는 API가 아니다. 실제 알림의 제목/본문/확장 텍스트에 대상·행동·기한이 들어오는지 소스별 대조가 필요하다. Android 문서상 알림 제거에는 클릭·사용자 지우기·앱 취소 등 여러 원인이 있어, 제거 이벤트를 행사 취소나 할 일 완료로 해석할 수 없다. [공식 NLS API](https://developer.android.com/reference/android/service/notification/NotificationListenerService)

**실행한 반례:** 관찰 가능한 source/title/text/key/postedAt가 모두 같은 알림 “새 공지가 등록되었습니다.”에 대해 원문 A는 9월 14일 동의서 제출, 원문 B는 9월 18일 체험학습비 납부인 두 가능한 세계를 합성했다. 동일 입력에 구별 근거가 없으므로 알림만 받는 시스템은 둘을 모두 정확히 복원할 수 없다. 더 좋은 LLM으로 해결되는 문제가 아니다. 이것은 논리적 반례이며 두 실서비스가 실제로 이 payload를 보낸다는 증거는 아니다.

첫 의사결정은 모델 선정이 아니라 **학교·학원 업무가 실제로 알림에 충분히 노출되는지**다. 불충분하면 공식 연동/제휴 등 합법적 별도 입력 경로를 확보하거나 지원 범위를 바꿔야 한다. 숨겨진 API·로그인·보안 설정 우회, 접근성 화면 수집으로 이를 메우지 않는다.

## 2. 공식 문서에서 확인한 제약

| 항목 | 확인 사실과 제품 영향 |
|---|---|
| 알림 읽기/보내기 | NLS 접근과 앱의 POST_NOTIFICATIONS는 별도다. Android 13 이상에서 일반 알림 발송은 런타임 허용을 필요로 하므로 “읽고 있지만 안내를 못 보냄” 상태를 표시해야 한다. [알림 발송 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission) |
| 민감 알림 | Android 15는 OTP가 탐지된 알림의 비편집 내용을 비신뢰 NLS 앱이 읽지 못하도록 한다. 모든 금융 알림이 차단된다는 뜻은 아니며, 일반 앱이 예외를 확보한다고 가정해서도 안 된다. [Android 15 변경](https://developer.android.com/about/versions/15/behavior-changes-all) |
| 연결과 기기 | NLS는 연결 완료 후 작업해야 한다. Android Q 이하 저메모리 기기와 업무 프로필 등에 제약이 있다. 지원 OS·제조사·프로필은 실제 테스트로 제한한다. [공식 NLS API](https://developer.android.com/reference/android/service/notification/NotificationListenerService) |
| 가족 캘린더 | Calendar Provider의 이벤트는 권한을 갖고 조회할 수 있다. 특정 앱 내부 일정 전체가 여기에 노출되는지는 별도 문제다. 읽기/쓰기/사용자 확인 인텐트를 구별한다. [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider) |
| 예약 신뢰성 | 정확한 알람은 특별 접근과 목적 제약이 있다. 하루 요약에 불필요한 exact-alarm 권한을 추가하지 말고, 허용 지연·재부팅·오프라인 복구를 실제 기기로 확인해야 한다. [알람 예약](https://developer.android.com/develop/background-work/services/alarms) |
| 사전 설명·동의 | 예상 밖 백그라운드 민감정보 접근에는 권한 전에 앱 내 설명과 명확한 동의가 필요하다. 설정 화면/약관만으로 대체할 수 없다. 선택 안 한 소스는 저장·로그·전송 전에 기기에서 제외해야 한다. [Play User Data](https://support.google.com/googleplay/android-developer/answer/10144311) |
| Play 신고·심사 | 실제 수집·외부 SDK에 맞는 Data safety와 개인정보처리방침을 준비한다. 민감/고위험 권한은 사용 권한에 따라 선언·승인을 요구할 수 있다. 조사한 공식 자료에서 **NLS라는 이유만으로 모든 앱에 별도 NLS 선언 양식이 필수라는 근거는 확인하지 못했다.** 최종 manifest와 Play Console 요구를 확인해야 하며 승인 가능성을 확정하지 않는다. [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [권한 선언](https://support.google.com/googleplay/android-developer/answer/9214102) |
| 첫 배포 일정 | 2023-11-13 이후 생성 개인 개발자 계정은 최소 12명의 14일 연속 비공개 테스트 후 프로덕션 접근을 신청한다. 5명 문제 면담은 이 요건을 충족하지 않는다. [신규 개인 계정 테스트](https://support.google.com/googleplay/android-developer/answer/14151465) |
| 출시 대상 API | 제출 당시 적용되는 target API 요건을 확인하고 빌드에 반영해야 한다. 이번에는 AAB/Console 제출을 하지 않았다. [Play target API 정책](https://support.google.com/googleplay/android-developer/answer/11926878) |

이 문서는 국내 개인정보법 적용이나 Play 승인을 확정하는 법률 검토가 아니다. 정책의 사전 설명·동의와 OS 접근 허용, 서비스 원문 권한은 서로 대신하지 않는다.

## 3. 실행 환경 확인

읽기 전용 확인만 수행했다. 의존성 설치, 휴대폰 개인정보 열람, 계정 사용을 하지 않았다.

| 명령/확인 | 실제 결과 |
|---|---|
| `Get-Command java,javac,adb,node,python -ErrorAction SilentlyContinue` | node/python 발견. java/javac/adb는 PATH에서 발견 못함 |
| `Get-Item Env:ANDROID_HOME,Env:ANDROID_SDK_ROOT,Env:JAVA_HOME -ErrorAction SilentlyContinue` | 세 환경변수 미발견 |
| 표준 경로 `C:\Users\user\AppData\Local\Android\Sdk`, `C:\Program Files\Android\Android Studio`, `C:\Program Files\Java` | 세 경로 모두 `Test-Path` false |
| `node --version` | `v24.14.1`, 실행 파일 `C:\Program Files\nodejs\node.exe` |
| `adb devices` | adb가 없어 실행하지 못함. “연결된 기기 없음”으로 단정하지 않음 |

초기 묶음 명령의 exit 1은 선택 검사에서 환경변수/도구를 못 찾은 결과였다. 후속 표준 경로·Node 확인 명령은 exit 0이었다. 전체 디스크를 뒤진 것은 아니므로 비표준 위치의 SDK 존재까지 부정하지 않는다. 지금 확인된 환경으로는 APK/NLS 빌드·설치·수신 검증을 수행할 수 없었다.

## 4. 이번에 실제 작성하고 실행한 검증

새 외부 의존성 없이 Node 내장 모듈로 규칙·상태 하니스와 별도 fixture 파일을 만들었다.

- [engine.mjs](technical/engine.mjs): 저장 전 소스 필터, 최소 근거 게이트, 중복·수정·완료·명시적 취소·해제 모델.
- [fixtures.json](technical/fixtures.json): 손으로 작성한 합성 입력/기대 결과 및 동일 payload 반례. 구현이 기대 결과를 자동 생성하지 않는다. 별도 외부 평가자가 작성한 데이터는 아니다.
- [validate.mjs](technical/validate.mjs): Node assert로 각 fixture 및 경계 조건 실행.
- [results.json](technical/results.json): 실행 시각, 런타임, 개별 결과.

실행 명령:

```powershell
node --check 'D:\Codex\mom-agent\validation\technical\engine.mjs'
node --check 'D:\Codex\mom-agent\validation\technical\validate.mjs'
node 'D:\Codex\mom-agent\validation\technical\validate.mjs'
```

결과: 문법 검사 exit 0. **합성 검증 17/17 통과, exit 0.** 첫 실행 시각은 2026-09-12T22:53:26Z(한국 9월 13일)다. 결과 파일은 재실행 시각으로 갱신된다.

검증 내용: 알림에서 숨겨진 본문 복원 불가능 반례, 미선택 소스 저장/본문 getter 접근/전송 없음, 중복 쓰기 방지, 알림 지우기와 실제 완료 구분, 완료 후 재알림 방지, 명시 취소 처리, 해제 후 대기열 및 최종 전송 경계 제외, 동일 문서의 기한 수정, 불가능한 날짜/근거 없는 필드 거절, 소스별 ID 충돌 분리.

**중요한 범위 제한:** 이 코드는 Android 앱이나 공지 추출기가 아니다. 정형 proposal과 검증된 documentId가 입력된다고 가정하며, 이를 실제 알림에서 만들어내는 어려운 문제를 해결하지 않았다. 문구 포함 확인은 의미 정확성의 충분조건이 아니다. 예를 들어 부정·인용·복수 행사 문맥은 이 게이트가 이해하지 못한다. 완료 뒤 변경을 무조건 막는 규칙도 실제 제품에서는 중요 변경 확인 흐름으로 보완해야 한다. 메모리 내 Map/queue를 검사했으며 디스크·서버·SDK 유출, 원자적 다중 스레드 처리, 삭제 백업을 검증하지 않았다.

따라서 17/17을 LLM 정확도, 실서비스 정보 확보율, 알림 전달 성공률, 제품 완성도, 개인정보 안전성 보증이나 사업 성공 확률로 보고하지 않는다. 별도 lint/typecheck 도구 설정은 없으며 새 의존성을 설치하지 않았다.

## 5. 아직 필요한 증거와 다음 검증 순서

독립 검토에서 ‘행사 취소 아님. 예정대로 진행.’을 취소 proposal과 함께 넣었을 때 이 모델이 취소로 처리함을 확인했다. 문구 포함 검사는 의미 판단을 해결하지 않는다. 이 코드를 제품의 안전 장치로 재사용하지 않도록 클래스 이름을 SyntheticStateModel로 명확히 바꿨고 동작은 바꾸지 않았다. 코드 실험 통과와 실제 자연어 검증 실패를 함께 기록한다.

1. **컴파일·OS 최소 실험:** 명시된 SDK/JDK와 테스트 Android 기기 또는 에뮬레이터를 확보한 뒤, 합성 발신 앱과 최소 NLS probe로 text/bigText/textLines, 업데이트/지우기, 권한 해제·재연결, 재부팅·절전을 확인한다. 이것도 학교 앱 호환성 증거는 아니다.
2. **실제 입력 검증:** 동의한 보호자의 실제 기기에서 같은 기간 학교종이/e알리미의 원문 업무와 관찰 알림을 대조한다. 앱 버전·OS·기종·알림 설정·원문 존재·알림 수신·필드 충분 여부를 기록한다. 민감 원문 복제 대신 가능하면 기기 내 판정값으로 남긴다.
3. **분모 분리:** `원문 업무 중 알림이 온 비율 × 수신 업무 중 필드 충분 비율 × 충분한 입력 중 정확 처리 비율 × 적시 안내 비율`을 각각 측정한다. 예를 들어 70% × 80% × 95% × 98%는 약 52.1%다. 이 숫자는 계산 예시이며 관측치가 아니다. 높은 추출 정확도만으로 전체 발견율을 대신할 수 없다.
4. **모델 평가는 그다음:** 동의·비식별화·보관 조건이 확정된 실제 입력을 독립 정답으로 라벨링하고, 학습/프롬프트 개선용과 보류 평가용을 분리한다. 중요한 날짜·아이·금액의 정밀도, 누락, 확인 질문 비율을 함께 측정한다.
5. **출시 검증:** 실제 데이터 흐름·암호화·권한 해제·가족 격리·삭제·진단 로그와 Play 제출 자료를 검토하고 필요한 기기/배포 테스트를 진행한다.

현재 자율적으로 더 할 수 있는 일은 공개 자료 기반 지원 소스/공식 제휴 경로 조사, synthetic 상태 테스트 독립 재검토, 입력 충분성 평가표·실험 명세·데이터 흐름 설계다. 실제 학교 수신 정보와 사용자 동의가 필요한 증거를 AI가 임의로 만들어낼 수 없다. **기술 사업성 판정은 “조건부 진행, 실제 입력 확보율 확인 전 본제품 확장 보류”다.**
