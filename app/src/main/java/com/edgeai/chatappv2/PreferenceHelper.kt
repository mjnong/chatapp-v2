package com.edgeai.chatappv2

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper class to manage user preferences for TTS
 */
class PreferenceHelper(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "tts_preferences",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_SPEED = "tts_speed"
        private const val KEY_SPEAKER_ID = "tts_speaker_id"
        private const val DEFAULT_SPEED = 1.0f
        private const val DEFAULT_SPEAKER_ID = 0
    }
    
    /**
     * Get the saved TTS speech speed
     */
    fun getSpeed(): Float {
        return sharedPreferences.getFloat(KEY_SPEED, DEFAULT_SPEED)
    }
    
    /**
     * Save the TTS speech speed
     */
    fun setSpeed(speed: Float) {
        sharedPreferences.edit().putFloat(KEY_SPEED, speed).apply()
    }
    
    /**
     * Get the saved speaker ID
     */
    fun getSpeakerId(): Int {
        return sharedPreferences.getInt(KEY_SPEAKER_ID, DEFAULT_SPEAKER_ID)
    }
    
    /**
     * Save the speaker ID
     */
    fun setSpeakerId(speakerId: Int) {
        sharedPreferences.edit().putInt(KEY_SPEAKER_ID, speakerId).apply()
    }
}