package com.inout.app;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Background Service to safely initialize and execute Text-To-Speech spoken reminders [3].
 * Tied to the system alarm triggers and manages its own lifecycle to prevent memory leaks [3].
 */
public class CheckInVoiceService extends Service implements TextToSpeech.OnInitListener {

    private static final String TAG = "CheckInVoiceService";
    private static final String UTTERANCE_ID = "InOutSpeechUtteranceId";

    private TextToSpeech tts;
    private String reminderType;
    private boolean isTtsInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Voice service created: Initializing Text-to-Speech Engine");
        // Initialize the native TTS engine with this service acting as the listener [3]
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            reminderType = intent.getStringExtra("reminder_type");
        }

        if (reminderType == null) {
            reminderType = "about_to_check_in"; // Standard fallback
        }

        Log.d(TAG, "Voice service started with type: " + reminderType);

        // If engine is already loaded, speak immediately
        if (isTtsInitialized) {
            speakPhrase();
        }

        // Return START_NOT_STICKY so the system does not recreate the voice service if killed [3]
        return START_NOT_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS Engine initialized successfully");
            
            // Configure voice language locales [3]
            int result = tts.setLanguage(Locale.US);

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Missing TTS language data or selected locale is not supported on this device");
                stopSelf(); // Safely exit to prevent service hanging [3]
                return;
            }

            isTtsInitialized = true;

            // Attach listener to track when the speaker finishes speaking [3]
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "Speech utterance started: " + utteranceId);
                }

                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "Speech completed: " + utteranceId + ". Shutting down service.");
                    // Cleanly stop the service context once vocal warning is finished [3]
                    stopSelf();
                }

                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "Speech error on utterance: " + utteranceId);
                    stopSelf(); // Exit safely [3]
                }
            });

            // Trigger the spoken warning
            speakPhrase();

        } else {
            Log.e(TAG, "Failed to initialize TTS Engine. Status: " + status);
            stopSelf(); // Exit safely to prevent resource leaks [3]
        }
    }

    private void speakPhrase() {
        if (tts == null || !isTtsInitialized) return;

        String textToSpeak = "Attention required.";

        if ("about_to_check_in".equals(reminderType)) {
            textToSpeak = "You are about to check in.";
        } else if ("late_check_in".equals(reminderType)) {
            textToSpeak = "You are late. Resume requested.";
        } else if ("about_to_check_out".equals(reminderType)) {
            textToSpeak = "You are about to check out.";
        }

        Log.d(TAG, "Vocal announcement playing: \"" + textToSpeak + "\"");

        // Pass utterance ID to trigger listener callbacks [3]
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID);

        // Queue-Flush parameters are used to interrupt existing sounds and speak immediately [3]
        tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Bound access not required [3]
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Voice service destroyed: Releasing Text-To-Speech resources");
        // Shutdown engine components to prevent memory leaks [3]
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}