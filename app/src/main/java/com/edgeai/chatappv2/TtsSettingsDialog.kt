package com.edgeai.chatappv2

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dialog to manage TTS settings
 */
class TtsSettingsDialog(private val context: Context) {
    private lateinit var dialog: Dialog
    private lateinit var speedValueText: TextView
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speakerContainer: LinearLayout
    private lateinit var speakerSpinner: Spinner
    private lateinit var testTextInput: EditText
    private lateinit var testPlayButton: Button
    private lateinit var testStopButton: Button
    private lateinit var saveButton: Button
    
    private val preferenceHelper = PreferenceHelper(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var testTtsJob: Job? = null
    
    private var numSpeakers = 1
    private var currentSpeed = 1.0f
    private var currentSpeakerId = 0
    
    fun show() {
        // Create the dialog
        val builder = AlertDialog.Builder(context)
        val dialogView = LayoutInflater.from(context).inflate(R.layout.tts_settings_dialog, null)
        builder.setView(dialogView)
        
        // Get dialog views
        speedValueText = dialogView.findViewById(R.id.speed_value_text)
        speedSeekBar = dialogView.findViewById(R.id.speed_seekbar)
        speakerContainer = dialogView.findViewById(R.id.speaker_selection_container)
        speakerSpinner = dialogView.findViewById(R.id.speaker_spinner)
        testTextInput = dialogView.findViewById(R.id.test_text_input)
        testPlayButton = dialogView.findViewById(R.id.test_play_button)
        testStopButton = dialogView.findViewById(R.id.test_stop_button)
        saveButton = dialogView.findViewById(R.id.save_settings_button)
        
        // Create and show the dialog
        dialog = builder.create()
        dialog.setCancelable(true)
        dialog.show()
        
        // Initialize UI with current values
        initializeDialogUI()
        
        // Set up event listeners
        setupEventListeners()
    }
    
    private fun initializeDialogUI() {
        // Get saved settings
        currentSpeed = preferenceHelper.getSpeed()
        currentSpeakerId = preferenceHelper.getSpeakerId()
        
        // Update speed controls
        val progress = (currentSpeed * 100).toInt()
        speedSeekBar.progress = progress
        updateSpeedText(currentSpeed)
        
        // Check if multiple speakers are available
        if (TtsEngine.tts != null) {
            numSpeakers = TtsEngine.tts!!.numSpeakers()
            if (numSpeakers > 1) {
                // Show speaker selection controls
                speakerContainer.visibility = View.VISIBLE
                
                // Set up speaker spinner
                val speakerOptions = (0 until numSpeakers).map { "Speaker $it" }.toTypedArray()
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, speakerOptions)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                speakerSpinner.adapter = adapter
                
                // Set current speaker
                if (currentSpeakerId < numSpeakers) {
                    speakerSpinner.setSelection(currentSpeakerId)
                } else {
                    speakerSpinner.setSelection(0)
                    currentSpeakerId = 0
                }
            } else {
                // Hide speaker selection if only one speaker is available
                speakerContainer.visibility = View.GONE
            }
        } else {
            // Hide speaker selection if TTS is not initialized
            speakerContainer.visibility = View.GONE
            Toast.makeText(context, "TTS engine not fully initialized", Toast.LENGTH_SHORT).show()
        }
        
        // Set sample text
        val sampleText = getSampleText(TtsEngine.lang ?: "eng")
        testTextInput.setText(sampleText)
    }
    
    private fun setupEventListeners() {
        // Speed seek bar listener
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentSpeed = progress / 100f
                if (currentSpeed < 0.2f) currentSpeed = 0.2f
                if (currentSpeed > 3.0f) currentSpeed = 3.0f
                updateSpeedText(currentSpeed)
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Not needed
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Not needed
            }
        })
        
        // Speaker spinner listener
        speakerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                currentSpeakerId = position
            }
            
            override fun onNothingSelected(parent: AdapterView<*>) {
                // Not needed
            }
        }
        
        // Test play button
        testPlayButton.setOnClickListener {
            val testText = testTextInput.text.toString()
            if (testText.isNotEmpty()) {
                stopTestTts() // Stop any current TTS
                playTestTts(testText)
            }
        }
        
        // Test stop button
        testStopButton.setOnClickListener {
            stopTestTts()
        }
        
        // Save button
        saveButton.setOnClickListener {
            // Save settings
            TtsEngine.speed = currentSpeed
            TtsEngine.speakerId = currentSpeakerId
            
            // Save to preferences
            preferenceHelper.setSpeed(currentSpeed)
            preferenceHelper.setSpeakerId(currentSpeakerId)
            
            // Show confirmation
            Toast.makeText(context, "TTS settings saved", Toast.LENGTH_SHORT).show()
            
            // Close dialog
            dialog.dismiss()
        }
    }
    
    private fun updateSpeedText(speed: Float) {
        speedValueText.text = String.format(Locale.getDefault(), "%.1f×", speed)
    }
    
    private fun playTestTts(text: String) {
        if (TtsEngine.tts == null) {
            Toast.makeText(context, "TTS engine not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Update UI
        testPlayButton.isEnabled = false
        testStopButton.isEnabled = true
        
        // Setup TTS with current settings
        TtsEngine.speed = currentSpeed
        TtsEngine.speakerId = currentSpeakerId
        TtsEngine.setPlaybackMode(TtsPlaybackMode.FILE_BASED)
        
        // Generate and play test audio in background
        testTtsJob = scope.launch {
            try {
                // Reset TTS state
                TtsEngine.trackState = false
                
                // Create dummy callback
                val dummyCallback: (FloatArray) -> Int = { _ -> 1 }
                
                // Generate audio
                val audio = TtsEngine.tts!!.generateWithCallback(
                    text = text,
                    sid = currentSpeakerId,
                    speed = currentSpeed,
                    callback = dummyCallback
                )
                
                // Save the generated audio file
                val filename = context.filesDir.absolutePath + "/generated.wav"
                val ok = audio.samples.isNotEmpty() && audio.save(filename)
                
                // Play the audio
                withContext(Dispatchers.Main) {
                    if (ok) {
                        // Play the audio
                        TtsEngine.onClickPlay(context, context.filesDir.absolutePath)
                        
                        // Set completion listener
                        TtsEngine.setPlaybackCompletionListener {
                            testPlayButton.isEnabled = true
                            testStopButton.isEnabled = false
                        }
                    } else {
                        Toast.makeText(context, "Failed to generate test audio", Toast.LENGTH_SHORT).show()
                        testPlayButton.isEnabled = true
                        testStopButton.isEnabled = false
                    }
                }
            } catch (e: Exception) {
                Log.e(MainActivity.TAG, "Error in test TTS: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_SHORT).show()
                    testPlayButton.isEnabled = true
                    testStopButton.isEnabled = false
                }
            }
        }
    }
    
    private fun stopTestTts() {
        testTtsJob?.cancel()
        testTtsJob = null
        TtsEngine.onCLickStop()
        
        // Update UI
        testPlayButton.isEnabled = true
        testStopButton.isEnabled = false
    }
    
    /**
     * Get sample text based on language
     */
    private fun getSampleText(lang: String): String {
        return when (lang) {
            "cmn" -> "你好，这是一个文字转语音的测试。我希望您能听清楚我说的话。"
            "deu" -> "Hallo, dies ist ein Test der Text-zu-Sprache-Funktionalität. Ich hoffe, Sie können mich klar verstehen."
            else -> "Hello, this is a test of the text to speech functionality. I hope you can understand me clearly."
        }
    }
} 