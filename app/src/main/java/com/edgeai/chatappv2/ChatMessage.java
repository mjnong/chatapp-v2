package com.edgeai.chatappv2;

/**
 * ChatMessage: Holds information about each message within Chat
 */
public class ChatMessage {

    private String mMessage;
    private int mLength;
    public MessageSender mSender;
    private double msToFirstToken;
    private double totalGenerationTimeMs; // Changed from msToLastToken to be more explicit
    private long startTimeMs; // Store the original start time
    private boolean isFirstTokenTimeSet = false;
    private double transcriptionTimeMs = 0; // Time it took to transcribe voice input
    private boolean isFromVoiceInput = false; // Flag to indicate if message is from voice input

    public ChatMessage(String msg, MessageSender sender) {
        mMessage = msg;
        mSender = sender;
        mLength = msg.length();
        startTimeMs = System.currentTimeMillis(); // Initialize start time
    }

    /**
     * ChatMessage: Constructor for a message from the user
     * @param msg: the message
     * @param sender: the sender of the message
     * @param timeUntilFirstToken: the time it took to generate the first token
     */
    public ChatMessage(String msg, MessageSender sender, double timeUntilFirstToken) {
        mMessage = msg;
        mLength = msg.length();
        mSender = sender;
        msToFirstToken = timeUntilFirstToken;
        totalGenerationTimeMs = timeUntilFirstToken; // Initialize total time to at least first token time
        isFirstTokenTimeSet = timeUntilFirstToken > 0;
        startTimeMs = System.currentTimeMillis() - (long)timeUntilFirstToken; // Calculate approximate start time
    }

    /**
     * ChatMessage: Constructor for a message from voice input
     * @param msg: the message
     * @param sender: the sender of the message
     * @param transcriptionTime: the time it took to transcribe the voice input in milliseconds
     */
    public ChatMessage(String msg, MessageSender sender, double transcriptionTime, boolean isVoiceInput) {
        mMessage = msg;
        mLength = msg.length();
        mSender = sender;
        transcriptionTimeMs = transcriptionTime;
        isFromVoiceInput = isVoiceInput;
        startTimeMs = System.currentTimeMillis(); // Initialize start time
    }

    /**
     * isMessageFromUser: Check if the message is from the user
     *
     * @return true if the message is from the user, false otherwise
     */
    public boolean isMessageFromUser() {
        return mSender == MessageSender.USER;
    }

    /**
     * getMessage: Get the message
     *
     * @return the message
     */
    public String getMessage() {
        return mMessage;
    }

    /**
     * getLength: Get the length of the message
     *
     * @return the length of the message
     */
    public int getLength() {
        return mLength;
    }

    /**
     * setMessage: Set the message and update the length
     * @param message: the message to set
     */
    public void setMessage(String message) {
        mMessage = message;
        mLength = message.length();
    }

    /**
     * getMsToFirstToken: Get the time it took to generate the first token
     *
     * @return time in milliseconds
     */
    public double getMsToFirstToken() {
        return msToFirstToken;
    }

    /**
     * setMsToFirstToken: Set the time it took to generate the first token
     */
    public void setMsToFirstToken() {
        if (!isFirstTokenTimeSet) {
            msToFirstToken = System.currentTimeMillis() - startTimeMs;
            totalGenerationTimeMs = msToFirstToken; // Initialize total time to at least first token time
            isFirstTokenTimeSet = true;
        }
    }

    /**
     * getMsToLastToken: Get the time it took to generate the last token
     *
     * @return time in milliseconds
     */ 
    public double getTotalGenerationTimeMs() {
        return totalGenerationTimeMs;
    }

    /**
     * setMsToLastToken: Set the time it took to generate the last token
     * @param origin: the time the message was sent
     */
    public void setMsToLastToken(long origin) {
        double newTotalTime = System.currentTimeMillis() - origin;
        // Ensure total time is never less than first token time
        totalGenerationTimeMs = Math.max(newTotalTime, msToFirstToken);
    }

    /**
     * timeBetweenTokens: Get the time it took to generate all tokens (total generation time)
     *
     * @return time in milliseconds
     */
    public double timeBetweenTokens() {
        if (!isFirstTokenTimeSet || totalGenerationTimeMs == 0) {
            return 0;
        }
        return totalGenerationTimeMs; // Return the total generation time directly
    }

    /**
     * getTimeToFirstTokenSeconds: Get the time it took to generate the first token
     *
     * @return time in seconds
     */
    public double getTimeToFirstTokenSeconds() {
        return msToFirstToken / 1000.0;
    }

    /**
     * Returns total generation time in seconds
     */
    public double getTotalTimeSeconds() {
        return timeBetweenTokens() / 1000.0;
    }

    /**
     * Returns transcription time in seconds
     */
    public double getTranscriptionTimeSeconds() {
        return transcriptionTimeMs / 1000.0;
    }

    /**
     * Returns true if the message is from voice input
     */
    public boolean isFromVoiceInput() {
        return isFromVoiceInput;
    }

    /**
     * Set transcription time in milliseconds
     */
    public void setTranscriptionTimeMs(double transcriptionTime) {
        transcriptionTimeMs = transcriptionTime;
        isFromVoiceInput = true;
    }
}
