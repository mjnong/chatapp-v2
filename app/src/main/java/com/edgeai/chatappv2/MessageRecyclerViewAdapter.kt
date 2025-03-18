package com.edgeai.chatappv2

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlinx.coroutines.channels.Channel

class Message_RecyclerViewAdapter(
    private val context: Context,
    private val messages: ArrayList<ChatMessage>
) : RecyclerView.Adapter<Message_RecyclerViewAdapter.MyViewHolder>() {

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

            // Set up long press on user message
            setupLongPressListener(holder.mRightChatLayout, msg)
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

            // Set up long press on bot message
            setupLongPressListener(holder.mLeftChatLayout, msg)
        }
    }

    private fun setupLongPressListener(chatLayout: View, message: ChatMessage) {
        chatLayout.setOnLongClickListener {
            showPopupMenu(it, message)
            true
        }
    }

    private fun showPopupMenu(view: View, message: ChatMessage) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.menu.add(0, 1, 0, "Speak text")
        popupMenu.menu.add(0, 2, 0, "Stop speaking")

        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { // Speak text
                    TtsEngine.trackState = false
                    TtsEngine.trackPause()
                    TtsEngine.trackFlush()
                    TtsEngine.trackPlay()
                    TtsEngine.sample = Channel<FloatArray>()
                    TtsEngine.onClickPlay(context, context.filesDir.absolutePath)
                    Toast.makeText(context, "Speaking message", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> { // Stop speaking
                    TtsEngine.onCLickStop()
                    Toast.makeText(context, "Stopped speaking", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
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
            addMessage(ChatMessage(bot_message, MessageSender.BOT, firstTokenTime))
        }

        if (lastMessageFromBot) {
            val msg = messages.last()
            msg.message += bot_message
            msg.msToLastToken = startTime
        } else {
            // Create a new message with first token time
            val firstTokenTime = System.currentTimeMillis() - startTime
            addMessage(ChatMessage(bot_message, MessageSender.BOT, firstTokenTime))
        }
    }

    class MyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val mUserMessage: TextView = itemView.findViewById(R.id.user_message)
        val mBotMessage: TextView = itemView.findViewById(R.id.bot_message)
        val mLeftChatLayout: LinearLayout = itemView.findViewById(R.id.left_chat_layout)
        val mRightChatLayout: LinearLayout = itemView.findViewById(R.id.right_chat_layout)
        val mTokenTimingView: TextView = itemView.findViewById(R.id.token_timing_view)
        val mTranscriptionTimingView: TextView = itemView.findViewById(R.id.transcription_timing_view)
    }
}
