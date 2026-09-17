package kr.mom.probe.ui

import android.content.Context
import android.content.pm.PackageManager
import kr.mom.probe.BuildConfig

data class SourceApp(val packageName: String, val name: String, val category: String, val mark: String)

object SourceCatalog {
    // Package identities verified against official Play listings; payload support is NOT yet validated.
    val candidates = listOf(
        SourceApp("com.schoolbell_e.schoolbell_e", "학교종이", "학교 소식", "종"),
        SourceApp("com.ewut.allealimi", "e알리미", "학교 소식", "e"),
        SourceApp("com.iscreammedia.app.hiclass.android", "하이클래스", "학교 소식", "Hi"),
        SourceApp("com.vaultmicro.kidsnote", "키즈노트", "교육기관 소식", "K"),
        SourceApp("com.classnote.android.release", "클래스노트", "학원 소식", "C")
    )
    private val fixture = SourceApp("kr.mom.probe.fixture", "알림 테스트 도우미", "개발용 합성 알림", "T")
    fun installed(context: Context): List<SourceApp> = (candidates + if (BuildConfig.DEBUG) listOf(fixture) else emptyList()).mapNotNull { candidate ->
        try {
            val info = context.packageManager.getApplicationInfo(candidate.packageName, PackageManager.ApplicationInfoFlags.of(0))
            val actualLabel = context.packageManager.getApplicationLabel(info).toString().trim()
            candidate.copy(name = actualLabel.ifBlank { candidate.name })
        } catch (_: PackageManager.NameNotFoundException) { null }
    }

    fun missing(context: Context): List<SourceApp> = candidates.filter { candidate ->
        try { context.packageManager.getApplicationInfo(candidate.packageName, PackageManager.ApplicationInfoFlags.of(0)); false }
        catch (_: PackageManager.NameNotFoundException) { true }
    }
    fun label(packageName: String): String = (candidates + fixture).find { it.packageName == packageName }?.name ?: "이전에 연결한 앱"
}
