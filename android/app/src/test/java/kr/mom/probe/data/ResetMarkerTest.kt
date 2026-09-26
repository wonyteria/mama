package kr.mom.probe.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResetMarkerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun markerSurvivesUntilCleanupCompletes() {
        assertFalse(ResetMarker.isPending(context))

        // Armed before the fallible chain: a death anywhere inside leaves it set.
        ResetMarker.begin(context)
        assertTrue(ResetMarker.isPending(context))

        // Cleared only after every cleanup step reports success.
        ResetMarker.finish(context)
        assertFalse(ResetMarker.isPending(context))
    }
}
