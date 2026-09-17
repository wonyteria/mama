package kr.mom.probe.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.mom.probe.MainActivity
import kr.mom.probe.connector.ConnectorRepository
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel

/** Fixed non-personal fixture. This Activity does not exist in release builds. */
class DebugValidationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val probe = ProbeRepository.get(this@DebugValidationActivity)
            val connectors = ConnectorRepository.get(this@DebugValidationActivity)
            val ok = probe.acceptConsent() &&
                probe.saveChild("검증 아이", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY) &&
                connectors.saveChild("검증 아이", "성남정자초등학교", 2)
            startActivity(Intent(this@DebugValidationActivity, MainActivity::class.java).putExtra("debugSetupResult", ok))
            finish()
        }
    }
}
