# 0.9 푸시 없는 학교 소식 자동 조회 실기기 검증

검증일: 2026-09-16 KST  
기기: Galaxy S24+ (SM-S926N), Android 16, 1080×2340, density 450

## 결론

성남정자초등학교 공개 웹은 새 푸시와 알림 접근 권한 없이 실제 Android WorkManager가 조회하고, 암호화 저장소에 기록하는 경로를 확인했다. 학교·학년 범위를 공통 정책으로 적용하며 명시적으로 2학년에게 해당하지 않는 기록은 일정에서 제외한다.

e알리미 개인 공지 자동 조회는 완료되지 않았다. 모모가 소유한 공식 웹 세션에서 실제 로그인한 목록·상세 DOM을 검증해야 한다. 쿠키 존재나 설치된 e알리미 앱의 로그인 상태를 성공으로 사용하지 않는다.

## 실행 증거

- 최종 PC 단위 테스트: 205개, 실패 0, 오류 0, 건너뜀 2.
- debug/androidTest/release 빌드 및 lintDebug 성공. lint 오류 0, 경고 84.
- `instrumentation-source-release-candidate.txt`: 14개 모두 통과.
  - 실제 공개 HWP `1951993`: `2학년 전체 학급` 텍스트 추출, BinData 때문에 PARTIAL.
  - 실제 공개 HWPX `1953062`: 공식 section/paragraph namespace와 6학년·개인 도시락 본문 추출, 그림 때문에 PARTIAL.
  - 암호화 저장·중복 revision·삭제 tombstone·연결 generation·늦은 상태 commit 차단·여러 행사일·브리핑/대화 공통 자료 시험.
  - 생산 SourceSyncWorker를 실제 WorkManager unique work로 실행. 현재 학교 공개 기록을 저장하고 6시간 periodic work 등록을 확인.
- 0.8.1 최종 회귀에서는 같은 기기에서 명시 캘린더/알람 5개가 통과했다. 0.9 최종 APK에서 잠금 해제 후 UI 회귀는 사용자의 잠금 해제를 기다리고 있다.

## 실제 원문 검증

허용 host/path는 `snjj-e.goesn.kr/snjj-e/`와 같은 학교의 검증된 `/upload/snjj-e/na/bbs_<board>/` 첨부 경로로 제한했다.

검증 게시판:

- 공지사항 `bbsId=12354`, `mi=14296`
- 가정통신문 `bbsId=12359`, `mi=14305`
- 초1~2 맞춤형·방과후 `bbsId=12570`, `mi=14704`

목록의 게시물 ID와 상세 form의 학교 게시판·메뉴·게시물 ID를 대조한다. 요청한 페이지와 응답 페이지가 다르거나 전체 페이지·본문·첨부 일부가 확인되지 않으면 PARTIAL로 남긴다.

## 문서 안전 경계

HWP5 OLE과 HWPX ZIP/XML은 파일·stream·sector chain·압축 해제·출력 글자 수에 상한을 둔다. 암호·배포 보호·깨진 chain·DTD/entity·다른 namespace를 거절한다. HWPX는 검증한 UTF-8 문자열을 그대로 parser에 전달해 다른 인코딩으로 다시 해석하지 못하게 한다.

그림·스캔 이미지는 OCR을 추가하지 않아 자동 해석하지 않는다. 텍스트 추출 성공을 전체 표·그림 이해로 표시하지 않는다.

## 남은 실사용 검증

- release 0.9 홈에서 부분 조회·로그인 필요 상태와 저장 일정의 실제 화면 확인.
- 잠금 해제 뒤 전체화면 `모모에게 부탁`이 한 번에 대화로 이동하는 최종 회귀.
- 앱 process 종료·재부팅·장시간 절전 뒤 6시간 주기 실행 관찰.
- 모모 WebView에서 e알리미 로그인 후 실제 계정의 학교·학년·공지 목록·상세를 비식별 구조로 검증.
- 이미지/스캔 PDF OCR은 신규 라이브러리 승인 전까지 제외.

## 개인정보

기기 테스트 fixture와 문서에는 자녀 실명을 사용하지 않았다. 실제 e알리미 화면에서 학교·학년을 확인한 뒤 남아 있던 로컬 화면 캡처는 삭제했다. 공개 HWP/HWPX 원본은 `device-qa/public-fixtures`에만 두며 배포 소스 ZIP에는 포함하지 않는다.
