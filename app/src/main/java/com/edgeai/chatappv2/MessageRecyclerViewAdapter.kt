package com.edgeai.chatappv2

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.edgeai.chatappv2.MainActivity.Companion.TAG
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

class MessageRecyclerViewAdapter(
    private val context: Context,
    private val messages: ArrayList<ChatMessage>
) : RecyclerView.Adapter<MessageRecyclerViewAdapter.MyViewHolder>() {

    // Track which message is currently being spoken
    private var currentlyPlayingPosition: Int = -1
    
    // Track the current TTS job to ensure we don't start multiple TTS operations
    private var currentTtsJob: Job? = null
    
    // Use a supervisor job so that if one coroutine fails, others can still run
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Variables to support streaming TTS during response generation
    private var streamingTtsBuffer = StringBuilder()
    private var isStreamingTts = false
    private var streamingSpeechJob: Job? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.chat_row, parent, false)
        return MyViewHolder(view)
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        val msg = messages[position]
        if (msg.isMessageFromUser()) {
            holder.mUserMessage.text = msg.message
            holder.mLeftChatLayout.visibility = View.GONE
            holder.mRightChatLayout.visibility = View.VISIBLE

            // Show transcription time for voice input messages
            if (msg.isFromVoiceInput() && msg.transcriptionTimeSeconds > 0) {
                holder.mTranscriptionTimingView.visibility = View.VISIBLE
                val timingText = String.format(Locale.ENGLISH, "Transcribed in %.2fs", msg.transcriptionTimeSeconds)
                holder.mTranscriptionTimingView.text = timingText
            } else {
                holder.mTranscriptionTimingView.visibility = View.GONE
            }

            holder.mTokenTimingView.visibility = View.GONE
            
            // Show play button for user messages if no message is currently playing
            // or if this is the currently playing message
            if (currentlyPlayingPosition == -1 || currentlyPlayingPosition == position) {
                holder.mUserTtsFab.visibility = View.VISIBLE
                // Set the appropriate icon based on play status
                if (currentlyPlayingPosition == position) {
                    holder.mUserTtsFab.setImageResource(R.drawable.ic_stop)
                } else {
                    holder.mUserTtsFab.setImageResource(R.drawable.ic_play_arrow)
                }
            } else {
                holder.mUserTtsFab.visibility = View.GONE
            }
            
            holder.mBotTtsFab.visibility = View.GONE
            
            // Set up user message TTS FAB click listener
            setupTtsFabListener(holder.mUserTtsFab, msg, position)
        } else {
            holder.mBotMessage.text = msg.message
            holder.mLeftChatLayout.visibility = View.VISIBLE
            holder.mRightChatLayout.visibility = View.GONE
            holder.mTranscriptionTimingView.visibility = View.GONE

            // Show timing information for messages that have started generating
            if (msg.timeToFirstTokenSeconds > 0) {
                holder.mTokenTimingView.visibility = View.VISIBLE
                val timingText = formatTimingText(msg)
                holder.mTokenTimingView.text = timingText

                // Style the timing view differently if message is still generating
                holder.mTokenTimingView.alpha = if (msg.totalTimeSeconds <= 0) 0.7f else 1.0f
            } else {
                holder.mTokenTimingView.visibility = View.GONE
            }
            
            // Show play button for bot messages if no message is currently playing
            // or if this is the currently playing message
            if (currentlyPlayingPosition == -1 || currentlyPlayingPosition == position) {
                holder.mBotTtsFab.visibility = View.VISIBLE
                // Set the appropriate icon based on play status
                if (currentlyPlayingPosition == position) {
                    holder.mBotTtsFab.setImageResource(R.drawable.ic_stop)
                } else {
                    holder.mBotTtsFab.setImageResource(R.drawable.ic_play_arrow)
                }
            } else {
                holder.mBotTtsFab.visibility = View.GONE
            }
            
            holder.mUserTtsFab.visibility = View.GONE
            
            // Set up bot message TTS FAB click listener
            setupTtsFabListener(holder.mBotTtsFab, msg, position)
        }
    }
    
    private fun setupTtsFabListener(fab: FloatingActionButton, message: ChatMessage, position: Int) {
        // Normal click for play/stop
        fab.setOnClickListener {
            if (currentlyPlayingPosition == position) {
                // This message is already playing, so stop it
                stopTts()
                currentlyPlayingPosition = -1
                notifyDataSetChanged() // Refresh all views to show play buttons again
            } else {
                // Cancel any ongoing TTS first
                if (currentlyPlayingPosition != -1) {
                    stopTts()
                }
                
                // Start playing this message
                currentlyPlayingPosition = position
                fab.setImageResource(R.drawable.ic_stop)
                
                // Hide all other FABs
                notifyDataSetChanged()
                
                // Play the message
                playTts(message)
            }
        }
        
        // Long press for playback mode selection
        fab.setOnLongClickListener {
            showPlaybackModeMenu(it, message, position)
            true
        }
    }
    
    @SuppressLint("DefaultLocale")
    private fun showPlaybackModeMenu(view: View, message: ChatMessage, position: Int) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.menu.add(0, 1, 0, "Play with MediaPlayer (Better quality)")
        popupMenu.menu.add(0, 2, 0, "Play in real-time (Lower latency)")

        popupMenu.setOnMenuItemClickListener { item ->
            // Cancel any ongoing TTS first
            if (currentlyPlayingPosition != -1) {
                stopTts()
            }
            
            // Start playing this message
            currentlyPlayingPosition = position
            val fab = view as FloatingActionButton
            fab.setImageResource(R.drawable.ic_stop)
            
            // Hide all other FABs
            notifyDataSetChanged()
            
            when (item.itemId) {
                1 -> { // File-based playback (MediaPlayer)
                    Toast.makeText(context, "Using MediaPlayer for better quality", Toast.LENGTH_SHORT).show()
                    playTts(message)
                    true
                }
                2 -> { // Real-time playback
                    Toast.makeText(context, "Using real-time playback for lower latency", Toast.LENGTH_SHORT).show()
                    generateAndStreamTTS(message)
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
    }
    
    @SuppressLint("DefaultLocale")
    private fun playTts(message: ChatMessage) {
        // Cancel any existing TTS job
        currentTtsJob?.cancel()
        
        // Reset TTS engine state
        TtsEngine.trackState = false
        
        // Set the playback mode to FILE_BASED to avoid double playback
        TtsEngine.setPlaybackMode(TtsPlaybackMode.FILE_BASED)
        
        // Launch a coroutine to generate audio (move heavy work off main thread)
        currentTtsJob = scope.launch {
            try {
                Log.d(TAG, "Starting TTS generation for message: ${message.message.take(20)}...")
                val timeSource = TimeSource.Monotonic
                val start = timeSource.markNow()

                // Dummy callback function that always continues
                val dummyCallback: (FloatArray) -> Int = { _ -> 1 }
                
                // Generate audio (this can be CPU intensive)
                val audio = TtsEngine.tts!!.generateWithCallback(
                    text = message.message,
                    sid = TtsEngine.speakerId,
                    speed = TtsEngine.speed,
                    callback = dummyCallback // Use dummy callback that does nothing
                )

                val elapsed = start.elapsedNow().inWholeMilliseconds.toFloat() / 1000
                val audioDuration = audio.samples.size / TtsEngine.tts!!.sampleRate().toFloat()
                val rtfInfo = String.format(
                    "Number of threads: %d\nElapsed: %.3f s\nAudio duration: %.3f s\nRTF: %.3f/%.3f = %.3f",
                    TtsEngine.tts!!.config.model.numThreads,
                    elapsed,
                    audioDuration,
                    elapsed,
                    audioDuration,
                    elapsed / audioDuration
                )
                Log.d(TAG, rtfInfo)

                // Save the generated audio file
                val filename = context.filesDir.absolutePath + "/generated.wav"
                val ok = audio.samples.isNotEmpty() && audio.save(filename)

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Log.d(TAG, "TTS generation successful, playing audio with MediaPlayer")
                        // Play the generated audio
                        TtsEngine.onClickPlay(context, context.filesDir.absolutePath)
                        
                        // Set up a callback for when playback completes
                        TtsEngine.setPlaybackCompletionListener {
                            if (currentlyPlayingPosition != -1) {
                                currentlyPlayingPosition = -1
                                notifyDataSetChanged() // Reset all FABs
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to generate audio file")
                        Toast.makeText(context, "Failed to generate audio", Toast.LENGTH_SHORT).show()
                        currentlyPlayingPosition = -1
                        notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating TTS: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_SHORT).show()
                    currentlyPlayingPosition = -1
                    notifyDataSetChanged()
                }
            }
        }
    }
    
    /**
     * Play TTS with real-time AudioTrack playback
     */
    private fun generateAndStreamTTS(message: ChatMessage) {
        // Cancel any existing TTS job
        currentTtsJob?.cancel()
        
        // Reset TTS engine state
        TtsEngine.trackState = false
        
        // Set the playback mode to REAL_TIME for real-time transcription
        TtsEngine.setPlaybackMode(TtsPlaybackMode.REAL_TIME)
        
        // Setup for real-time playback
        TtsEngine.trackPause()
        TtsEngine.trackFlush()
        TtsEngine.trackPlay()
        TtsEngine.sample = Channel<FloatArray>()
        
        // Launch a coroutine to write samples (on IO thread)
        scope.launch {
            try {
                for (sample in TtsEngine.sample) {
                    TtsEngine.trackWrite(sample, 0, sample.size)
                    if (TtsEngine.trackState) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing TTS samples: ${e.message}")
            }
        }
        
        // Launch a coroutine to generate audio with real-time callback
        currentTtsJob = scope.launch {
            try {
                Log.d(TAG, "Starting real-time TTS generation for message: ${message.message.take(20)}...")
                val timeSource = TimeSource.Monotonic
                val start = timeSource.markNow()
                
                // Use a function reference for the callback
                val callbackFn: (FloatArray) -> Int = { samples ->
                    realtimeCallback(samples)
                }
                
                // Generate audio with real-time callback
                val audio = TtsEngine.tts!!.generateWithCallback(
                    text = message.message,
                    sid = TtsEngine.speakerId,
                    speed = TtsEngine.speed,
                    callback = callbackFn
                )
                
                // Close the sample channel
                TtsEngine.sample.close()
                
                val elapsed = start.elapsedNow().inWholeMilliseconds.toFloat() / 1000
                val audioDuration = audio.samples.size / TtsEngine.tts!!.sampleRate().toFloat()
                Log.d(TAG, "Real-time TTS completed in ${elapsed}s (audio duration: ${audioDuration}s)")
                
                // Auto-complete playback after a slight delay to ensure all audio is played
                withContext(Dispatchers.Main) {
                    // Set up a timer to reset the UI after the audio completes
                    scope.launch {
                        kotlinx.coroutines.delay((audioDuration * 1000).toLong() + 500)
                        if (currentlyPlayingPosition != -1) {
                            currentlyPlayingPosition = -1
                            notifyDataSetChanged() // Reset all FABs
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in real-time TTS generation: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "TTS error: ${e.message}", Toast.LENGTH_SHORT).show()
                    currentlyPlayingPosition = -1
                    notifyDataSetChanged()
                }
            }
        }
    }
    
    // Callback for real-time TTS playback
    private fun realtimeCallback(samples: FloatArray): Int {
        if (!TtsEngine.trackState) {
            val samplesCopy = samples.copyOf()
            scope.launch {
                try {
                    TtsEngine.sample.send(samplesCopy)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending TTS samples: ${e.message}")
                }
            }
            return 1
        } else {
            TtsEngine.trackStop()
            Log.i(TAG, "TTS stopped by user")
            return 0
        }
    }
    
    private fun stopTts() {
        // Cancel the TTS job if it's running
        currentTtsJob?.cancel()
        currentTtsJob = null
        
        // Stop the TTS engine
        TtsEngine.onCLickStop()
    }

    private fun formatTimingText(msg: ChatMessage): String {
        val firstTokenTime = msg.timeToFirstTokenSeconds
        val totalTime = msg.totalTimeSeconds
        val tokenRate = msg.length / if (totalTime > 0) totalTime else 1.0
        return String.format(Locale.ENGLISH, "First token: %.2fs", firstTokenTime) +
                " • Total: " + String.format(Locale.ENGLISH, "%.2fs", totalTime) +
                " • " + String.format(Locale.ENGLISH, "%.1f chars/sec", tokenRate)
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
    }

    /**
     * Add a voice transcription message from the user
     * @param message The transcribed message text
     * @param transcriptionTimeMs The time it took to transcribe in milliseconds
     */
    fun addVoiceTranscriptionMessage(message: String, transcriptionTimeMs: Double) {
        val msg = ChatMessage(message, MessageSender.USER, transcriptionTimeMs, true)
        addMessage(msg)
    }

    /**
     * updateBotMessage: updates / inserts message on behalf of Bot
     * @param bot_message message to update or insert
     * @param startTime the time the message was sent
     */
    fun updateBotMessage(bot_message: String, startTime: Long) {
        var lastMessageFromBot = false

        if (messages.size > 1) {
            val lastMessage = messages.last()
            if (lastMessage.mSender == MessageSender.BOT) {
                lastMessageFromBot = true
            }
        } else {
            // Create a new message with first token time
            val firstTokenTime = System.currentTimeMillis() - startTime
            addMessage(ChatMessage(bot_message, MessageSender.BOT, firstTokenTime.toDouble()))
        }

        if (lastMessageFromBot) {
            val msg = messages.last()
            msg.message += bot_message
            msg.setMsToLastToken(startTime)
        } else {
            // Create a new message with first token time
            val firstTokenTime = System.currentTimeMillis() - startTime
            addMessage(ChatMessage(bot_message, MessageSender.BOT, firstTokenTime.toDouble()))
        }
    }
    
    // Call this method to clean up resources when adapter is no longer needed
    fun cleanup() {
        // Stop any ongoing streaming TTS
        stopStreamingTts()
        
        // Stop regular TTS
        stopTts()
        
        // Cancel any pending jobs
        currentTtsJob?.cancel()
        streamingSpeechJob?.cancel()
        
        // Clear references
        currentTtsJob = null
        streamingSpeechJob = null
        
        // Reset state
        isStreamingTts = false
        streamingTtsBuffer.clear()
    }

    /**
     * Start real-time TTS for a message being generated
     * @param initialText The initial text to speak
     */
    fun startStreamingTTSPlayback(initialText: String) {
        // Cancel any existing TTS or streaming job
        stopTts()
        streamingSpeechJob?.cancel()
        
        // Set streaming mode
        isStreamingTts = true
        streamingTtsBuffer.clear()
        streamingTtsBuffer.append(initialText)
        
        Log.d(TAG, "Starting live realtime TTS with initial text: ${initialText.take(50)}...")
        
        // Start TTS in real-time mode
        initStreamingTts()
    }
    
    /**
     * Append new text to an ongoing streaming TTS session
     * @param newText New text to append to the speech
     */
    fun appendStreamingTTS(newText: String) {
        if (!isStreamingTts) return
        
        // Only log if there's meaningful text to add
        if (newText.isNotEmpty() && newText.trim().isNotEmpty()) {
            Log.d(TAG, "Appending new text to streaming buffer: '${newText}'")
            
            // Append the new text to our buffer
            streamingTtsBuffer.append(newText)
        }
        
        // No need to restart TTS - the streaming is continuous
    }
    
    /**
     * Stop streaming TTS
     */
    fun stopStreamingTts() {
        if (!isStreamingTts) return
        
        Log.d(TAG, "Stopping streaming TTS")
        isStreamingTts = false
        streamingTtsBuffer.clear()
        
        // Cancel streaming job
        streamingSpeechJob?.cancel()
        streamingSpeechJob = null
        
        // Stop any ongoing TTS
        TtsEngine.trackState = true  // Signal to stop
        TtsEngine.trackStop()
        
        // Make sure channel is closed safely
        scope.launch {
            try {
                TtsEngine.sample.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing sample channel: ${e.message}")
            }
        }
    }
    
    /**
     * Initialize the streaming TTS system
     */
    private fun initStreamingTts() {
        // Reset TTS engine state
        TtsEngine.trackState = false
        
        // Set the playback mode to REAL_TIME for streaming
        TtsEngine.setPlaybackMode(TtsPlaybackMode.REAL_TIME)
        
        // Setup for real-time playback
        TtsEngine.trackPause()
        TtsEngine.trackFlush()
        TtsEngine.trackPlay()
        
        // Create a new buffered channel for this streaming session
        TtsEngine.sample = Channel<FloatArray>(capacity = Channel.BUFFERED)
        
        // Launch worker coroutine to process audio samples
        scope.launch {
            try {
                for (sample in TtsEngine.sample) {
                    TtsEngine.trackWrite(sample, 0, sample.size)
                    if (TtsEngine.trackState || !isStreamingTts) {
                        Log.d(TAG, "Breaking out of sample processing loop")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing TTS samples: ${e.message}")
            }
        }
        
        // Start the continuous generation process
        startContinuousStreamingTts()
    }
    
    /**
     * Start continuous streaming TTS that processes text as it's generated
     */
    private fun startContinuousStreamingTts() {
        streamingSpeechJob = scope.launch {
            try {
                while (isStreamingTts) {
                    val currentText = streamingTtsBuffer.toString()
                    // Extract complete sentence matches from the buffer
                    val matches = createCompleteSentenceMatches(currentText)
                    if (matches.isNotEmpty()) {
                        // Process each complete sentence
                        for (match in matches) {
                            val sentence = match.value.trim()
                            if (sentence.isNotEmpty()) {
                                Log.d(TAG, "Speaking sentence: '$sentence'")
                                processSentenceTts(sentence)
                            }
                        }
                        // Remove processed text up to the end of the last complete sentence
                        val lastMatch = matches.last()
                        streamingTtsBuffer.delete(0, lastMatch.range.last + 1)
                    }
                    // Small delay to prevent CPU thrashing
                    kotlinx.coroutines.delay(50)
                }
                Log.d(TAG, "Continuous streaming TTS loop ended")
            } catch (e: Exception) {
                Log.e(TAG, "Error in continuous streaming TTS: ${e.message}")
            }
        }
    }
    
    /**
     * Process a single sentence for TTS
     */
    private fun processSentenceTts(sentence: String) {
        if (!isStreamingTts) return
        
        try {
            // Create a simple stop check function for TTS generation
            val callbackFn: (FloatArray) -> Int = { samples ->
                if (!isStreamingTts || TtsEngine.trackState) {
                    0  // Stop
                } else {
                    // Process audio samples
                    val samplesCopy = samples.copyOf()
                    scope.launch {
                        try {
                            TtsEngine.sample.send(samplesCopy)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending samples: ${e.message}")
                        }
                    }
                    1  // Continue
                }
            }
            
            // Generate audio with callback
            TtsEngine.tts!!.generateWithCallback(
                text = sentence,
                sid = TtsEngine.speakerId,
                speed = TtsEngine.speed,
                callback = callbackFn
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sentence for TTS: ${e.message}")
        }
    }
    
    private fun createSentences(text: String): List<String> {
        // Define a minimum chunk size to avoid processing very short segments
        val MIN_CHUNK_SIZE = 20
        if (text.length < MIN_CHUNK_SIZE) {
            return emptyList()
        }
        
        // Use a regex to split text at punctuation (., !, ?) followed by whitespace
        // This improves performance and clarity compared to manual delimiter searching
        val sentences = Regex("(?<=[.!?])\\s+").split(text).filter { it.trim().isNotEmpty() }
        
        return sentences
    }

    /**
     * Finds all complete sentences in the given text.
     * A complete sentence is defined as any substring that ends with a punctuation mark (. ! ?),
     * followed by whitespace or end-of-string.
     */
    private fun createCompleteSentenceMatches(text: String): List<MatchResult> {
        val regex = Regex(".*?[.!?](\\s+|$)")
        return regex.findAll(text).toList()
    }

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val mUserMessage: TextView = itemView.findViewById(R.id.user_message)
        val mBotMessage: TextView = itemView.findViewById(R.id.bot_message)
        val mLeftChatLayout: LinearLayout = itemView.findViewById(R.id.left_chat_layout)
        val mRightChatLayout: LinearLayout = itemView.findViewById(R.id.right_chat_layout)
        val mTokenTimingView: TextView = itemView.findViewById(R.id.token_timing_view)
        val mTranscriptionTimingView: TextView = itemView.findViewById(R.id.transcription_timing_view)
        val mBotTtsFab: FloatingActionButton = itemView.findViewById(R.id.bot_tts_fab)
        val mUserTtsFab: FloatingActionButton = itemView.findViewById(R.id.user_tts_fab)
    }
}
