# 교육·보육 소통 앱 6종: 규모와 비서 연결 가능성

조사일: 2026-09-13 KST. 공개된 공식 앱 등록정보·제품 안내·고객센터·회사 공시를 읽었다. 로그인, 고객 접촉, 비공개 요청 호출, 실제 아동/부모 데이터 수집을 하지 않았다. [구조화 데이터](./education.json)에 앱별 패키지·출처·한계가 있다. 학교종이/e알리미 접근 조건은 기존 [원천 접근 검토](../validation/source-access.md)를 재사용했다.

**이 앱들은 엄마 비서가 대체할 단순 알림장이 아니라 학교·기관이 공지·신청·출결·학습·납부를 처리하는 업무 시스템이다.** 우리 제품은 여러 원천 사이에서 부모의 판단·준비·확인을 줄이는 역할을 검증해야 한다. 앱에 정보가 존재한다는 사실과 제3자 비서가 합법적·안정적으로 가져올 수 있다는 사실은 분리한다.

## 규모를 과장하지 않고 읽기

Play 다운로드 표시는 전 세계 해당 Android 앱의 누적 설치 구간이다. 한국 엄마 수, 현재 활성 사용자, 특정 지역 보급률, 유료 사용자 수가 아니다. 연령 등급도 실제 자녀 이용 연령과 다르다. 지표는 합산하지 않는다.

