# 통화·문자·카카오톡·알림 접근 가능 범위

확인: 2026-09-13. 개발할 일반 Android 앱을 기준으로 한 공식 문서 검토. 실제 단말·카카오톡 수신·통화 녹음 실험은 하지 않았다.

| 정보 | 기술적 접근 후보 | 전체 자동 수집의 한계 |
|---|---|---|
| 일반 앱의 새 알림 | 사용자가 허용한 NotificationListenerService로 게시된 내용 수신 | 원문/첨부/과거 전체 데이터 보장 없음, 미발생·숨김·민감내용 제한 |
| 카카오톡 수신 메시지 일부 | 해당 기기의 실제 알림에 본문이 포함될 때 수신 | 개인 대화 전체 읽기 공개 API 없음. 발신 메시지·과거·사진·알림 미발생 대화의 전체성 보장 불가 |
| 문자/SMS 내역 | 적격 기본 SMS/Assistant 역할 또는 승인된 예외와 필요한 권한 | Play 심사 필요. RCS 등 다른 메시징 저장소까지 접근하는 뜻 아님 |
| 통화 기록 | 적격 역할/예외와 통화기록 권한 | 상대·시각·기간 같은 메타데이터이며 대화 음성 아님 |
| 통화 대화 내용 | 정당하게 생성되고 접근 허용된 녹음/전사문을 전달받아 분석 | 일반 앱은 시스템 전용 VOICE_CALL 음성 권한을 보통 받을 수 없음. 제조사 녹음 지원과 제3자 앱의 자동 접근은 별개 |

Android NLS는 알림 객체를 수신한다. MessagingStyle에 일부 과거/자신의 메시지가 포함될 수는 있지만 발신 앱이 그 정보를 넣는 경우이며 대화 전체 조회 권한을 주지 않는다. [NLS](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [MessagingStyle](https://developer.android.com/reference/android/app/Notification.MessagingStyle)

카카오 공식 개발자 답변은 개인 간 메시지 내용을 외부 연동하도록 제공하지 않는다고 설명한다. 공개 카카오톡 메시지 API는 허용된 서비스 사용자 간 발송 중심이며 개인 채팅방의 전체 읽기 API로 해석하지 않는다. [공식 답변](https://devtalk.kakao.com/t/topic/148362), [메시지 API](https://developers.kakao.com/docs/ko/kakaotalk-message/rest-api)

**기획 정정:** 문자 앱의 알림이 기술적으로 수신될 수 있다는 사실만으로 SMS 제한을 면제받는 것은 아니다. Play 정책은 SMS/통화기록 제한 데이터를 다른 권한·API 등의 대체 방법으로 유도하는 것도 제한한다. 따라서 NLS를 SMS 권한 심사 우회 수단으로 설계하지 않는다. 기본 Assistant/SMS/Phone 또는 적격 예외의 요건과 실제 승인 범위를 먼저 검토한다. 예외가 존재하지만 이 엄마 비서 앱의 승인 가능성을 확정하지 않는다. [SMS·통화기록 정책](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en), [민감 권한 정책](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en-NZ)

Android VOICE_CALL/UPLINK/DOWNLINK 음성 캡처는 시스템용 CAPTURE_AUDIO_OUTPUT 권한을 요구한다. 일반 앱의 마이크 권한만으로 양쪽 통화가 안정적으로 녹음된다고 약속하지 않는다. 사용자가 공유한 녹음 파일·전사문 분석은 별도의 입력 방식이며, 자동·상시 수집을 검증한 것이 아니다. [공식 음성 소스 문서](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)

사업 영향: 확보한 내용에서 일정·할 일·관련 인물·출처·확실성을 구조화하고 기억하는 제품은 구현 후보가 된다. 다만 수신 메시지에 ‘금요일 3시’가 보였어도 엄마의 발신 답장에서 ‘4시로 변경’했을 수 있다. 편향된 일부 관찰을 전체 합의로 확정하면 안 된다. 명시적 합의나 사용자 확인이 없으면 일정 후보로 유지한다.

초기 권장 범위: 정책에 맞는 실제 지원 앱의 알림 + 엄마의 직접 위임 + 선택한 캘린더. 문자 전체·통화기록·통화내용·카톡 전체 대화의 자동 통합을 첫 버전의 전제로 삼지 않는다. 통화 전사/공유와 적격 메시지 연결은 독립 검토 후 추가한다.
