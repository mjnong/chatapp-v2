package com.edgeai.chatappv2

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Paths
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "ChatApp V2"
    }

    private val ttsViewModel: TtsViewModel by viewModels()

    private lateinit var mainViewModel: MainViewModel
    private lateinit var loadLlamaButton: Button

    private class LibraryLoader {
        // List of libraries to load
        private val libraries = arrayOf(
            "avutil",                // Base library for multimedia utilities
            "swresample",            // For audio resampling (depends on avutil)
            "avcodec",               // For multimedia decoding (depends on avutil)
            "avformat",              // For multimedia format handling (depends on avcodec)
            // QNN libraries
            "QnnSystem",             // Core system library for QNN
            "QnnGpu",                // GPU support for QNN
            "QnnDsp",                // DSP support for QNN
            "QnnDspV66Stub",
            "QnnHtp",
            "QnnHtpPrepare",
            "QnnHtpV68Stub",
            "QnnHtpV69Stub",
            "QnnHtpV73Stub",
            "QnnHtpV75Stub",
            "QnnHtpV79Stub",
            "QnnTFLiteDelegate",
            "tensorflowlite",
            "tensorflowlite_gpu_delegate",  // GPU support for TensorFlow Lite

            // ONNX
            "onnxruntime",
            "sherpa-onnx-jni",

            // Native WhisperKit libraries
            "whisperkit",
            "native-whisper",
            // Add our native-helper library
            "native-helper",
            "app"
        )

        // Loads all the native libraries
        fun loadAllLibraries() {
            for (lib in libraries) {
                try {
                    System.loadLibrary(lib)
                    println("Loaded library: lib$lib.so")
                } catch (e: UnsatisfiedLinkError) {
                    println("Failed to load library: lib$lib.so")
                    e.printStackTrace()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { entry ->
            val permission = entry.key
            val isGranted = entry.value
            when (permission) {
                android.Manifest.permission.RECORD_AUDIO -> {
                    if (isGranted) {
                        Log.d(TAG, "Record permission is granted")
                    } else {
                        Log.d(TAG, "Record permission is not granted")
                    }
                }
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE -> {
                    if (isGranted) {
                        Log.d(TAG, "Write permission is granted")
                    } else {
                        Log.d(TAG, "Write permission is not granted")
                    }
                }
            }
        }
    }

    private fun requestRecordPermission() {
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Optionally show rationale to the user explaining why the permission is needed
            Log.d(TAG, "Displaying permission rationale")
        } else {
            // Directly request the permission
            requestPermissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
        }
    }

    /**
     * Copies provided assets to output path
     *
     * @param inputAssetRelPath relative path to asset from asset root
     * @param outputPath output path to copy assets to
     * @throws IOException
     * @throws NullPointerException
     */
    @Throws(IOException::class, NullPointerException::class)
    private fun copyAssetsDir(inputAssetRelPath: String, outputPath: String) {
        val outputAssetPath = File(Paths.get(outputPath, inputAssetRelPath).toString())

        val subAssetList = assets.list(inputAssetRelPath)
        if (subAssetList?.isEmpty() == true) {
            // If file already present, skip copy.
            if (!outputAssetPath.exists()) {
                copyFile(inputAssetRelPath, outputAssetPath)
            }
            return
        }

        // Input asset is a directory, create directory if not present already.
        if (!outputAssetPath.exists()) {
            outputAssetPath.mkdirs()
        }
        
        subAssetList?.forEach { subAssetName ->
            // Copy content of sub-directory
            val inputSubAssetPath = Paths.get(inputAssetRelPath, subAssetName).toString()
            // NOTE: Not to modify output path, relative asset path is being updated.
            copyAssetsDir(inputSubAssetPath, outputPath)
        }
    }

    /**
     * Copies provided input file asset into output asset file
     *
     * @param inputFilePath relative file path from asset root directory
     * @param outputAssetFile output file to copy input asset file into
     */
    @Throws(IOException::class)
    private fun copyFile(inputFilePath: String, outputAssetFile: File) {
        assets.open(inputFilePath).use { inputStream ->
            FileOutputStream(outputAssetFile).use { outputStream ->
                val buffer = ByteArray(1024 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set ADSP_LIBRARY_PATH as early as possible
        setAdspLibraryPath()

        // Hide the action bar
        supportActionBar?.hide()

        val libraryLoader = LibraryLoader()
        libraryLoader.loadAllLibraries()
        
        // Check audio system
        checkAudioSystem()
        
        // Initialize TTS Engine
        Log.i(TAG, "Start to initialize TTS")
        TtsEngine.createTts(this, BuildConfig.SOCKET_ID)
        Log.i(TAG, "TTS Engine initialized")

        try {
            // Get SoC model from build properties
            // As of now, only Snapdragon Gen 3 and 8 Elite is supported.
            val supportedSocModel = HashMap<String, String>().apply {
                put("SM8750", "qualcomm-snapdragon-8-elite.json")
                put("SM8650", "qualcomm-snapdragon-8-gen3.json")
                put("QCS8550", "qualcomm-snapdragon-8-gen2.json")
            }

            val socModel = android.os.Build.SOC_MODEL
            if (!supportedSocModel.containsKey(socModel)) {
                val errorMsg = "Unsupported device. Please ensure you have one of the following device to run the ChatApp: $supportedSocModel"
                Log.e("ChatApp", errorMsg)
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Copy assets to External cache
            //  - <assets>/models
            //      - has list of models with tokenizer.json, genie-config.json and model binaries
            //  - <assets>/htp_config/
            //      - has SM8750.json and SM8650.json and picked up according to device SOC Model at runtime.
            val externalDir = externalCacheDir?.absolutePath ?: throw NullPointerException("External cache directory is null")
            
            try {
                // Copy assets to External cache if not already present
                copyAssetsDir("models", externalDir)
                copyAssetsDir("htp_config", externalDir)
            } catch (e: IOException) {
                val errorMsg = "Error during copying model asset to external storage: $e"
                Log.e("ChatApp", errorMsg)
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            val htpConfigPath = Paths.get(externalDir, "htp_config", supportedSocModel[socModel])
            val htpExtConfigPath = htpConfigPath.toString()

            setContentView(R.layout.activity_main)
            
//            // Set window soft input mode to adjust resize
//            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            
            // Get the singleton instance of MainViewModel
            mainViewModel = MainViewModel.getInstance()
            mainViewModel.initialize(this)

            // Initialize UI components - only what's needed for MainActivity
            loadLlamaButton = findViewById(R.id.load_llama_button)
            
            setupModelLoadingButtons(htpExtConfigPath)
            requestRecordPermission()

        } catch (e: Exception) {
            val errorMsg = "Unexpected error occurred while running ChatApp: $e"
            Log.e("ChatApp", errorMsg)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupModelLoadingButtons(htpConfigPath: String) {
        val htpExtConfigPath = htpConfigPath

        // Setup LLAMA model button to enable advanced chat
        loadLlamaButton.setOnClickListener {
            loadLlamaButton.isEnabled = false
            loadLlamaButton.text = "Loading LLAMA..."

            mainViewModel.loadLlamaModel()

            // Observe LLAMA model state
            mainViewModel.llamaModelState.observe(this) { state ->
                when (state) {
                    ModelState.LOADED -> {
                        loadLlamaButton.text = "LLAMA Loaded"
                        
                        // Launch the Conversation activity with required parameters
                        val intent = Intent(this@MainActivity, Conversation::class.java).apply {
                            putExtra(Conversation.cConversationActivityKeyHtpConfig, htpExtConfigPath)
                            putExtra(Conversation.cConversationActivityKeyModelName, "llama3_2_3b")
                        }
                        startActivity(intent)
                    }
                    ModelState.ERROR -> {
                        loadLlamaButton.text = "Retry Loading LLAMA"
                        loadLlamaButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Failed to load LLAMA model. Please try again.", Toast.LENGTH_LONG).show()
                    }
                    else -> { /* Handle other states if needed */ }
                }
            }
        }
    }

    /**
     * Set ADSP_LIBRARY_PATH environment variable to the native library directory
     * This is critical for components that need to find native libraries at runtime
     */
    private fun setAdspLibraryPath() {
        try {
            val success = NativeHelper.setAdspLibraryPath(this)
            if (success) {
                val path = NativeHelper.getAdspLibraryPath()
                Log.i(TAG, "ADSP_LIBRARY_PATH set to: $path")
                
                // In debug builds, show a quick toast to confirm it's set
                if (BuildConfig.DEBUG) {
                    Toast.makeText(this, "ADSP_LIBRARY_PATH set", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "Failed to set ADSP_LIBRARY_PATH")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting ADSP_LIBRARY_PATH: ${e.message}")
        }
    }

    /**
     * Check the audio system and volume levels
     */
    private fun checkAudioSystem() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volumePercent = (currentVolume.toFloat() / maxVolume.toFloat() * 100).toInt()
            
            Log.i(TAG, "Audio system check: Current volume: $currentVolume/$maxVolume ($volumePercent%)")
            
            // Check if volume is too low
            if (volumePercent < 30) {
                Log.w(TAG, "Audio volume is low, TTS might not be audible")
                // Show toast only if in debug mode
                if (BuildConfig.DEBUG) {
                    Toast.makeText(this, 
                        "Media volume is low ($volumePercent%). Consider increasing for better TTS.", 
                        Toast.LENGTH_LONG).show()
                }
            }
            
            // Check audio focus mode
            val mode = when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "UNKNOWN(${audioManager.mode})"
            }
            Log.i(TAG, "Audio mode: $mode")
            
            // Check if the device is muted
            val isMusicMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "Is music stream muted: $isMusicMuted")
            
            if (isMusicMuted) {
                Log.w(TAG, "Music stream is muted! TTS will not be audible")
                Toast.makeText(this, "Music stream is muted. TTS will not be audible.", Toast.LENGTH_LONG).show()
            }
            
            // Encourage volume increase if it's very low
            if (currentVolume == 0) {
                // Request volume increase
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking audio system: ${e.message}")
        }
    }
}
