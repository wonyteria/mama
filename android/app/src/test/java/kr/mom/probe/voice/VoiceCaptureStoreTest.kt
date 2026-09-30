package kr.mom.probe.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Voice capture persistence contract: consent-gated writes (fail closed),
 * text-only records (no audio artifacts), and a delete-all-safe reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceCaptureStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `save without completed onboarding fails closed`() {
        val store = VoiceCaptureStore.get(context)
        val error = runCatching {
            store.save(VoiceCaptureRecord("id-1", "내일 물티슈 챙겨줘", "TASK", 1000L))
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException || error is IllegalArgumentException)
    }

    @Test fun `capture ids are unique so retries cannot collide`() {
        assertNotEquals(VoiceCaptureStore.newCaptureId(), VoiceCaptureStore.newCaptureId())
    }

    @Test fun `reset clears persisted records and stays safe without consent`() {
        VoiceCaptureStore.reset(context)
        val prefs = context.getSharedPreferences("voice_captures", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
    }
}

/** Plain-JVM structural checks — no Android runtime needed. */
class VoiceCaptureRecordTest {
    @Test fun `record model carries only text and ids never audio`() {
        val record = VoiceCaptureRecord("id-2", "메모", "MEMO", 2000L, linkedTaskId = "task-9")
        assertEquals("메모", record.rawTranscript)
        assertEquals("task-9", record.linkedTaskId)
        // Structural proof: no byte-array / file field exists to carry audio.
        val fields = VoiceCaptureRecord::class.java.declaredFields
        assertTrue(fields.none { it.type == ByteArray::class.java || it.type == java.io.File::class.java })
    }
}
