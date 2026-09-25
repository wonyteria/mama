# 나는 엄마다 (mama)

학교·학원 공지에서 준비물, 마감, 일정 변경을 자동으로 챙기는 엄마용 로컬 에이전트입니다.

현재 작업 폴더는 `D:\플랫폼\mama\project`이고, 원격 저장소는 [wonyteria/mama](https://github.com/wonyteria/mama)입니다.

## 구성

- `android/` — MOM PROBE / 모모 Android 앱. 현재 시험판은 0.9
- `docs/product/` — PRD, 기능명세서, 유저플로우, 와이어프레임, 검수 문서
- `app-research/` — 학교·학원 앱 및 공공데이터 조사
- `validation/` — 제품 가설·경제성·접근성 검증
- `device-tests/` — 실기기 테스트 기록

앱 빌드와 버전별 구현 기록은 [android/README.md](android/README.md)를 보세요.

## 현재 상태

- 공개 학교 웹 공지 자동 조회와 HWP/HWPX 텍스트 추출까지 구현
- e알리미 개인 공지는 공식 웹 로그인 후 DOM 계약 검증이 남아 있음
- 이미지 OCR과 생성형 AI는 아직 포함하지 않음
