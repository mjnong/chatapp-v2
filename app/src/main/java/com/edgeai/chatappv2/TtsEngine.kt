package com.edgeai.chatappv2

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.edgeai.chatappv2.MainActivity.Companion.TAG
import kotlinx.coroutines.channels.Channel

/**
 * Playback mode for TtsEngine
 */
enum class TtsPlaybackMode {
    REAL_TIME,   // Use AudioTrack for real-time playback during generation
    FILE_BASED   // Save to file first, then play with MediaPlayer
}

object TtsEngine {
    var tts: OfflineTts? = null

    // Mediaplayer
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    
    // Callback for when playback completes
    private var playbackCompletionListener: (() -> Unit)? = null
    
    // Flag to track if TTS is already running
    private var isTtsRunning = false
    
    // Current playback mode
    private var playbackMode: TtsPlaybackMode = TtsPlaybackMode.FILE_BASED

    // https://en.wikipedia.org/wiki/ISO_639-3
    // Example:
    // eng for English,
    // deu for German
    // cmn for Mandarin
    var lang: String? = null

    private val trackStateStopped: MutableState<Boolean> = mutableStateOf(false)
    private val samplesChannel: MutableState<Channel<FloatArray>> = mutableStateOf(Channel<FloatArray>())

    var sample: Channel<FloatArray>
        get() = samplesChannel.value
        set(value) {
            samplesChannel.value = value
        }

    var trackState: Boolean
        get() = trackStateStopped.value
        set(value) {
            trackStateStopped.value = value
        }

    @JvmField
    var speed: Float = 1.0F

    @JvmField
    var speakerId: Int = 0

    private var modelDir: String? = null
    private var modelName: String? = null
    private var acousticModelName: String? = null // for matcha tts
    private var vocoder: String? = null // for matcha tts
    private var voices: String? = null // for kokoro
    private var ruleFsts: String? = null
    private var ruleFars: String? = null
    private var lexicon: String? = null
    private var dataDir: String? = null
    private var dictDir: String? = null
    private var assets: AssetManager? = null

    init {
        // The purpose of such a design is to make the CI test easier
        // Please see
        // https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/apk/generate-tts-apk-script.py
        //
        // For VITS -- begin
        modelName = null
        // For VITS -- end

        // For Matcha -- begin
        acousticModelName = null
        vocoder = null
        // For Matcha -- end

        // For Kokoro -- begin
        voices = null
        // For Kokoro -- end

        modelDir = null
        ruleFsts = null
        ruleFars = null
        lexicon = null
        dataDir = null
        dictDir = null
        lang = null

        // Example 10
        // kokoro-int8-multi-lang-v1_1
         modelDir = "kokoro-int8-multi-lang-v1_1"
         modelName = "model.int8.onnx"
         voices = "voices.bin"
         dataDir = "$modelDir/espeak-ng-data"
         dictDir = "$modelDir/dict"
         lexicon = "$modelDir/lexicon-us-en.txt,$modelDir/lexicon-zh.txt"
         lang = "eng"
         ruleFsts = "$modelDir/phone-zh.fst,$modelDir/date-zh.fst,$modelDir/number-zh.fst"
        //
        // This model supports many languages, e.g., English, Chinese, etc.
        // We set lang to eng here.
    }

    /**
     * Set the playback mode to use
     */
    fun setPlaybackMode(mode: TtsPlaybackMode) {
        playbackMode = mode
        Log.d(TAG, "TTS playback mode set to: $mode")
    }

    /**
     * Set a listener to be notified when playback completes
     */
    fun setPlaybackCompletionListener(listener: () -> Unit) {
        playbackCompletionListener = listener
    }

    fun trackPause() {
        if (playbackMode == TtsPlaybackMode.REAL_TIME && track != null) {
            track!!.pause()
        } else {
            Log.d(TAG, "trackPause called but ignored in ${playbackMode.name} mode")
        }
    }

    fun trackFlush() {
        if (playbackMode == TtsPlaybackMode.REAL_TIME && track != null) {
            track!!.flush()
        } else {
            Log.d(TAG, "trackFlush called but ignored in ${playbackMode.name} mode")
        }
    }

    fun trackStop() {
        if (playbackMode == TtsPlaybackMode.REAL_TIME && track != null) {
            track!!.stop()
            track = null
        } else {
            Log.d(TAG, "trackStop called but ignored in ${playbackMode.name} mode")
        }
    }

    fun trackPlay() {
        if (playbackMode == TtsPlaybackMode.REAL_TIME) {
            if (track == null) {
                initAudioTrack()
            }
            
            if (track != null && track!!.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track!!.play()
            } else if (track != null) {
                Log.i(TAG, "Track is already playing")
            }
        } else {
            Log.d(TAG, "trackPlay called but ignored in ${playbackMode.name} mode")
        }
    }

    fun trackWrite(data: FloatArray, offset: Int, size: Int) {
        if (playbackMode == TtsPlaybackMode.REAL_TIME && track != null) {
            track!!.write(data, offset, size, AudioTrack.WRITE_BLOCKING)
        } else {
            Log.d(TAG, "trackWrite called but ignored in ${playbackMode.name} mode")
        }
    }

    fun stopMediaPlayer() {
        try {
            Log.d(TAG, "Stopping media player")
            player?.setOnCompletionListener(null)
            player?.stop()
            player?.release()
            player = null
            
            // Reset the running flag
            isTtsRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        }
    }

