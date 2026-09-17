package kr.mom.probe.fixture

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Separate QA fixture. Never bundled inside the consumer APK. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("fixture", "합성 테스트 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        layout.addView(TextView(this).apply { text = "개발 검증용 합성 알림입니다. 실제 학교 공지가 아닙니다." })
        layout.addView(Button(this).apply { text = "테스트 알림 보내기"; setOnClickListener { post(manager) } })
        setContentView(layout)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (intent.getBooleanExtra("send", false)) {
            post(manager)
            finish()
        }
    }
    private fun post(manager: NotificationManager) {
        val title = intent.getStringExtra("title") ?: "[테스트] 내일 미술 준비물"
        val body = intent.getStringExtra("text") ?: "합성 테스트 자료입니다. 내일 미술 시간에 물감, 붓, 스케치북을 준비해주세요."
        manager.notify(intent.getIntExtra("id", 101), Notification.Builder(this, "fixture")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true).build())
    }
}
