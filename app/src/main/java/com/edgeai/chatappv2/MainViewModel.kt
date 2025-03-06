package com.edgeai.chatappv2

import android.content.Context
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgeai.chatappv2.asr.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import kotlin.system.measureTimeMillis

enum class InferenceState {
    IDLE,
    RECORDING,
    LOADING,
    TRANSCRIBING,
}

enum class ModelState {
    NOT_LOADED,
    LOADING,
    LOADED,
    ERROR
}

class MainViewModel : ViewModel() {
    private val _statusState = MutableLiveData<InferenceState>(InferenceState.IDLE)
    val statusState: LiveData<InferenceState> = _statusState

    private val _transcriptionResult = MutableLiveData<String>()
    val transcriptionResult: LiveData<String> = _transcriptionResult

    private val _transcriptionTime = MutableLiveData<Number>()
    val transcriptionTime: LiveData<Number> = _transcriptionTime
    
    private val _whisperModelState = MutableLiveData<ModelState>(ModelState.NOT_LOADED)
    val whisperModelState: LiveData<ModelState> = _whisperModelState
    
    private val _llamaModelState = MutableLiveData<ModelState>(ModelState.NOT_LOADED)
    val llamaModelState: LiveData<ModelState> = _llamaModelState
    
    // Recording duration counter
    private val _recordingDuration = MutableLiveData<Int>(0)
    val recordingDuration: LiveData<Int> = _recordingDuration
    
    // Loading progress
    private val _loadingProgress = MutableLiveData<String>("")
    val loadingProgress: LiveData<String> = _loadingProgress
    
    private var recordingTimer: Timer? = null

    // Companion object to hold singleton instance
    companion object {
        private var instance: MainViewModel? = null
        
        @JvmStatic
        fun getInstance(): MainViewModel {
            if (instance == null) {
                instance = MainViewModel()
            }
            return instance!!
        }
    }

    // Constant strings
    private val whisperFolderName = "openai_whisper-tiny"
    private val microphoneInputFileName = "MicInput.wav"
    private lateinit var modelDestFolder: File

    private val _waveFileNamesState = mutableStateOf<List<String>>(emptyList())
    val waveFileNamesState: State<List<String>> get() = _waveFileNamesState

    var waveFile: File? = null
    private var mRecorder: Recorder? = null

    var sdcardDataFolder: File? = null

    private var whisperKit: WhisperKitNative? = null
    private var nativeLibsDir: String? = null
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true
        
        val cacheDir = context.cacheDir
        Log.d("Cache", cacheDir.absolutePath)
        nativeLibsDir = context.applicationInfo.nativeLibraryDir
        copyDataToSdCardFolder(context)

        Log.d("FilesDir", context.filesDir.absolutePath)
        // Create audio_input_tiny directory for WhisperKit
        val tempDir = File(context.filesDir, "audio_input_tiny")
        if (!tempDir.exists()) {
            val created = tempDir.mkdirs()
            Log.d("WhisperKit", "Created temp directory: ${tempDir.absolutePath}, success: $created")
            
            // Explicitly set write permissions on the directory
            if (created) {
                tempDir.setWritable(true, false) // Make writable by all
                tempDir.setReadable(true, false) // Make readable by all
                tempDir.setExecutable(true, false) // Make executable by all
                
                // Also create a .nomedia file to prevent media scanning
                try {
                    File(tempDir, ".nomedia").createNewFile()
                } catch (e: IOException) {
                    Log.e("WhisperKit", "Failed to create .nomedia file", e)
                }
                
                Log.d("WhisperKit", "Set permissions on directory: ${tempDir.absolutePath}, " +
                      "writable: ${tempDir.canWrite()}, readable: ${tempDir.canRead()}, executable: ${tempDir.canExecute()}")
            } else {
                Log.e("WhisperKit", "Failed to create directory: ${tempDir.absolutePath}")
            }
        } else if (!tempDir.canWrite()) {
            // If directory exists but is not writable, try to set permissions
            val setWritable = tempDir.setWritable(true, false)
            Log.d("WhisperKit", "Set existing directory writable: ${tempDir.absolutePath}, success: $setWritable")
        }
    
        loadAudioFileNames(sdcardDataFolder!!.absolutePath)

        mRecorder = Recorder(context)

        _transcriptionResult.value = ""
        _transcriptionTime.value = 0.0
        