    fun onClickPlay(context: Context, applicationPath: String) {
        // If TTS is already running, don't start it again
        if (isTtsRunning) {
            Log.i(TAG, "TTS is already running, not starting another instance")
            return
        }
        
        if (playbackMode == TtsPlaybackMode.FILE_BASED) {
            try {
                Log.d(TAG, "Starting media player")
                isTtsRunning = true
                
                val filename = "$applicationPath/generated.wav"
                // Make sure any previous instance is stopped first
                stopMediaPlayer()
                
                player = MediaPlayer.create(
                    context,
                    Uri.fromFile(File(filename))
                )
                
                if (player == null) {
                    Log.e(TAG, "Failed to create MediaPlayer")
                    isTtsRunning = false
                    return
                }
                
                // Set completion listener
                player?.setOnCompletionListener {
                    Log.d(TAG, "Media playback completed")
                    stopMediaPlayer()
                    playbackCompletionListener?.invoke()
                }
                
                player?.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onClickPlay: ${e.message}")
                isTtsRunning = false
            }
        } else {
            Log.d(TAG, "onClickPlay called but ignored in ${playbackMode.name} mode")
        }
    }

    fun onCLickStop() {
        Log.d(TAG, "onClickStop called")
        trackState = true
        
        if (playbackMode == TtsPlaybackMode.REAL_TIME) {
            trackPause()
            trackFlush()
            trackStop()
        } else {
            stopMediaPlayer()
        }
        
        // Notify listeners that playback has stopped
        playbackCompletionListener?.invoke()
    }

    fun createTts(context: Context) {
        Log.i(TAG, "Init Next-gen Kaldi TTS")
        if (tts == null) {
            initTts(context)
        }
    }

    private fun initTts(context: Context) {
        assets = context.assets

        if (dataDir != null) {
            val newDir = copyDataDir(context, dataDir!!)
            dataDir = "$newDir/$dataDir"
        }

        if (dictDir != null) {
            val newDir = copyDataDir(context, dictDir!!)
            dictDir = "$newDir/$dictDir"
            if (ruleFsts == null) {
                ruleFsts = "$modelDir/phone.fst,$modelDir/date.fst,$modelDir/number.fst"
            }
        }

        val config = getOfflineTtsConfig(
            modelDir = modelDir!!,
            modelName = modelName ?: "",
            acousticModelName = acousticModelName ?: "",
            vocoder = vocoder ?: "",
            voices = voices ?: "",
            lexicon = lexicon ?: "",
            dataDir = dataDir ?: "",
            dictDir = dictDir ?: "",
            ruleFsts = ruleFsts ?: "",
            ruleFars = ruleFars ?: ""
        )

        // Load saved settings
        speed = PreferenceHelper(context).getSpeed()
        speakerId = PreferenceHelper(context).getSpeakerId()

        tts = OfflineTts(assetManager = assets, config = config)
        Log.i(TAG, "Start to initialize AudioTrack")
        
        if (playbackMode == TtsPlaybackMode.REAL_TIME) {
            initAudioTrack()
        } else {
            Log.i(TAG, "Skipping AudioTrack initialization in ${playbackMode.name} mode")
        }
        
        Log.i(TAG, "Finish initializing TTS")
    }

    private fun copyDataDir(context: Context, dataDir: String): String {
        Log.i(TAG, "data dir is $dataDir")
        copyAssets(context, dataDir)

        val newDataDir = context.getExternalFilesDir(null)!!.absolutePath
        Log.i(TAG, "newDataDir: $newDataDir")
        return newDataDir
    }

    private fun copyAssets(context: Context, path: String) {
        val assets: Array<String>?
        try {
            assets = context.assets.list(path)
            if (assets!!.isEmpty()) {
                copyFile(context, path)
            } else {
                val fullPath = "${context.getExternalFilesDir(null)}/$path"
                val dir = File(fullPath)
                dir.mkdirs()
                for (asset in assets.iterator()) {
                    val p: String = if (path == "") "" else "$path/"
                    copyAssets(context, p + asset)
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to copy $path. $ex")
        }
    }

    private fun copyFile(context: Context, filename: String) {
        try {
            val istream = context.assets.open(filename)
            val newFilename = context.getExternalFilesDir(null).toString() + "/" + filename
            val ostream = FileOutputStream(newFilename)
            // Log.i(TAG, "Copying $filename to $newFilename")
            val buffer = ByteArray(1024)
            var read = 0
            while (read != -1) {
                ostream.write(buffer, 0, read)
                read = istream.read(buffer)
            }
            istream.close()
            ostream.flush()
            ostream.close()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to copy $filename, $ex")
        }
    }

    private fun initAudioTrack() {
        if (playbackMode != TtsPlaybackMode.REAL_TIME) {
            Log.i(TAG, "AudioTrack initialization skipped in ${playbackMode.name} mode")
            return
        }
        
        if (tts == null) {
            Log.e(TAG, "TTS not initialized, can't create AudioTrack")
            return
        }
        
        val sampleRate = tts!!.sampleRate()
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        Log.i(TAG, "sampleRate: $sampleRate, buffLength: $bufLength")

        val attr = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        
        Log.i(TAG, "AudioTrack initialized successfully")
    }
}