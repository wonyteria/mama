# 병원·대화·지출·일정 앱 검토

2026-09-13. 원자료는 [utilities.json](utilities.json). 공식 Play 설치 구간은 누적 배포 규모의 단서다. 엄마 이용 순위·한국 활성 이용자·현재 점유율로 해석하지 않았다. 이 목록은 육아 관련 일상 업무의 연결 후보이며 대표 표본으로 만든 순위가 아니다.

| 앱 | Play 누적 설치 표시 | 실제 업무 | 우리에게 정보를 주는 경로 |
|---|---|---|---|
| [똑닥](https://play.google.com/store/apps/details?hl=ko&id=com.bbros.sayup) | 500만+ | 병원 접수·예약·대기 확인 | 개인 예약 읽기 API 미확인. 알림 내용 실측·정당한 연동 필요 |
| [카카오톡](https://play.google.com/web/store/apps/details?gl=KR&hl=ko&id=com.kakao.talk) | 1억+ | 가족/기관 대화·주문/예약 알림톡 | 일부 알림 관찰 후보. 개인 대화 전체 읽기 API는 제공하지 않는다는 공식 답변 |
| [쿠팡](https://play.google.com/store/apps/details?hl=ko&id=com.coupang.mobile) | 5,000만+ | 구매·배송·반품 | 판매자 API와 부모의 소비자 구매 이력은 별개. 알림 세부 내용 미측정 |
| [뱅크샐러드](https://play.google.com/store/apps/details?hl=ko&id=com.rainist.banksalad2) | 500만+ | 금융 연결·자동 가계부 | 대체재. 제3자 소비자 이력 읽기 API는 확인한 문서에서 미발견 |
| [Google Calendar](https://play.google.com/store/apps/details?hl=ko&id=com.google.android.calendar) | 100억+ | 기존 개인·공유 일정 | 공식 Calendar API 또는 기기 Calendar Provider. 비공개 데이터는 사용자 권한 필요 |
| [TimeTree](https://play.google.com/store/apps/details?hl=ko&id=works.jubilee.timetree) | 1,000만+ | 공유 일정·가족 조율 | 과거 Connect App 공개 API는 2023-12-22 종료. 현재 자동 연결 가능으로 표시하지 않음 |

## 새로운 중요한 확인

TimeTree는 제3자 개발 앱 연결을 제공하던 Connect App API 종료를 공식 발표했다. 예전 연동 예제나 공유 캘린더라는 기능만 보고 우리 앱도 읽을 수 있다고 판단하면 안 된다. 외부 캘린더 가져오기는 별도 기능이다. [공식 종료 공지](https://timetreeapp.com/intl/en/newsroom/2023-12-14/connect-app-api-202312)

Google Calendar에는 공식 이벤트 읽기 API가 있고 갱신·삭제를 조회하는 옵션이 있다. 실제 사용자 캘린더의 읽기는 권한·동의 범위가 필요하다. 이번에 개인 Google 계정을 연결하거나 읽지는 않았다. 이미 일정이 있는 가정에서는 새 기록 없이 쓸 후보지만, 캘린더를 쓰지 않는 엄마에게 새 입력 습관을 요구하는 방법으로 바꾸지 않는다. [공식 API](https://developers.google.com/workspace/calendar/api/v3/reference/events/list), [동의 범위](https://developers.google.com/workspace/calendar/api/auth)

카카오의 공식 설명상 개인 간 메시지 전체를 외부 연동하는 일반 API는 제공되지 않는다. 주문·예약 알림톡이라는 실제 정보 경로는 있지만 발신·수정·삭제·미수신을 포함한 전체 합의를 보장하지 못한다. [공식 답변](https://devtalk.kakao.com/t/topic/148362)

쿠팡의 공개 OpenAPI 문서는 vendorId·WING·판매자 주문 처리 맥락이다. 이를 소비자가 다른 판매자에게 구매한 모든 내역을 가져오는 권한으로 바꾸어 해석하지 않는다. [공식 판매자 FAQ](https://developers.coupangcorp.com/hc/ko/categories/360001818893-FAQs)

똑닥은 병원 EMR과 실제 업무를 연결한다. 엄마 비서가 방문 준비를 도울 수 있다는 가설과, 똑닥의 현재 대기 상태/예약을 직접 처리하는 권한을 갖는다는 주장을 구분한다. [공식 병원 안내](https://hospital.ddocdoc.com/signin)

## 공개 리뷰에서 얻은 문제 가설

Play가 현재 보여준 일부 리뷰만 읽었다. 표본의 선택 방식·응답자 부모 여부를 모르며 전체 발생률을 추정하지 않는다. 닉네임·개인정보는 수집하지 않는다.

- 똑닥 2026-09-08 표시 리뷰: 순번 확인 화면의 광고가 핵심 업무를 방해한다는 의견. 사업 적용 가설은 중요한 업무 화면에 광고·추천을 우선 배치하지 않는 것. [Play](https://play.google.com/store/apps/details?hl=ko&id=com.bbros.sayup)
- Google Calendar 2026-07-07 표시 리뷰: 비슷한 과거 일정을 찾아 복사하는 부담. 다만 새 메모 습관이 없는 사용자까지 해결했다고 해석할 수 없다. [Play](https://play.google.com/store/apps/details?hl=ko&id=com.google.android.calendar)
- 뱅크샐러드 2026-08-27 표시 리뷰: 카드와 페이머니 결제 중복에 대한 혼란. 납부·결제 연동에서는 금액 합계보다 중복·취소·불명확성 처리 검증이 필요하다는 가설. 2022년 상품별 세부 분류 불편 리뷰는 과거 사례이며 현재 미해결이라고 단정하지 않는다. [Play](https://play.google.com/store/apps/details?hl=ko&id=com.rainist.banksalad2)

## 1인 사업에 적용

당장 이용자 수가 가장 큰 앱을 먼저 붙이는 전략은 적절하지 않다. 필요한 정보가 실제로 존재하고, 정당한 지속 접근 경로가 있고, 엄마가 반복해서 직접 옮기지 않아도 되는 업무를 고른다. 개인 금융 통합·진료 데이터·모든 메신저 통합은 초기 운영비와 검증 부담을 크게 만들 수 있으므로 핵심 가설이 확인되기 전 확정 지원하지 않는다.
