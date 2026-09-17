package kr.mom.probe.agent

import android.app.Application

import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import kr.mom.probe.calendar.CalendarAppHandler
import kr.mom.probe.calendar.CalendarCommandRecord
import kr.mom.probe.calendar.CalendarCommandStatus
import kr.mom.probe.calendar.CalendarDestination
import kr.mom.probe.reminder.ExternalAlarmHandler
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.ProbeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScheduleStateCodecTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val crypto = TestSavedStateCrypto

    @Test
    fun pendingScheduleRequestRoundTripsThroughEncryptedSavedState() {
        val payload = SchedulePayload("민서 비밀 상담", millis(2026, 10, 17, 14, 0), millis(2026, 10, 17, 15, 0), zone.id, false, 60)
        val request = PendingScheduleRequest("request-rotation", CalendarCreateCommand("10월 17일 민서 비밀 상담 일정 추가", payload), 77L)
        val encrypted = encryptScheduleSavedState(encodePendingScheduleRequest(request).toString(), "test-pending", crypto)

        assertFalse(encrypted.contains("민서"))
        assertFalse(encrypted.contains("비밀"))
        assertFalse(encrypted.contains("request-rotation"))

        val restored = decodePendingScheduleRequest(org.json.JSONObject(decryptScheduleSavedState(encrypted, "test-pending", crypto)))
        assertEquals(request, restored)
    }

    @Test
    fun receiptAndHandlerStateRoundTripWithoutPlaintext() {
        val destination = CalendarDestination(7, "개인", "mom@example.com", "LOCAL", "owner@example.com")
        val payload = SchedulePayload("치과", millis(2026, 10, 17, 14, 0), millis(2026, 10, 17, 15, 0), zone.id, false, 60)
        val token = scheduleConsentGeneration(consentedSettings(123_456L))!!
        val record = CalendarCommandRecord("receipt-1", "raw calendar text", payload, destination, CalendarCommandStatus.SAVED, eventId = 12, consentGeneration = token)
        val calendarHandler = CalendarAppHandler("com.example.calendar", "CalendarActivity", "가족 캘린더")
        val alarmHandler = ExternalAlarmHandler("com.example.clock", "AlarmActivity", "엄마 시계")

        val encryptedRecord = encryptScheduleSavedState(encodeCalendarCommandRecord(record).toString(), "test-record", crypto)
        val encryptedCalendarHandler = encryptScheduleSavedState(encodeCalendarAppHandler(calendarHandler).toString(), "test-calendar-handler", crypto)
        val encryptedAlarmHandler = encryptScheduleSavedState(encodeExternalAlarmHandler(alarmHandler).toString(), "test-alarm-handler", crypto)

        listOf(encryptedRecord, encryptedCalendarHandler, encryptedAlarmHandler).forEach { saved ->
            assertFalse(saved.contains("mom@example.com"))
            assertFalse(saved.contains("치과"))
            assertFalse(saved.contains("가족"))
            assertFalse(saved.contains("엄마"))
        }
        val decodedRecord = decodeCalendarCommandRecord(org.json.JSONObject(decryptScheduleSavedState(encryptedRecord, "test-record", crypto)))
        assertEquals(record, decodedRecord)
        assertEquals(token, decodedRecord.consentGeneration)
        assertEquals(calendarHandler, decodeCalendarAppHandler(org.json.JSONObject(decryptScheduleSavedState(encryptedCalendarHandler, "test-calendar-handler", crypto))))
        assertEquals(alarmHandler, decodeExternalAlarmHandler(org.json.JSONObject(decryptScheduleSavedState(encryptedAlarmHandler, "test-alarm-handler", crypto))))
    }


    @Test
    fun permissionResultDuringLoadingKeepsRestoredRequestUntilReady() {
        var pendingRequest: PendingScheduleRequest? = PendingScheduleRequest(
            "same-uuid-after-rotation",
            AlarmRequestCommand("내일 오전 7시 알람 맞춰줘", SchedulePayload("알람", millis(2026, 10, 17, 7, 0), millis(2026, 10, 17, 7, 1), zone.id, true, 0)),
            88L,
        )
        var queuedPermissionResult: Boolean? = null

        if (shouldQueueSchedulePermissionResult(ready = false)) {
            queuedPermissionResult = true
        } else {
            pendingRequest = null
        }

        assertEquals("same-uuid-after-rotation", pendingRequest?.requestId)
        assertEquals(true, queuedPermissionResult)
        assertEquals(true, restoredScheduleGenerationMatches(ready = true, allowed = true, restoredGeneration = pendingRequest!!.generation, currentGeneration = 88L))
        assertEquals(false, restoredScheduleGenerationMatches(ready = true, allowed = true, restoredGeneration = pendingRequest.generation, currentGeneration = 89L))
    }


    @Test
    fun scheduleConsentGenerationIsPositiveDurableAndResetInvalidated() {
        val settings = consentedSettings(consentAt = 123_456L)
        val firstProcessToken = scheduleConsentGeneration(settings)
        val recreatedProcessToken = scheduleConsentGeneration(settings.copy())

        assertTrue(firstProcessToken!! > 0L)
        assertEquals(firstProcessToken, recreatedProcessToken)
        assertEquals(firstProcessToken, scheduleConsentGeneration(settings.copy(childName = "다른 이름")))
        assertNotEquals(firstProcessToken, scheduleConsentGeneration(consentedSettings(consentAt = 789_000L)))
        assertNull(scheduleConsentGeneration(settings.copy(consent = false)))
        assertNull(scheduleConsentGeneration(settings.copy(consentAt = null)))
    }
    private object TestSavedStateCrypto : ScheduleSavedStateCrypto {
        private val key = SecretKeySpec(ByteArray(16) { index -> (index + 1).toByte() }, "AES")
        private val random = SecureRandom()

        override fun encrypt(value: String, context: String): ByteArray {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            return byteArrayOf(1) + iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        }

        override fun decrypt(bytes: ByteArray, context: String): String {
            require(bytes.size >= 29 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
        }
    }

    private fun consentedSettings(consentAt: Long): ProbeSettings = ProbeSettings(
        consent = true,
        onboardingDone = true,
        consentAt = consentAt,
        consentVersion = ProbeRules.CONSENT_VERSION,
    )
    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}





