package com.edgeai.chatappv2

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.edgeai.chatappv2.MainActivity.Companion.TAG


class TtsService : TextToSpeechService() {
    override fun onCreate() {
        Log.i(TAG, "onCreate tts service")
        super.onCreate()

        // see https://github.com/Miserlou/Android-SDK-Samples/blob/master/TtsEngine/src/com/example/android/ttsengine/RobotSpeakTtsService.java#L68
        onLoadLanguage(TtsEngine.lang, "", "")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy tts service")
        super.onDestroy()
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onislanguageavailable
    override fun onIsLanguageAvailable(_lang: String?, _country: String?, _variant: String?): Int {
        val lang = _lang ?: ""

        if (lang == TtsEngine.lang) {
            return TextToSpeech.LANG_AVAILABLE
        }

        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(TtsEngine.lang!!, "", "")
    }

    // https://developer.android.com/reference/kotlin/android/speech/tts/TextToSpeechService#onLoadLanguage(kotlin.String,%20kotlin.String,%20kotlin.String)
    override fun onLoadLanguage(_lang: String?, _country: String?, _variant: String?): Int {
        Log.i(TAG, "onLoadLanguage: $_lang, $_country")
        val lang = _lang ?: ""

        return if (lang == TtsEngine.lang) {
            Log.i(TAG, "creating tts, lang :$lang")
            TtsEngine.createTts(application, BuildConfig.SOCKET_ID)
            TextToSpeech.LANG_AVAILABLE
        } else {
            Log.i(TAG, "lang $lang not supported, tts engine lang: ${TtsEngine.lang}")
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onStop() {}

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            return
        }
        val language = request.language
        val country = request.country
        val variant = request.variant
        val text = request.charSequenceText.toString()

        val ret = onIsLanguageAvailable(language, country, variant)
        if (ret == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.error()
            return
        }
        Log.i(TAG, "text: $text")
        val tts = TtsEngine.tts!!

        // Note that AudioFormat.ENCODING_PCM_FLOAT requires API level >= 24
        // callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_FLOAT, 1)

        callback.start(tts.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)

        if (text.isBlank() || text.isEmpty()) {
            callback.done()
            return
        }

        val ttsCallback: (FloatArray) -> Int = fun(floatSamples): Int {
            // convert FloatArray to ByteArray
            val samples = floatArrayToByteArray(floatSamples)
            val maxBufferSize: Int = callback.maxBufferSize
            var offset = 0
            while (offset < samples.size) {
                val bytesToWrite = Math.min(maxBufferSize, samples.size - offset)
                callback.audioAvailable(samples, offset, bytesToWrite)
                offset += bytesToWrite
            }

            // 1 means to continue
            // 0 means to stop
            return 1
        }

        Log.i(TAG, "text: $text")
        tts.generateWithCallback(
            text = text,
            sid = TtsEngine.speakerId,
            speed = TtsEngine.speed,
            callback = ttsCallback,
        )

        callback.done()
    }

    private fun floatArrayToByteArray(audio: FloatArray): ByteArray {
        // byteArray is actually a ShortArray
        val byteArray = ByteArray(audio.size * 2)
        for (i in audio.indices) {
            val sample = (audio[i] * 32767).toInt()
            byteArray[2 * i] = sample.toByte()
            byteArray[2 * i + 1] = (sample shr 8).toByte()
        }
        return byteArray
    }
}