package com.edgeai.chatappv2;

import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Rect;
import android.widget.ToggleButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Conversation extends AppCompatActivity {

    ArrayList<ChatMessage> messages = new ArrayList<ChatMessage>(1000);

    private static final String cWelcomeMessage = "Hi! How can I help you?";
    public static final String cConversationActivityKeyHtpConfig = "htp_config_path";
    public static final String cConversationActivityKeyModelName = "model_dir_name";
    
    private MainViewModel mainViewModel;
    private ImageButton recordButton;
    private View recordingIndicator;
    private ProgressBar loadingProgress;
    private TextView transcriptionStatus;
    private Button loadWhisperButton;
    private TextView userInput;
    private ImageButton sendButton;
    private String htpExtensionsDir;
    private String modelName;
    private GenieWrapper genieWrapper;
    private String TAG = "ChatApp";
    // Toggle for real-time TTS
    private boolean enableRealtimeTts = false;
    private ToggleButton toggleRealtimeTts;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    isGranted -> {
                        for (Map.Entry<String, Boolean> entry : isGranted.entrySet()) {
                            String permission = entry.getKey();
                            boolean granted = entry.getValue();
                            if (android.Manifest.permission.RECORD_AUDIO.equals(permission)) {
                                if (granted) {
                                    Log.d(TAG, "Record permission is granted");
                                } else {
                                    Log.d(TAG, "Record permission is not granted");
                                    Toast.makeText(this, "Record permission is required for voice input", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    }
            );

    private void requestRecordPermission() {
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Optionally show rationale to the user explaining why the permission is needed
            Log.d(TAG, "Displaying permission rationale");
        } else {
            // Directly request the permission
            requestPermissionLauncher.launch(new String[]{android.Manifest.permission.RECORD_AUDIO});
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hide the action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Set window soft input mode to adjust pan
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        setContentView(R.layout.chat);
        
        // Get the singleton instance of MainViewModel
        mainViewModel = MainViewModel.getInstance();
        
        // Initialize TTS settings from preferences
        PreferenceHelper preferenceHelper = new PreferenceHelper(this);
        TtsEngine.speed = preferenceHelper.getSpeed();
        TtsEngine.speakerId = preferenceHelper.getSpeakerId();
        
        // Initialize UI components
        RecyclerView recyclerView = findViewById(R.id.chat_recycler_view);
        MessageRecyclerViewAdapter adapter = new MessageRecyclerViewAdapter(this, messages);
        recyclerView.setAdapter(adapter);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);

        recordButton = findViewById(R.id.record_button);
        recordingIndicator = findViewById(R.id.recording_indicator);
        transcriptionStatus = findViewById(R.id.transcription_status);
        loadingProgress = findViewById(R.id.loading_progress);
        loadWhisperButton = findViewById(R.id.load_whisper_button);
        userInput = findViewById(R.id.user_input);
        sendButton = findViewById(R.id.send_button);
        
        // Find the TTS settings button and set up listener
        ImageButton ttsSettingsButton = findViewById(R.id.tts_settings_button);
        ttsSettingsButton.setOnClickListener(v -> {
            // Show TTS settings dialog
            TtsSettingsDialog settingsDialog = new TtsSettingsDialog(this);
            settingsDialog.show();
        });
        
        // Find the toggle button and set up listener
        try {
            toggleRealtimeTts = findViewById(R.id.toggle_realtime_tts);
            toggleRealtimeTts.setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableRealtimeTts = isChecked;
                // Update appearance based on state
                if (isChecked) {
                    toggleRealtimeTts.setTextColor(getResources().getColor(R.color.colorAccent));
                } else {
                    toggleRealtimeTts.setTextColor(getResources().getColor(R.color.black));
                }
                Toast.makeText(this, 
                    isChecked ? "Real-time TTS enabled" : "Real-time TTS disabled", 
                    Toast.LENGTH_SHORT).show();
            });
            
            // Set initial text color
            toggleRealtimeTts.setTextColor(getResources().getColor(R.color.black));
        } catch (Exception e) {
            Log.e(TAG, "Error setting up toggle TTS button: " + e.getMessage());
        }

        try {
            // Make QNN libraries discoverable
            String nativeLibPath = getApplicationContext().getApplicationInfo().nativeLibraryDir;
            Os.setenv("ADSP_LIBRARY_PATH", nativeLibPath, true);
            Os.setenv("LD_LIBRARY_PATH", nativeLibPath, true);

            // Get information from MainActivity regarding
            //  - Model to run
            //  - HTP config to use
            Bundle bundle = getIntent().getExtras();
            if (bundle == null) {
                Log.e("ChatApp", "Error getting additional info from bundle.");
                Toast.makeText(this, "Unexpected error observed. Exiting app.", Toast.LENGTH_LONG).show();
                finish();
            }

            htpExtensionsDir = bundle.getString(cConversationActivityKeyHtpConfig);
            modelName = bundle.getString(cConversationActivityKeyModelName);
            String externalCacheDir = this.getExternalCacheDir().getAbsolutePath().toString();
            String modelDir = Paths.get(externalCacheDir, "models", modelName).toString();

            // Load Model
            genieWrapper = new GenieWrapper(modelDir, htpExtensionsDir);
            Log.i("ChatApp", modelName + " Loaded.");

            messages.add(new ChatMessage(cWelcomeMessage, MessageSender.BOT));
            
            // Setup Whisper model loading button
            setupWhisperButton();
            
            // Setup recording UI
            setupRecordingUI();
            requestRecordPermission();
            setupKeyboardVisibilityListener();

            // Get response from Bot once user message is sent
            sendButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendUserMessage();
                }
            });

        } catch (Exception e) {
            Log.e("ChatApp", "Error during conversation with Chatbot: " + e.toString());
            Toast.makeText(this, "Unexpected error observed. Exiting app.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    
    private void setupWhisperButton() {
        loadWhisperButton.setOnClickListener(view -> {
            loadWhisperButton.setEnabled(false);
            loadWhisperButton.setText(R.string.loading_whisper);

            mainViewModel.loadWhisperModel(this);

            // Observe Whisper model state
            mainViewModel.getWhisperModelState().observe(this, state -> {
                switch (state) {
                    case LOADED:
                        loadWhisperButton.setText(R.string.whisper_loaded);
                        recordButton.setEnabled(true);
                        Toast.makeText(this, "Whisper model loaded successfully", Toast.LENGTH_SHORT).show();
                        break;
                    case ERROR:
                        loadWhisperButton.setText(R.string.retry_whisper);
                        loadWhisperButton.setEnabled(true);
                        Toast.makeText(this, "Failed to load Whisper model", Toast.LENGTH_SHORT).show();
                        break;
                }
            });
            
            // Observe loading progress messages
            mainViewModel.getLoadingProgress().observe(this, progressMessage -> {
                if (!progressMessage.isEmpty()) {
                    transcriptionStatus.setText(progressMessage);
                }
            });
        });
    }
    
    private void setupRecordingUI() {
        recordButton.setOnClickListener(v -> {
            if (mainViewModel.getStatusState().getValue() == InferenceState.IDLE) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        // Observe transcription state changes
        mainViewModel.getStatusState().observe(this, state -> {
            switch (state) {
                case IDLE:
                    recordButton.setImageResource(R.drawable.ic_mic);
                    recordingIndicator.setVisibility(View.GONE);
                    transcriptionStatus.setVisibility(View.GONE);
                    loadingProgress.setVisibility(View.GONE);
                    userInput.setEnabled(true);
                    break;
                case RECORDING:
                    recordButton.setImageResource(R.drawable.ic_stop);
                    recordingIndicator.setVisibility(View.VISIBLE);
                    transcriptionStatus.setVisibility(View.VISIBLE);
                    loadingProgress.setVisibility(View.GONE);
                    transcriptionStatus.setText("Recording...");
                    userInput.setEnabled(false);
                    break;
                case TRANSCRIBING:
                    recordButton.setImageResource(R.drawable.ic_mic);
                    recordingIndicator.setVisibility(View.GONE);
                    transcriptionStatus.setVisibility(View.VISIBLE);
                    loadingProgress.setVisibility(View.VISIBLE);
                    transcriptionStatus.setText("Transcribing...");
                    userInput.setEnabled(false);
                    break;
                case LOADING:
                    recordButton.setEnabled(false);
                    transcriptionStatus.setVisibility(View.VISIBLE);
                    transcriptionStatus.setText("Loading model...");
                    loadingProgress.setVisibility(View.VISIBLE);
                    userInput.setEnabled(false);
                    break;
            }
        });
        
        // Observe recording duration
        mainViewModel.getRecordingDuration().observe(this, duration -> {
            if (mainViewModel.getStatusState().getValue() == InferenceState.RECORDING) {
                int minutes = duration / 60;
                int seconds = duration % 60;
                transcriptionStatus.setText(String.format(Locale.ENGLISH, "Recording... %02d:%02d", minutes, seconds));
            }
        });

        // Observe transcription result and time together
        mainViewModel.getTranscriptionResult().observe(this, result -> {
            if (!result.isEmpty()) {
                userInput.setText(result);
                // Get the transcription time from the ViewModel
                double transcriptionTime = mainViewModel.getTranscriptionTime().getValue().doubleValue();
                
                // Automatically send the transcribed message with transcription time
                sendVoiceTranscribedMessage(result, transcriptionTime);
            }
        });

        // Observe transcription time for UI updates
        mainViewModel.getTranscriptionTime().observe(this, time -> {
            if (time.doubleValue() > 0) {
                transcriptionStatus.setText(String.format(Locale.ENGLISH, 
                    "Transcribed in %.3f seconds", time.doubleValue() / 1000.0));
                
                // If we have a transcription result already, update the UI
                String currentResult = mainViewModel.getTranscriptionResult().getValue();
                if (currentResult != null && !currentResult.isEmpty() && 
                    userInput.getText().toString().equals(currentResult)) {
                    // This ensures we have both the result and time before sending
                    userInput.setText(currentResult);
                }
            }
        });
    }
    
    private void setupKeyboardVisibilityListener() {
        final View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private final Rect r = new Rect();
            private boolean wasKeyboardOpen = false;

            @Override
            public void onGlobalLayout() {
                rootView.getWindowVisibleDisplayFrame(r);
                int heightDiff = rootView.getRootView().getHeight() - r.height();
                
                // If height difference is more than 200 pixels, consider keyboard as open
                boolean isKeyboardOpen = heightDiff > 200;
                
                if (isKeyboardOpen != wasKeyboardOpen) {
                    wasKeyboardOpen = isKeyboardOpen;
                    
                    RecyclerView recyclerView = findViewById(R.id.chat_recycler_view);
                    if (recyclerView != null && recyclerView.getAdapter() != null && recyclerView.getAdapter().getItemCount() > 0) {
                        if (isKeyboardOpen) {
                            // Keyboard opened - scroll to bottom
                            recyclerView.postDelayed(() -> {
                                recyclerView.smoothScrollToPosition(recyclerView.getAdapter().getItemCount() - 1);
                            }, 300); // Small delay to ensure layout is complete
                        } else {
                            // Keyboard closed - adjust scroll position if needed
                            LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                            if (layoutManager != null) {
                                int lastVisiblePosition = layoutManager.findLastVisibleItemPosition();
                                int itemCount = recyclerView.getAdapter().getItemCount();
                                
                                // If we're near the bottom, scroll to bottom
                                if (lastVisiblePosition >= itemCount - 3) {
                                    recyclerView.smoothScrollToPosition(itemCount - 1);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    private void startRecording() {
        mainViewModel.startRecording();
    }

    private void stopRecording() {
        mainViewModel.stopRecording();
        mainViewModel.runInference();
    }
    
    /**
     * Sends a message that was transcribed from voice input
     * @param userInputText The transcribed text
     * @param transcriptionTime The time it took to transcribe in milliseconds
     */
    private void sendVoiceTranscribedMessage(String userInputText, double transcriptionTime) {
        if (userInputText != null && !userInputText.trim().isEmpty()) {
            // Reset user message box
            userInput.setText("");

            // Insert user message in the conversation with transcription time
            RecyclerView recyclerView = findViewById(R.id.chat_recycler_view);
            MessageRecyclerViewAdapter adapter = (MessageRecyclerViewAdapter) recyclerView.getAdapter();
            
            // Create a voice transcription message with the transcription time
            assert adapter != null;
            adapter.addVoiceTranscriptionMessage(userInputText, transcriptionTime);
            adapter.notifyItemInserted(adapter.getItemCount() - 1);

            // Scroll to bottom after adding user message
            recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);

            ExecutorService service = Executors.newSingleThreadExecutor();
            service.execute(new Runnable() {
                @Override
                public void run() {
                    final long startTime = System.currentTimeMillis();
                    
                    // Track if real-time TTS is enabled
                    final boolean isRealtimeTtsEnabled = enableRealtimeTts;
                    
                    // Flag to track if we've started speaking yet
                    final boolean[] isSpeakingStarted = {false};
                    
                    // Track accumulated text for speech
                    final StringBuilder speechBuffer = new StringBuilder();
                    
                    genieWrapper.getResponseForPrompt(userInputText, new StringCallback() {
                        @Override
                        public void onNewString(String response) {
                            runOnUiThread(() -> {
                                // Update the last item in the adapter
                                adapter.updateBotMessage(response, startTime);                        
                                adapter.notifyItemChanged(messages.size() - 1);
                                
                                // Always scroll to bottom when receiving new message content
                                recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);
                                
                                // Handle real-time TTS if enabled
                                if (isRealtimeTtsEnabled) {
                                    // Accumulate text for speech
                                    speechBuffer.append(response);
                                    
                                    // If we haven't started speaking and have enough text, start speaking
                                    if (!isSpeakingStarted[0] && speechBuffer.length() > 30) {
                                        isSpeakingStarted[0] = true;
                                        
                                        // Start real-time TTS playback
                                        String textToSpeak = speechBuffer.toString();
                                        adapter.startStreamingTTSPlayback(textToSpeak);
                                        
                                        // Clear buffer since we're now in continuous playback mode
                                        speechBuffer.setLength(0);
                                    }
                                    // If we're already speaking and have a new chunk, send it for continuous processing
                                    else if (isSpeakingStarted[0] && !response.isEmpty()) {
                                        // Append new text to TTS
                                        adapter.appendStreamingTTS(response);
                                    }
                                }
                            });
                        }
                    });
                }
            });
        }
    }
    
    private void sendUserMessage() {
        String userInputText = userInput.getText().toString();
        if (!userInputText.trim().isEmpty()) {
            // Reset user message box
            userInput.setText("");

            // Insert user message in the conversation (regular text input, not voice)
            RecyclerView recyclerView = findViewById(R.id.chat_recycler_view);
            MessageRecyclerViewAdapter adapter = (MessageRecyclerViewAdapter) recyclerView.getAdapter();
            assert adapter != null;
            adapter.addMessage(new ChatMessage(userInputText, MessageSender.USER));
            adapter.notifyItemInserted(adapter.getItemCount() - 1);

            // Scroll to bottom after adding user message
            recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);

            ExecutorService service = Executors.newSingleThreadExecutor();
            service.execute(new Runnable() {
                @Override
                public void run() {
                    final long startTime = System.currentTimeMillis();
                    
                    // Track if real-time TTS is enabled
                    final boolean isRealtimeTtsEnabled = enableRealtimeTts;
                    
                    // Flag to track if we've started speaking yet
                    final boolean[] isSpeakingStarted = {false};
                    
                    // Track accumulated text for speech
                    final StringBuilder speechBuffer = new StringBuilder();
                    
                    genieWrapper.getResponseForPrompt(userInputText, new StringCallback() {
                        @Override
                        public void onNewString(String response) {
                            runOnUiThread(() -> {
                                // Update the last item in the adapter
                                adapter.updateBotMessage(response, startTime);                        
                                adapter.notifyItemChanged(messages.size() - 1);
                                
                                // Always scroll to bottom when receiving new message content
                                recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);
                                
                                // Handle real-time TTS if enabled
                                if (isRealtimeTtsEnabled) {
                                    // Accumulate text for speech
                                    speechBuffer.append(response);
                                    
                                    // If we haven't started speaking and have enough text, start speaking
                                    if (!isSpeakingStarted[0] && speechBuffer.length() > 30) {
                                        isSpeakingStarted[0] = true;
                                        
                                        // Start real-time TTS playback
                                        String textToSpeak = speechBuffer.toString();
                                        adapter.startStreamingTTSPlayback(textToSpeak);
                                        
                                        // Clear buffer since we're now in continuous playback mode
                                        speechBuffer.setLength(0);
                                    }
                                    // If we're already speaking and have a new chunk, send it for continuous processing
                                    else if (isSpeakingStarted[0] && !response.isEmpty()) {
                                        // Append new text to TTS
                                        adapter.appendStreamingTTS(response);
                                    }
                                }
                            });
                        }
                    });
                }
            });
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release the Whisper model when the activity is destroyed
        mainViewModel.releaseModel();

        // Stop any ongoing TTS
        TtsEngine.INSTANCE.stopMediaPlayer();
        
        // Clean up the adapter resources
        RecyclerView recyclerView = findViewById(R.id.chat_recycler_view);
        if (recyclerView != null && recyclerView.getAdapter() != null) {
            MessageRecyclerViewAdapter adapter = (MessageRecyclerViewAdapter) recyclerView.getAdapter();
            // Stop any streaming TTS
            adapter.stopStreamingTts();
            // Regular cleanup
            adapter.cleanup();
        }
    }
}
