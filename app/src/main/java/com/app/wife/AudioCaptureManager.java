package com.wife.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.OutputStream;

public class AudioCaptureManager {
    private static final String TAG = "AudioCapture";

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordThread;

    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    public AudioCaptureManager(Context context) {
        this.context = context;
    }

    @SuppressLint("MissingPermission")
    public synchronized void startCapture(final OutputStream outputStream) {
        WifeLogger.log(TAG, "startCapture() invoked. Checking active recording status...");
        if (isRecording) {
            WifeLogger.log(TAG, "startCapture() aborted: Capture thread is already active.");
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        WifeLogger.log(TAG, "Resolved min buffer size for AudioRecord: " + minBufferSize + " bytes. Initializing AudioRecord driver...");

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize
            );
        } catch (Exception initEx) {
            WifeLogger.log(TAG, "Exception thrown inside AudioRecord constructor: " + initEx.getMessage(), initEx);
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state is not initialized.");
            WifeLogger.log(TAG, "AudioRecord state check failed: STATE_UNINITIALIZED. Capture cannot start.");
            return;
        }

        // Enable hardware Echo Cancellation and Noise Suppression if available
        int audioSessionId = audioRecord.getAudioSessionId();
        WifeLogger.log(TAG, "AudioRecord successfully initialized. Session ID resolved: " + audioSessionId + " | Assessing native sound filters...");

        if (AcousticEchoCanceler.isAvailable()) {
            WifeLogger.log(TAG, "Hardware AcousticEchoCanceler is supported. Attempting binding...");
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                    WifeLogger.log(TAG, "AcousticEchoCanceler enabled successfully.");
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed enabling AcousticEchoCanceler: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "Hardware AcousticEchoCanceler is not supported on this device.");
        }

        if (NoiseSuppressor.isAvailable()) {
            WifeLogger.log(TAG, "Hardware NoiseSuppressor is supported. Attempting binding...");
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    WifeLogger.log(TAG, "NoiseSuppressor enabled successfully.");
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed enabling NoiseSuppressor: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "Hardware NoiseSuppressor is not supported on this device.");
        }

        WifeLogger.log(TAG, "Starting native microphone capture stream.");
        try {
            audioRecord.startRecording();
        } catch (Exception startEx) {
            WifeLogger.log(TAG, "Failed starting microphone capture stream: " + startEx.getMessage(), startEx);
            return;
        }

        isRecording = true;

        recordThread = new Thread(() -> {
            WifeLogger.log(TAG, "Audio capture streaming thread spawned. Starting read-loop on AudioRecord buffer.");
            byte[] buffer = new byte[minBufferSize];
            long totalBytesCaptured = 0;
            try {
                while (isRecording) {
                    int readBytes = audioRecord.read(buffer, 0, buffer.length);
                    if (readBytes > 0) {
                        outputStream.write(buffer, 0, readBytes);
                        totalBytesCaptured += readBytes;
                    } else if (readBytes < 0) {
                        WifeLogger.log(TAG, "AudioRecord read error encountered. Status Code: " + readBytes);
                    }
                }
                WifeLogger.log(TAG, "Exited audio capture streaming read-loop. Total PCM bytes transmitted: " + totalBytesCaptured);
            } catch (Exception e) {
                Log.e(TAG, "Audio capture streaming error: " + e.getMessage());
                WifeLogger.log(TAG, "Audio capture streaming loop encountered an exception: " + e.getMessage(), e);
            }
        });
        recordThread.start();
        Log.d(TAG, "Audio capture started at session " + audioSessionId);
        WifeLogger.log(TAG, "Audio capture thread started successfully.");
    }

    public synchronized void stopCapture() {
        WifeLogger.log(TAG, "stopCapture() invoked. Halting capture loops and releasing audio hardware...");
        isRecording = false;
        if (recordThread != null) {
            WifeLogger.log(TAG, "Interrupting active audio capture thread.");
            recordThread.interrupt();
            recordThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    WifeLogger.log(TAG, "Stopping native AudioRecord capture stream.");
                    audioRecord.stop();
                }
                WifeLogger.log(TAG, "Releasing native AudioRecord resource buffers.");
                audioRecord.release();
            } catch (Exception e) {
                e.printStackTrace();
                WifeLogger.log(TAG, "Error stopping or releasing AudioRecord resource: " + e.getMessage(), e);
            }
            audioRecord = null;
        }
        if (echoCanceler != null) {
            try {
                WifeLogger.log(TAG, "Releasing hardware AcousticEchoCanceler.");
                echoCanceler.release();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error releasing AcousticEchoCanceler: " + e.getMessage(), e);
            }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try {
                WifeLogger.log(TAG, "Releasing hardware NoiseSuppressor.");
                noiseSuppressor.release();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error releasing NoiseSuppressor: " + e.getMessage(), e);
            }
            noiseSuppressor = null;
        }
        Log.d(TAG, "Audio capture stopped.");
        WifeLogger.log(TAG, "Audio capture halted cleanly.");
    }
}