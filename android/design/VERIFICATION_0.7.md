# 0.7 최종 검증과 배포 범위

2026-09-15. GPT-6 Astra High 설계 → GPT-5.5 구현 → GPT-6 Astra High 독립 재검토 순서로 진행했다. 추가 오류를 수정한 뒤 제한된 로컬 비서 범위의 소스 검토를 통과했다. 세부 수정 파일은 `IMPLEMENTATION_0.7.md`, 검토 이력과 최종 종결은 `REVIEW_0.7.md`에 있다.

## 확인한 증거

| 항목 | 결과 |
|---|---|
| 최종 JUnit XML 집계 | 83개, 실패 0, 오류 0, 건너뜀 0 |
| 컴파일·APK | assembleDebug / assembleRelease 성공 |
| Android Lint | 오류 0, 경고 54 |
| 독립 소스 검토 | 추적한 blocking 항목 모두 CLOSED |
| APK 식별 | kr.mom.probe, versionCode 7, 0.7.0-agent |
| Android 범위 | minSdk 33 / targetSdk 36 |
| 서명 검사 | apksigner verify 성공, v2 서명 1개; 로컬 시험용 서명 |
| 배포 APK SHA-256 | B92308FC79CE35D8907CCF2B7A8D9BBC0D7ED2959CB2A9DA7C55AA8A23B5D417 |
| 홈 렌더 검토 | PC Compose 렌더 기준 90/100; 구체적 준비물·기한·짧은 CTA 확인 |
| 최종 실기기 | ADB 연결 목록이 비어 있어 새 APK 설치·구동 검증 미수행 |

Lint 경고는 UseKtx 20, GradleDependency 13, SetTextI18n 10, ApplySharedPref 4, NewerVersionAvailable 3, StaticFieldLeak 3, AndroidGradlePluginVersion 1이다. StaticFieldLeak 대상 세 저장소는 모두 `context.applicationContext`를 사용함을 확인했다. 경고를 모두 없앴다고 보고하지 않는다. 의존성 버전 일괄 변경·다국어 확장은 이번 수정 범위가 아니다.

## 검증 범위의 한계

자동 테스트는 로컬 판단·대화 의도·날짜·저장·브리핑·알림 경계와 PC UI 렌더의 회귀를 다룬다. 테스트의 수동 전사문은 실제 OCR 결과가 아니다. 이 사례 수가 모든 학교 공지에 대한 정확도를 입증하지 않는다.

새 빌드의 실제 NotificationListener 수신, 첫 설치·업데이트, 잠금화면, 백그라운드·배터리 제한, 실제 알람 도착과 Android 16 UI는 휴대폰 재연결 후 별도 검증해야 한다. 이전 0.6 기기 검증을 새 버전의 실기기 검증으로 사용하지 않았다.

OCR SDK, 생성 AI/API 호출, 개인 웹 공지 조회·인증 갱신, 실제 등하교 서비스 연결, 활동 사진·영상 모으기는 구현 완료로 보고하지 않는다. 등하교·미디어는 `ATTENDANCE_AND_MEDIA_PLAN.md`, OCR는 `LOCAL_OCR_IMPLEMENTATION_PLAN.md`에 설계했다. 새 라이브러리 추가 확인과 실제 서비스 식별이 남아 있다.

## 자료

- `dist/나는엄마다-0.7.0.apk`: 로컬 시험용 설치파일.
- `dist/나는엄마다-0.7.0-source.zip`: app/fixture 경로를 유지한 소스·설계 묶음. 빌드 캐시, 기기 데이터, 서명 키는 포함하지 않는다.
- `dist/사용안내-0.7.md`: 설치·지원 범위·미지원 기능.
- `dist/build-manifest-0.7.0.json`: APK·소스 해시와 검증 범위.

최종 판단은 **로컬 비서 개선판의 PC 검증 완료, 전체 AI 비서 및 실사용 출시 검증 미완료**이다.
