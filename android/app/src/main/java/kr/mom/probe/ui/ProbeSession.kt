package kr.mom.probe.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kr.mom.probe.data.ProbeRecord

/** Raw review data survives rotation in RAM; it is never written into saved-instance bundles. */
class ProbeSession : ViewModel() {
    var exportSnapshot by mutableStateOf(emptyList<ProbeRecord>())
    var exportPayload by mutableStateOf<String?>(null)
    var exportIds by mutableStateOf(emptySet<String>())
    fun clearExport() { exportSnapshot = emptyList(); exportPayload = null; exportIds = emptySet() }
}
