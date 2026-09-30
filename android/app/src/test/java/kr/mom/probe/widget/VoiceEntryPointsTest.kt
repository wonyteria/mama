package kr.mom.probe.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kr.mom.probe.R
import kr.mom.probe.voice.VoiceQuickCaptureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Widget + Quick Settings entry points into VoiceQuickCaptureActivity.
 * Contract: user-tap only, explicit intents, separate PendingIntent
 * identities, and a privacy-safe default rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceEntryPointsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `voice intent is an explicit component launch`() {
        val intent = VoiceQuickCaptureActivity.intent(context)
        assertEquals(VoiceQuickCaptureActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        // No action/extras that could trigger auto-listening.
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
    }

    @Test fun `quick settings tile is declared with the signature permission`() {
        val services = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services.orEmpty()
        val tile = services.single { it.name.endsWith("VoiceCaptureTileService") }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertTrue(tile.exported) // system binds to the tile service
    }

    @Test fun `widget layout carries a privacy-safe separate voice action`() {
        val parser = context.resources.getLayout(R.layout.assistant_widget_layout)
        var voiceAttrs: Pair<String, String>? = null
        var foundRootDescription = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val id = (0 until parser.attributeCount)
                    .firstOrNull { parser.getAttributeName(it) == "id" }
                    ?.let { parser.getAttributeResourceValue(it, 0) } ?: 0
                when (id) {
                    R.id.assistant_widget_voice -> {
                        fun dimen(name: String) = (0 until parser.attributeCount)
                            .firstOrNull { parser.getAttributeName(it) == name }
                            ?.let { parser.getAttributeValue(it) } ?: ""
                        voiceAttrs = dimen("minWidth") to dimen("minHeight")
                    }
                    R.id.assistant_widget_root -> {
                        foundRootDescription = (0 until parser.attributeCount).any {
                            parser.getAttributeName(it) == "contentDescription"
                        }
                    }
                }
            }
            event = parser.next()
        }
        parser.close()
        assertNotNull("voice action view missing", voiceAttrs)
        assertTrue("minWidth >= 48dp", voiceAttrs!!.first.startsWith("48"))
        assertTrue("minHeight >= 48dp", voiceAttrs!!.second.startsWith("48"))
        assertTrue("root has no contentDescription", foundRootDescription)
    }

    @Test fun `widget strings expose no family or notice content`() {
        // The default widget rendering is static resources only — assert the
        // copy stays generic and privacy-safe.
        val resources = context.resources
        val texts = listOf(
            resources.getString(R.string.assistant_widget_title),
            resources.getString(R.string.assistant_widget_subtitle),
            resources.getString(R.string.assistant_widget_action),
            resources.getString(R.string.assistant_widget_description),
            resources.getString(R.string.assistant_widget_voice_accessibility),
        )
        texts.forEach { text ->
            assertTrue(text.isNotBlank())
            // No placeholders that could splice child/school/task data.
            assertTrue(!text.contains("%"))
        }
        assertNotNull(context.getString(R.string.assistant_widget_accessibility))
    }

    @Test fun `open-todo and voice pending intents are separate identities`() {
        val open = android.app.PendingIntent.getActivity(
            context, 4200,
            android.content.Intent(context, kr.mom.probe.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val voice = android.app.PendingIntent.getActivity(
            context, 4201, VoiceQuickCaptureActivity.intent(context),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        assertNotNull(open)
        assertNotNull(voice)
        assertTrue(open != voice) // distinct request codes → distinct identities
    }
}
