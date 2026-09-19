package kr.mom.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the installed QA app (kr.mom.probe.qa) through onboarding using
 * UiAutomation, because `input text` cannot inject Korean on Samsung IME.
 * The instrumentation process can interact with any window on screen.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseOnboardingDriverTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: android.app.UiAutomation get() = instrumentation.uiAutomation

    @Test
    fun driveReleaseOnboarding() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        launch("kr.mom.probe.qa", "kr.mom.probe.MainActivity")

        // 1. Welcome screen (only if consent not already given).
        waitForText("동의해요", timeoutMs = 6_000)?.let { consent ->
            var checkable: AccessibilityNodeInfo? = consent
            while (checkable != null && !checkable.isCheckable) checkable = checkable.parent
            if (checkable == null || !checkable.isChecked) clickRow(consent)
            scrollUntil("이 기기에서 시작", 8)
            clickText("이 기기에서 시작", timeoutMs = 5_000)
        }

        // 2. Child profile: name, school, level, grade.
        // Gate on the heading (always visible); the save button sits below
        // the fold and only enters the accessibility tree once scrolled up.
        if (waitForText("누구의 소식을", timeoutMs = 10_000) != null) {
            setEditText("아이 이름 또는 별칭", "이율")
            setEditText("학교 정식 이름", "성남정자초등학교")
            clickText("학교급 선택", timeoutMs = 5_000)
            clickText("초등", timeoutMs = 5_000, exact = true)
            clickText("학년 선택", timeoutMs = 5_000)
            clickText("2 학년", timeoutMs = 5_000, exact = true)
            scrollUntil("자녀 정보 저장", 10)
            clickText("자녀 정보 저장", timeoutMs = 5_000)

            // 3. Connections: enable NEIS public school info (completes onboarding).
            scrollUntil("나이스 학교정보", 10)
            val neis = waitForText("나이스 학교정보", timeoutMs = 15_000)
            assertNotNull("NEIS row not found", neis)
            clickRow(neis!!)
        }
        // Onboarding auto-completes once a site reports CONNECTED.
        // The system notification-permission dialog may sit on top; grant it.
        val deadline = System.currentTimeMillis() + 30_000
        var reached = false
        while (System.currentTimeMillis() < deadline && !reached) {
            findByText("허용", exact = true)?.let { clickRow(it) }
            reached = findByText("오늘") != null
            if (!reached) Thread.sleep(500)
        }
        if (!reached) {
            dumpVisibleTexts("onboarding-stuck")
            fail("onboarding did not reach today tab")
        }
    }

    private fun dumpVisibleTexts(tag: String) {
        val texts = mutableListOf<String>()
        val r = root() ?: run {
            android.util.Log.w(tag, "no active window root")
            return
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it.take(80) }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        android.util.Log.w(tag, "visible texts: ${texts.joinToString(" | ")}")
    }

    private fun launch(pkg: String, cls: String) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        instrumentation.context.startActivity(intent)
        Thread.sleep(2_000)
    }

    private fun scrollUntil(text: String, maxSwipes: Int) {
        repeat(maxSwipes) {
            if (findByText(text) != null) return
            swipeUp()
        }
    }

    private fun swipeUp() {
        val dm = instrumentation.context.resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val startY = h * 0.75f; val endY = h * 0.3f
        injectMotion(android.view.MotionEvent.ACTION_DOWN, w / 2, startY, 0)
        val steps = 12
        for (i in 1..steps) {
            injectMotion(android.view.MotionEvent.ACTION_MOVE, w / 2, startY + (endY - startY) * i / steps, i * 20L)
        }
        injectMotion(android.view.MotionEvent.ACTION_UP, w / 2, endY, steps * 20L + 20)
        Thread.sleep(600)
    }

    private fun root(): AccessibilityNodeInfo? {
        repeat(10) {
            automation.rootInActiveWindow?.let { return it }
            Thread.sleep(300)
        }
        return null
    }

    private fun findByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            val hit = if (exact) t == text || d == text
            else t?.contains(text) == true || d?.contains(text) == true
            if (hit) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun waitForText(text: String, timeoutMs: Long, exact: Boolean = false): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findByText(text, exact)?.let { return it }
            Thread.sleep(400)
        }
        return null
    }

    private fun clickText(text: String, timeoutMs: Long, exact: Boolean = false) {
        val n = waitForText(text, timeoutMs, exact)
        assertNotNull("node '$text' not found", n)
        clickRow(n!!)
    }

    private fun clickRow(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            tapAt(b.centerX().toFloat(), b.centerY().toFloat())
        }
        Thread.sleep(700)
    }

    private fun tapAt(x: Float, y: Float) {
        injectMotion(android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        injectMotion(android.view.MotionEvent.ACTION_UP, x, y, 60)
    }

    private fun injectMotion(action: Int, x: Float, y: Float, delayMs: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        val ev = android.view.MotionEvent.obtain(now, now + delayMs, action, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        automation.injectInputEvent(ev, true)
        ev.recycle()
        Thread.sleep(60)
    }

    private fun setEditText(labelHint: String, value: String) {
        val label = waitForText(labelHint, 8_000)
        assertNotNull("label '$labelHint' not found", label)
        // The EditText is the nearest editable sibling/descendant.
        var edit: AccessibilityNodeInfo? = findEditableNear(label!!)
        if (edit == null) {
            val r = root()
            val edits = mutableListOf<AccessibilityNodeInfo>()
            collectEditable(r, edits)
            edit = edits.firstOrNull { it.text?.toString().orEmpty().contains(labelHint) }
                ?: edits.firstOrNull()
        }
        assertNotNull("editable for '$labelHint' not found", edit)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        repeat(3) {
            edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Thread.sleep(300)
            if (edit!!.refresh() && edit!!.text?.toString() == value) return
            edit!!.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            edit!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(200)
        }
    }

    private fun collectEditable(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        if (n.isEditable || n.className == "android.widget.EditText") out.add(n)
        for (i in 0 until n.childCount) collectEditable(n.getChild(i), out)
    }

    private fun findEditableNear(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var scope: AccessibilityNodeInfo? = label.parent
        repeat(3) {
            val out = mutableListOf<AccessibilityNodeInfo>()
            collectEditable(scope, out)
            if (out.isNotEmpty()) return out.first()
            scope = scope?.parent
        }
        return null
    }
}
