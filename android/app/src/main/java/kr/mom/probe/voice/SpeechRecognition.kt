package kr.mom.probe.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Test seam for platform speech recognition. All methods are main-thread
 * only, matching SpeechRecognizer's own contract — implementations throw
 * if invoked off the main thread rather than silently posting.
 */
interface RecognizerAdapter {
    fun setCallback(callback: VoiceRecognizerCallback)
    fun start()
    fun stop()
    fun cancel()
    fun destroy()
}

interface VoiceRecognizerCallback {
    fun onReady() {}
    fun onSpeechEnded() {}
    fun onPartial(text: String) {}
    fun onResult(text: String) {}
    fun onError(error: Int) {}
}

interface SpeechRecognizerFactory {
    fun isRecognitionAvailable(): Boolean
    fun isOnDeviceAvailable(): Boolean
    fun createOnDevice(): RecognizerAdapter
    fun createGeneric(): RecognizerAdapter
}

/** Production factory backed by android.speech.SpeechRecognizer. */
class SystemSpeechRecognizerFactory(context: Context) : SpeechRecognizerFactory {
    private val appContext = context.applicationContext

    override fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun isOnDeviceAvailable(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    override fun createOnDevice(): RecognizerAdapter {
        requireMainThread("createOnDevice")
        return SystemRecognizerAdapter(
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext), appContext.packageName,
        )
    }

    override fun createGeneric(): RecognizerAdapter {
        requireMainThread("createGeneric")
        return SystemRecognizerAdapter(
            SpeechRecognizer.createSpeechRecognizer(appContext), appContext.packageName,
        )
    }
}

private fun requireMainThread(call: String) {
    check(Looper.myLooper() == Looper.getMainLooper()) { "$call must run on the main thread" }
}

/**
 * Thin adapter over platform SpeechRecognizer. The listener is installed
 * via [setCallback] before [start]; SpeechRecognizer always delivers
 * callbacks on the main thread. No audio is ever written to disk — only
 * the text results the platform hands back.
 */
private class SystemRecognizerAdapter(
    private val recognizer: SpeechRecognizer,
    private val callingPackage: String,
) : RecognizerAdapter {

    override fun setCallback(callback: VoiceRecognizerCallback) {
        requireMainThread("setCallback")
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = callback.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = callback.onSpeechEnded()
            override fun onError(error: Int) = callback.onError(error)
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) callback.onPartial(text)
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                callback.onResult(text)
            }
        })
    }

    override fun start() {
        requireMainThread("start")
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
            },
        )
    }

    override fun stop() {
        requireMainThread("stop")
        recognizer.stopListening()
    }

    override fun cancel() {
        requireMainThread("cancel")
        recognizer.cancel()
    }

    override fun destroy() {
        requireMainThread("destroy")
        recognizer.destroy()
    }
}