        waveFile = File(sdcardDataFolder!!.absolutePath + "/" + microphoneInputFileName)
    }
    
    fun loadWhisperModel(context: Context) {
        if (_whisperModelState.value == ModelState.LOADING || 
            _whisperModelState.value == ModelState.LOADED) return
            
        _whisperModelState.value = ModelState.LOADING
        _statusState.value = InferenceState.LOADING
        _loadingProgress.value = "Initializing..."
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure the temporary directory exists
                val tempDir = File(context.filesDir, "audio_input_tiny")
                if (!tempDir.exists()) {
                    val created = tempDir.mkdirs()
                    Log.d("WhisperKit", "Created temp directory: ${tempDir.absolutePath}, success: $created")
                    if (!created) {
                        throw IOException("Failed to create temporary directory for WhisperKit")
                    }
                }
                
                // Make sure the directory is writable
                if (!tempDir.canWrite()) {
                    throw IOException("Temporary directory is not writable: ${tempDir.absolutePath}")
                }

                _loadingProgress.postValue("Loading model files...")
    
                // Check and log current working directory
                try {
                    val currentDir = File(".")
                    Log.d("WhisperKit", "Current working directory: ${currentDir.absolutePath}")
                    
                    // Also log the canonical path which resolves any symbolic links
                    Log.d("WhisperKit", "Current working directory (canonical): ${currentDir.canonicalPath}")
                    
                    // List all files in current directory to help debug
                    val files = currentDir.listFiles()
                    if (files != null) {
                        Log.d("WhisperKit", "Files in current directory: ${files.joinToString { it.name }}")
                    } else {
                        Log.d("WhisperKit", "No files in current directory or cannot list files")
                    }
                } catch (e: Exception) {
                    Log.e("WhisperKit", "Error getting current working directory", e)
                }
             
                Log.d("WhisperKit", "Temp directory: ${tempDir.absolutePath}")
                Log.d("WhisperKit", "Model path: ${modelDestFolder.absolutePath}, Wave file path: ${waveFile!!.path}, Libs dir: ${nativeLibsDir}, Num threads: 4")

                // Simulate loading progress updates
                for (i in 1..5) {
                    delay(300)
                    _loadingProgress.postValue("Loading model components ($i/5)...")
                }

                whisperKit = WhisperKitNative(modelDestFolder.absolutePath, waveFile!!.path, ".", nativeLibsDir!!, 4)
                _loadingProgress.postValue("Finalizing model setup...")
                delay(300)
                
                withContext(Dispatchers.Main) {
                    _whisperModelState.value = ModelState.LOADED
                    _statusState.value = InferenceState.IDLE
                    _loadingProgress.value = ""
                }
            } catch (e: Exception) {
                Log.e("WhisperKit", "Error loading Whisper model", e)
                withContext(Dispatchers.Main) {
                    _whisperModelState.value = ModelState.ERROR
                    _statusState.value = InferenceState.IDLE
                    _loadingProgress.value = "Error: ${e.message}"
                }
            }
        }
    }
    
    fun loadLlamaModel() {
        if (_llamaModelState.value == ModelState.LOADING || 
            _llamaModelState.value == ModelState.LOADED) return
            
        _llamaModelState.value = ModelState.LOADING
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Simulate loading LLAMA model
                Thread.sleep(500)
                withContext(Dispatchers.Main) {
                    _llamaModelState.value = ModelState.LOADED
                }
            } catch (e: Exception) {
                Log.e("LlamaModel", "Error loading LLAMA model", e)
                withContext(Dispatchers.Main) {
                    _llamaModelState.value = ModelState.ERROR
                }
            }
        }
    }

    private fun copyDataToSdCardFolder(context: Context) {
        sdcardDataFolder = context.getExternalFilesDir(null)
        modelDestFolder = File(sdcardDataFolder!!.absolutePath + "/" + whisperFolderName)
        copyAssetsToSdcard(context, sdcardDataFolder!!, arrayOf( "wav", "m4a"))
        copyAssetsDirectory(context, whisperFolderName, modelDestFolder)
        Log.d("ASDF", "Assets directory copied to emulated storage")
    }

    fun runInference() {
        if (whisperKit == null || _whisperModelState.value != ModelState.LOADED) {
            Log.e("WhisperKit", "Whisper model not loaded")
            return
        }
        
        if (waveFile == null || !waveFile!!.exists() || waveFile!!.length().toInt() == 0) {
            Log.e("WhisperKit", "Wave file does not exist or is empty: ${waveFile?.absolutePath}")
            _statusState.value = InferenceState.IDLE
            return
        }
        
        _statusState.value = InferenceState.TRANSCRIBING
        viewModelScope.launch(Dispatchers.IO) {
            var transcriptOutput: String
            val time = measureTimeMillis {
                try {
                    Log.d("WhisperKit", "Starting transcription of file: ${waveFile!!.absolutePath}")
                    transcriptOutput = whisperKit!!.transcribe(waveFile!!.absolutePath)
                } catch (e: Exception) {
                    Log.e("WhisperKit", "Error during transcription", e)
                    transcriptOutput = ""
                }
            }

            withContext(Dispatchers.Main) {
                _transcriptionTime.value = time
                _transcriptionResult.value = transcriptOutput
                _statusState.value = InferenceState.IDLE
                Log.d("Transcript", "Transcription result: $transcriptOutput")
            }
        }
    }

    fun releaseModel() {
        whisperKit?.release()
    }

    fun startRecording() {
        if (_whisperModelState.value != ModelState.LOADED) {
            Log.e("WhisperKit", "Cannot record: Whisper model not loaded")
            return
        }
        
        _statusState.value = InferenceState.RECORDING
        _recordingDuration.value = 0
        
        // Start a timer to update recording duration every second
        recordingTimer = Timer()
        recordingTimer?.schedule(object : TimerTask() {
            override fun run() {
                viewModelScope.launch(Dispatchers.Main) {
                    _recordingDuration.value = (_recordingDuration.value ?: 0) + 1
                }
            }
        }, 1000, 1000)
        
        // Ensure the wave file path is set correctly
        Log.d("Recording", "Starting recording to file: ${waveFile?.absolutePath}")
        mRecorder?.setFilePath(waveFile?.absolutePath)
        mRecorder?.setFolderPath(sdcardDataFolder?.absolutePath)
        mRecorder?.start()
    }

    fun stopRecording() {
        Log.d("Recording", "Stopping recording")
        recordingTimer?.cancel()
        recordingTimer = null
        
        mRecorder?.stop()
        
        // Check if the wave file exists and has content
        if (waveFile?.exists() == true && waveFile?.length() ?: 0 > 0) {
            Log.d("Recording", "Wave file created successfully: ${waveFile?.absolutePath}, size: ${waveFile?.length()} bytes")
            _statusState.value = InferenceState.TRANSCRIBING
        } else {
            Log.e("Recording", "Wave file not created or empty: ${waveFile?.absolutePath}")
            _statusState.value = InferenceState.IDLE
        }
    }

    fun loadAudioFileNames(folderPath: String) {
        val folder = File(folderPath)
        if (folder.exists() && folder.isDirectory) {
            val waveFiles = folder.listFiles { file ->
                file.name.endsWith(".wav") || file.name.endsWith(".m4a")
            }
            _waveFileNamesState.value = waveFiles?.map { it.name } ?: emptyList()
        }
    }

    private fun copyAssetsToSdcard(context: Context, destFolder: File, extensions: Array<String>) {
        try {
            val assetManager = context.assets
            val files = assetManager.list("")
            if (files != null) {
                for (file in files) {
                    for (extension in extensions) {
                        if (file.endsWith(".$extension")) {
                            copyAsset(context, file, File(destFolder, file))
                            break
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("MainViewModel", "Error copying assets", e)
        }
    }

    private fun copyAsset(context: Context, assetName: String, destFile: File) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(assetName)
            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            inputStream.close()
            outputStream.flush()
            outputStream.close()
        } catch (e: IOException) {
            Log.e("MainViewModel", "Error copying asset $assetName", e)
        }
    }

    private fun copyAssetsDirectory(context: Context, assetsDirName: String, destDir: File) {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val assetManager = context.assets
            val files = assetManager.list(assetsDirName)
            if (files != null) {
                for (filename in files) {
                    val subDirFiles = assetManager.list("$assetsDirName/$filename")
                    if (subDirFiles != null && subDirFiles.isNotEmpty()) {
                        val newDir = File(destDir, filename)
                        copyAssetsDirectory(context, "$assetsDirName/$filename", newDir)
                    } else {
                        val inputStream = assetManager.open("$assetsDirName/$filename")
                        val outFile = File(destDir, filename)
                        val outputStream = FileOutputStream(outFile)
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                        inputStream.close()
                        outputStream.flush()
                        outputStream.close()
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("MainViewModel", "Error copying assets directory", e)
        }
    }
}