| 앱 | 공식 Android 패키지 | Play 표시 다운로드 / 업데이트일 | 실제 이용 맥락 |
|---|---|---|---|
| 키즈노트 | `com.vaultmicro.kidsnote` | **500만+** / 2026-09-07 ([Play](https://play.google.com/store/apps/details?hl=ko&id=com.vaultmicro.kidsnote)) | 어린이집·유치원 영유아 중심, 일부 학원 |
| 학교종이 | `com.schoolbell_e.schoolbell_e` | **100만+** / 2026-09-02 ([Play](https://play.google.com/store/apps/details?hl=ko&id=com.schoolbell_e.schoolbell_e)) | 유치원·초중고 등 학교 교육공동체; 실제 학교 채택 필요 |
| 스마트 공지시스템 e알리미 | `com.ewut.allealimi` | **500만+** / 2026-08-30 ([Play](https://play.google.com/store/apps/details?hl=ko&id=com.ewut.allealimi)) | 초중고 중심; 기업·아파트·단체·공공기관도 사용 |
| 하이클래스 | `com.iscreammedia.app.hiclass.android` | **100만+** / 2026-09-10 ([Play](https://play.google.com/store/apps/details?hl=ko&id=com.iscreammedia.app.hiclass.android)) | 초등 중심으로 학교·교사·학생·학부모 연결; 유치원·중고 확대도 회사 설명 |
| 클래스팅 | `com.Classting` | **500만+** / 2026-06-23 ([Play](https://play.google.com/store/apps/details?hl=ko&id=com.Classting)) | 초1~고3 학습 및 학급 소통 |
| 클래스노트 | `com.classnote.android.release` | **10만+** / 2026-09-07 ([Play](https://play.google.com/store/apps/details?hl=ko&id=com.classnote.android.release)) | 학원·교육기관, 원생 연령은 기관에 따라 다름 |

추가 채택 증거는 날짜·범위를 보존한다.

- 키즈노트 홈은 **2023-08 기준** 누적 가입 430만+, 글로벌 사용 원 85,000+ 등을 표시한다. 오늘의 한국 엄마 MAU로 바꾸지 않는다. [공식 제품 페이지](https://www.kidsnote.com/)
- 하이클래스 회사 연혁은 **2025-04 MAU 435만·누적 회원 530만**을 주장한다. 학부모·학생·교사 포함 수치다. 별도 2025-03-31 공시는 학부모 누적 227만·학생 220만이며 **탈퇴 회원도 포함**한다. 다른 기간·정의이므로 서로 같은 숫자를 재는 지표가 아니다. [회사 연혁](https://www.i-screammedia.com/www/company_history.html), [2025-1분기 공시](https://kind.krx.co.kr/external/2025/05/15/002722/20250515006372/11013.htm)
- 학교종이 Play의 250만 사용자, e알리미의 초중고 3곳 중 1곳·재계약 95%, 클래스팅 광고 페이지의 학부모/학생 900만+·학교 점유율 98%는 **시점 또는 산정 분모가 미표시된 자사 주장**이다. 신생 엄마 비서의 수요나 학교 점유율을 계산하는 근거로 사용하지 않는다. [학교종이 Play](https://play.google.com/store/apps/details?hl=ko&id=com.schoolbell_e.schoolbell_e), [e알리미 제품 안내](https://www.ealimi.com/Promotion/Promotion), [클래스팅 광고 안내](https://business.classting.com/)

## 앱별 업무와 접근 상태

### 키즈노트

- **업무:** 원·반 가입 후 알림장·공지 확인과 댓글 / 행사 일정·식단 확인 / 투약의뢰·귀가동의·전자문서 요청 처리 / 아이 사진·영상 보관. [공식 등록정보](https://play.google.com/store/apps/details?hl=ko&id=com.vaultmicro.kidsnote)
- **비서 가치 가설:** 준비물·행사·서류 요청을 가족별 업무로 연결.
- **외부 읽기:** NOT_FOUND_IN_SEARCH
- **내보내기/연동:** DOCUMENTED_LIMITED: 학부모 사진/영상 다운로드·앨범 출력, 키즈노트북 PDF/원본 파일 상품. 기관용 전자문서 엑셀 다운로드는 보호자 원문 API가 아님. NOT_FOUND_IN_SEARCH: 앱 일정표 존재와 ICS/외부 캘린더 연동은 별개
- **한계:** 2023-08 채택 통계를 2026 현황으로 부풀리지 않음.
- **추가 출처:** [공식 제품·2023-08 통계](https://www.kidsnote.com/), [공식 사용자 가이드](https://www.with-kidsnote.com/guide), [학부모 앨범 출력·다운로드](https://www.with-kidsnote.com/guide/parentsalbum), [사진/영상 다운로드 상품](https://www.with-kidsnote.com/guide/knbbasic/app), [키즈노트북 2025 버전](https://www.with-kidsnote.com/guide/knbpremium/app), [전자문서+](https://campaign.kidsnote.com/e-docu/)

### 학교종이

- **업무:** 가정통신문·알림장·준비물·숙제·수행평가 확인 / 방과후·돌봄·상담·우유 신청 및 설문 / 결석신고·교외체험학습·투약의뢰 서명과 증빙 제출 / 학교 방문 예약 및 종이톡/종이콜. [공식 등록정보](https://play.google.com/store/apps/details?hl=ko&id=com.schoolbell_e.schoolbell_e)
- **비서 가치 가설:** 학교별 마감·준비·제출 단계 기억과 자료 준비.
- **외부 읽기:** NOT_FOUND_IN_SEARCH
- **내보내기/연동:** DOCUMENTED_LIMITED: Play가 파일 업로드/다운로드 설명; 보호자 개인 공지의 구조화 일괄 export는 미발견 NOT_FOUND_IN_SEARCH
- **한계:** 삭제/발행대상 제외 뒤에도 알림이 남을 수 있음.
- **추가 출처:** [새글 알림 후 글이 안 보이는 경우](https://schoolbell-e.oopy.io/post_visibility_issue), [홈페이지 게시판 연동](https://schoolbell-e.oopy.io/f5bb0bbe-2102-4479-a4be-9d8b17fd56d0), [공식 학교 도입 대상](https://schoolbell-e.com/support/ko/purchase)

### 스마트 공지시스템 e알리미

- **업무:** 승인된 단체에서 공지 열람·응답 / 방과후 선착순/추첨·상담 신청 / 결석·체험학습 서류 작성·승인/반려 / 출결·개인 시간표·학교방문·급식 확인. [공식 등록정보](https://play.google.com/store/apps/details?hl=ko&id=com.ewut.allealimi)
- **비서 가치 가설:** 서명/회신/상담/강좌 신청을 필요한 행동으로 구분.
- **외부 읽기:** NOT_FOUND_IN_SEARCH
- **내보내기/연동:** DOCUMENTED_LIMITED: 관리자 설문 Raw Data 엑셀, 파일·사진 일괄 다운로드 설명. 보호자 전용 전체 업무 export 보장 아님. INBOUND_PUBLIC_DATA_DOCUMENTED: NEIS 학사·급식 API 및 학교 홈페이지를 e알리미가 읽는 연동; 외부 비서가 e알리미 개인 공지를 읽는 API 아님
- **한계:** 관리자 승인 필요.
- **추가 출처:** [공식 기능·도입 지표](https://www.ealimi.com/Promotion/Promotion)

### 하이클래스

- **업무:** 가정통신문·설문·상담 신청·학교 양식 제출 / 급식 알레르기 정보·시간표 확인 / 출결·과제 제출·학생 활동 리포트 / 하이톡·하이콜로 상담. [공식 등록정보](https://play.google.com/store/apps/details?hl=ko&id=com.iscreammedia.app.hiclass.android)
- **비서 가치 가설:** 공지에 담긴 준비와 제출·상담을 가족 일정에 연결.
- **외부 읽기:** NOT_FOUND_IN_SEARCH
- **내보내기/연동:** DOCUMENTED_LIMITED: 설문 수합·통계·출력과 사진/파일 저장. 보호자 전체 데이터 export는 미발견. NOT_FOUND_IN_SEARCH: 시간표 조회 기능만 확인
- **한계:** 회사 전체 i-Scream S 교사 점유율 93%를 하이클래스 점유율로 사용하지 않음.
- **추가 출처:** [아이스크림미디어 공식 연혁](https://www.i-screammedia.com/www/company_history.html), [2025-1분기 공시](https://kind.krx.co.kr/external/2025/05/15/002722/20250515006372/11013.htm), [공식 웹](https://www.hiclass.net/)

### 클래스팅

- **업무:** 우리 반 소식·과제·클래스톡 / AI 진단평가·맞춤학습·학습 현황 / AI 튜터와 중단 평가/놓친 학습 확인. [공식 등록정보](https://play.google.com/store/apps/details?hl=ko&id=com.Classting)
- **비서 가치 가설:** 여러 학습·가정 일정 사이 부모의 확인 업무 정리.
- **외부 읽기:** ENTERPRISE_API_SIGNAL_ONLY: 공식 요금제에 학습 데이터 API·SSO·기관 플랫폼 연동 명시. 공개 부모 공지 읽기 API/OAuth 명세는 NOT_FOUND_IN_SEARCH.
- **내보내기/연동:** NOT_FOUND_IN_SEARCH_FOR_PARENT_NOTICE_EXPORT NOT_FOUND_IN_SEARCH
- **한계:** API 연동이라는 단어만으로 부모 공지·톡·제출 상태 전체 접근이 된다고 추정 금지.
- **추가 출처:** [공식 광고·사용자 규모](https://business.classting.com/), [공식 요금제 및 API 연동](https://www.classting.com/pricing)

### 클래스노트

- **업무:** 기관이 학부모를 초대해 1:1 알림장/공지 소통 / 전자출결과 실시간 출석 알림 / 교육비 청구/수납·알림톡 / 문서/음성 숙제·학습 피드백과 앨범. [공식 등록정보](https://play.google.com/store/apps/details?hl=ko&id=com.classnote.android.release)
- **비서 가치 가설:** 교육과 지출을 연결하는 구체적 후보: 청구서·납부기한·관련 결제·중복/미확인 상태.
- **외부 읽기:** NOT_FOUND_IN_SEARCH
- **내보내기/연동:** DOCUMENTED_LIMITED: 공식 파일 첨부/다운로드 안내, 부모 문서/음성 제출. 구조화 공지·청구 전체 export는 미발견. NOT_FOUND_IN_SEARCH
- **한계:** 청구와 수납이 앱 안에 있다는 것과 비서가 그 상태를 읽을 수 있다는 것은 별개.
- **추가 출처:** [공식 서비스](https://www.classnote.com/welcome), [공식 이용 가이드](https://www.with-classnote.com/), [문서·음성 첨부와 부모 제출](https://www.with-classnote.com/news/9)

모든 앱의 실제 Android notification title/text/bigText는 **UNTESTED**다. **NOT_FOUND_IN_SEARCH는 ‘검색한 공식 자료에서 찾지 못함’이지 ‘API가 존재하지 않음’이 아니다.** 기업 제휴 API 가능성도 배제하지 않는다. 클래스팅은 실제로 엔터프라이즈 학습 데이터 API 연동을 명시하지만 부모 개인 공지 전체 읽기 명세는 확인되지 않았다.

특히 내보내기에는 사용자의 클릭이 필요한 파일 저장, 부모 전용 유료 사진/PDF 상품, 교사·관리자 전용 엑셀 통계가 섞여 있다. 이것들을 ‘모든 부모 업무의 무입력 실시간 동기화’로 묶으면 안 된다. 공개 NEIS 일정을 받는 e알리미 기능 역시 개인 공지의 외부 개방 API가 아니다.

## 공개 리뷰에서 얻은 문제 가설 3개

공식 Play 페이지에 현재 노출된 개별 리뷰를 짧게 요약했다. **작성자의 실제 보호자 여부를 확인하지 않았으며, 표본은 대표성이 없다. 빈도·불만율·전체 앱 품질로 일반화하지 않는다.** 사용자 이름은 옮기지 않았다.

1. **e알리미, 2026-08-26:** 알림이 여러 개일 때 각각 열어야 하고 다음 메시지로 넘어가기·종료 흐름이 불편하다는 경험. 공급자는 다음 날 개선 의견을 담당부서에 전달하겠다고 답했다. **가설:** 읽기 개수를 줄이되 원문 이동을 쉽게 해야 한다. [공개 리뷰](https://play.google.com/store/apps/details?hl=ko&id=com.ewut.allealimi)
2. **e알리미, 2026-04-06 표시:** 글자 확대와 자녀에게 공유가 잘 안 돼 스크린샷으로 확인한다는 경험. 해당 항목에 표시된 공급자 답변은 2021년 날짜여서 현재 불만의 원인/해결 확인 근거로 쓰지 않았다. **가설:** 큰 글씨·원문 보존·공유 준비는 실제 사용성 과제다. [공개 리뷰](https://play.google.com/store/apps/details?hl=ko&id=com.ewut.allealimi)
3. **클래스팅, 2026-08-18:** 반복적인 종료로 재실행해도 사용하기 어렵다는 경험. **가설:** 비서는 원천 앱 장애·정보 갱신 실패를 정상 상태와 구분해야 한다. 한 리뷰만으로 현재 장애가 지속된다고 판단하지 않는다. [공개 리뷰](https://play.google.com/store/apps/details?hl=ko&id=com.Classting)

이 리뷰들은 우리 서비스에 돈을 낼 의향의 증거가 아니다. 외부 요약기가 원천 장애나 글자 확대 문제를 해결할 수 있다는 증거도 아니다. 입력을 확보한 상태에서 테스트할 사용자 경험 가설이다.

## 저비용으로 행동 가능한 사업 방향

**기존 앱의 작성·접수·결제를 복제하기보다 부모 쪽의 업무 연결을 검증하는 것이 먼저다.** 이 판단은 제품 구조를 바탕으로 한 제안이며 수요 검증 결과는 아니다.

| 탐색할 업무 | 이미 원천에서 되는 일 | 비서가 추가로 증명해야 할 가치 | 완료를 판정할 증거 |
|---|---|---|---|
| 학교 서류·신청 | 학교종이/e알리미/하이클래스의 작성·서명·수합 | 자녀/상황에 맞는 서류와 준비사항을 찾고 다음 단계를 줄이는가 | 승인된 원 서비스의 제출/반려 상태 또는 명시적 사용자 확인 |
| 교육비·납부 | 클래스노트의 청구·수납 | 청구 요청과 이미 한 결제를 연결해 중복 확인·독촉을 줄이는가 | 실제 청구서와 확인된 결제/수납 기록; 금액만 같다는 이유로 납부 확정 금지 |
| 행사·준비물 | 여러 원천의 일정·알림장 | 다른 가족 일정과 조율하고 구체적 준비 계획을 만들어 노동을 줄이는가 | 원문 기한·대상, 필요한 준비와 실제 완료 확인 |
| 영유아 원 소통 | 키즈노트의 하루 기록·동의·귀가·문서 | 예전 정보를 다시 설명하는 횟수와 문서 준비 부담이 줄어드는가 | 부모가 확인한 맥락·원에서 실제 적용된 요청; 투약/귀가를 추론해 자동 승인 금지 |

개발 전 다음 연결 검증에는 **최소 하나의 허용된 입력 경로와 하나의 확인 가능한 완료 상태**가 함께 있어야 한다. 파일 저장·공유로만 성립하는 실험은 그 수동 행동까지 비용에 넣어 평가한다. ‘학교 앱 6개를 연결했다’가 아니라 ‘대표 없이 반복해도 한 종류의 가족 업무가 더 쉽게 끝난다’를 기준으로 삼는다.

검색 범위: 공식 Play 등록정보, Kids Note/Class Note 공식 웹·역할별 사용 가이드, 학교종이 도움말 및 도입 자료, e알리미 기능 소개, HiClass 웹·아이스크림미디어 연혁/공시, Classting 제품·광고·가격 안내에 앱명+API/OAuth/연동/내보내기/다운로드/캘린더/일정/이용자 조합으로 검색했다. 로그인 화면·내부 API·비공식 downloader는 실제 데이터 접근 근거에서 제외했다.

