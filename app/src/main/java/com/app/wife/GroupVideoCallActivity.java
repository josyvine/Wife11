package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.wife.app.databinding.ActivityGroupVideoCallBinding;
import com.wife.app.VideoCaptureManager.LocalFrameListener;
import com.wife.app.GroupCallManager.GroupCallListener;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroupVideoCallActivity extends AppCompatActivity implements 
        CallSignalingManager.SignalingEventListener, 
        GroupCallListener,
        LocalFrameListener {

    private static final String TAG = "GroupVideoCallActivity";

    private ActivityGroupVideoCallBinding binding;
    private GroupCallManager groupCallManager;
    private VideoCaptureManager videoCaptureManager;
    private AudioCaptureManager audioCaptureManager;

    private boolean isMuted = false;
    private boolean isSpeaker = true;
    private boolean isInbound = false;
    private boolean isCallAccepted = false; // Tracks whether the inbound call has been picked up
    private long callStartTime = 0;

    private int callDurationSeconds = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    private MediaPlayer ringtonePlayer;
    private Vibrator vibratorService;

    // Track active peer hash IDs to their displayed CardView layout containers
    private final Map<Integer, CardView> peerCardViews = new HashMap<>();
    // Track active peer hash IDs to their displayed inside ImageView components
    private final Map<Integer, ImageView> peerImageViews = new HashMap<>();

    private CardView localCardView;
    private ImageView localImageView;

    // Proxy outputs to intercept raw capture bytes and stream them to UDP broadcast frames
    private OutputStream udpAudioOutputStream;
    private OutputStream udpVideoOutputStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Configure window flags to wake the screen backlight, keep it on, and bypass locks
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED 
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        binding = ActivityGroupVideoCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Initializing parallel 5-way GroupVideoCallActivity.");

        groupCallManager = GroupCallManager.getInstance(this);
        videoCaptureManager = new VideoCaptureManager(this);
        audioCaptureManager = AudioCaptureManager.getInstance(this);
        vibratorService = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        isInbound = getIntent().getBooleanExtra("IS_INBOUND", false);
        if (isInbound) {
            isCallAccepted = false;
        } else {
            isCallAccepted = true;
        }

        initializeProxyStreams();
        setupLocalUserCell();
        setupClickListeners();
        configureCallingState();

        if (isInbound) {
            startRingtone();
        }
    }

    private void startRingtone() {
        WifeLogger.log(TAG, "Checking device system audio configuration profile.");
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int ringerMode = audioManager.getRingerMode();
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                WifeLogger.log(TAG, "Device profile is SILENT. Suppressing incoming group call ringtone playback (Glitch 6 Fix).");
                return;
            } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                WifeLogger.log(TAG, "Device profile is VIBRATE. Suppressing ringtone, starting vibration (Glitch 6 Fix).");
                if (vibratorService != null && vibratorService.hasVibrator()) {
                    long[] pattern = {0, 600, 800}; // Vibrate 600ms, pause 800ms
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibratorService.vibrate(VibrationEffect.createWaveform(pattern, 0)); // Loop from index 0
                    } else {
                        vibratorService.vibrate(pattern, 0);
                    }
                }
                return;
            }
        }

        WifeLogger.log(TAG, "Initializing incoming group ringtone playback.");
        try {
            ringtonePlayer = MediaPlayer.create(this, R.raw.wife_ringtone);
            if (ringtonePlayer != null) {
                ringtonePlayer.setLooping(true);
                ringtonePlayer.start();
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error playing group call ringtone: " + e.getMessage(), e);
        }
    }

    private void stopRingtone() {
        if (vibratorService != null) {
            WifeLogger.log(TAG, "Halting active vibrator channels.");
            vibratorService.cancel();
        }
        if (ringtonePlayer != null) {
            try {
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            } catch (Exception ignored) {}
            ringtonePlayer = null;
        }
    }

    /**
     * Highly advanced TCP-to-UDP stream translation proxies.
     * Intercepts 1-to-1 TCP write blocks on the fly and broadcasts them over UDP.
     */
    private void initializeProxyStreams() {
        udpAudioOutputStream = new OutputStream() {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (isMuted) return;
                groupCallManager.broadcastAudioFrame(b, len);
            }

            @Override
            public void write(int b) throws IOException {}
        };

        udpVideoOutputStream = new OutputStream() {
            private final ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
            private byte[] frameBuffer = null;
            private int bytesWritten = 0;
            private int targetFrameSize = -1;

            @Override
            public void write(int b) throws IOException {}

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int offset = off;
                int remaining = len;

                while (remaining > 0) {
                    if (targetFrameSize == -1) {
                        int bytesToRead = Math.min(remaining, sizeBuffer.remaining());
                        sizeBuffer.put(b, offset, bytesToRead);
                        offset += bytesToRead;
                        remaining -= bytesToRead;

                        if (!sizeBuffer.hasRemaining()) {
                            sizeBuffer.flip();
                            targetFrameSize = sizeBuffer.getInt();
                            frameBuffer = new byte[targetFrameSize];
                            bytesWritten = 0;
                            sizeBuffer.clear();
                        }
                    } else {
                        int bytesToRead = Math.min(remaining, targetFrameSize - bytesWritten);
                        System.arraycopy(b, offset, frameBuffer, bytesWritten, bytesToRead);
                        offset += bytesToRead;
                        remaining -= bytesToRead;
                        bytesWritten += bytesToRead;

                        if (bytesWritten == targetFrameSize) {
                            groupCallManager.broadcastVideoFrame(frameBuffer);
                            targetFrameSize = -1;
                        }
                    }
                }
            }
        };
    }

    private void setupLocalUserCell() {
        localCardView = new CardView(this);
        localCardView.setRadius(24f);

        localImageView = new ImageView(this);
        localImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        localImageView.setBackgroundColor(0xFF222222);
        
        localCardView.addView(localImageView);
        binding.gridLayoutVideo.addView(localCardView);

        updateVideoGrid();
    }

    private void setupClickListeners() {
        binding.fabGroupMute.setOnClickListener(v -> {
            if (isInbound && !isCallAccepted) {
                // Symmetrical Pick Up trigger logic: Accept call and setup media controls
                isCallAccepted = true;
                acceptCall();
                
                // Return button to default theme, restore mic icon, and show control panel
                binding.fabGroupMute.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xAA121212));
                binding.fabGroupMute.setImageResource(android.R.drawable.ic_btn_speak_now);
                binding.fabGroupSwitchCamera.setVisibility(View.VISIBLE);
                binding.fabGroupSpeaker.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Group Call Connected", Toast.LENGTH_SHORT).show();
            } else {
                isMuted = !isMuted;
                WifeLogger.log(TAG, "Toggled mic state: Mute = " + isMuted);
                binding.fabGroupMute.setImageResource(isMuted ? 
                        android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_btn_speak_now);
                Toast.makeText(this, isMuted ? "Microphone muted" : "Microphone active", Toast.LENGTH_SHORT).show();
            }
        });

        binding.fabGroupSwitchCamera.setOnClickListener(v -> {
            WifeLogger.log(TAG, "Rotating local active camera lens.");
            videoCaptureManager.switchCamera(this, udpVideoOutputStream);
        });

        binding.fabGroupSpeaker.setOnClickListener(v -> {
            isSpeaker = !isSpeaker;
            WifeLogger.log(TAG, "Toggled speakerphone mixer state: " + isSpeaker);
            groupCallManager.setSpeakerphoneEnabled(isSpeaker);
            binding.fabGroupSpeaker.setImageResource(isSpeaker ? 
                    android.R.drawable.ic_lock_silent_mode_off : android.R.drawable.ic_lock_silent_mode);
            Toast.makeText(this, isSpeaker ? "Speaker mixer active" : "Earpiece active", Toast.LENGTH_SHORT).show();
        });

        binding.fabDeclineGroup.setOnClickListener(v -> {
            WifeLogger.log(TAG, "Decline/Hangup button clicked. Exiting group call.");
            hangUp();
        });
    }

    private void configureCallingState() {
        WifeLogger.log(TAG, "Registering CallingState listener with CallSignalingManager.");
        CallSignalingManager.getInstance(this).registerListener(this);

        if (isInbound) {
            WifeLogger.log(TAG, "Group Call State configured as: INBOUND.");
            binding.tvGroupCallState.setText("Inbound Group Request...");
            
            // Stylize mute button as green "Accept Call" FAB during ringing state
            binding.fabGroupMute.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
            binding.fabGroupMute.setImageResource(android.R.drawable.sym_action_call);
            
            // Symmetrical hide of camera lens flip and speaker toggle during ringing
            binding.fabGroupSwitchCamera.setVisibility(View.GONE);
            binding.fabGroupSpeaker.setVisibility(View.GONE);
        } else {
            WifeLogger.log(TAG, "Group Call State configured as: OUTBOUND. Broadcasting invitations...");
            binding.tvGroupCallState.setText("Inviting peers...");
            
            // Broadcast group calling signal request to all currently connected peers in connection map
            java.util.Map<String, String> activePeers = ConnectionManager.getInstance(this).getGroupPeers();
            List<String> peerIps = new ArrayList<>(activePeers.values());
            
            CallSignalingManager.getInstance(this).broadcastGroupSignal(peerIps, Constants.SIGNAL_GROUP_CALL_REQUEST);
            
            // Start local UDP session immediately as a broadcaster
            acceptCall();
        }
    }

    private void acceptCall() {
        stopRingtone();
        binding.tvGroupCallState.setText("Opening P2P Channels...");

        // Start hardware captures and UDP broadcast loops
        VideoCaptureManager.setLocalFrameListener(this);
        videoCaptureManager.startCapture(this, udpVideoOutputStream);
        audioCaptureManager.startCapture(udpAudioOutputStream);

        // Accept and start software audio mixer
        groupCallManager.startGroupCall(this);
        startCallServiceAndTimer();
    }

    private void startCallServiceAndTimer() {
        WifeLogger.log(TAG, "Starting GroupCallForegroundService foreground worker.");
        startService(new Intent(this, GroupCallForegroundService.class));

        binding.tvGroupCallState.setText("Streaming Live");
        callStartTime = System.currentTimeMillis();

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                callDurationSeconds++;
                binding.tvGroupCallDuration.setText(Utils.formatDuration(callDurationSeconds));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void hangUp() {
        WifeLogger.log(TAG, "hangUp() executed. Releasing parallel hardware engines and services.");
        stopRingtone();
        stopCallServiceAndTimer();

        // 1. Terminate local video capture and listeners
        videoCaptureManager.stopCapture();
        audioCaptureManager.stopCapture();
        VideoCaptureManager.setLocalFrameListener(null);

        // 2. Halt UDP sockets and audio mixer
        groupCallManager.endGroupCall();

        // 3. Log call duration record
        long duration = (callStartTime > 0) ? (System.currentTimeMillis() - callStartTime) / 1000 : 0;
        CallEntity entity = new CallEntity("Mesh Group Call", "Video", duration, System.currentTimeMillis());
        try {
            RoomDatabaseManager.getInstance(this).callDao().insert(entity);
            WifeLogger.log(TAG, "Group call session record saved successfully in database.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed saving group call record: " + e.getMessage());
        }

        finish();
    }

    private void stopCallServiceAndTimer() {
        CallSignalingManager.getInstance(this).unregisterListener(this);
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        stopService(new Intent(this, GroupCallForegroundService.class));
    }

    /**
     * Dynamically updates and resizes grid dimensions based on active calling count.
     */
    private void updateVideoGrid() {
        int count = binding.gridLayoutVideo.getChildCount();
        if (count == 0) return;

        // Reset the specs first of all child layouts before column/row updates to prevent crash exception
        for (int i = 0; i < count; i++) {
            View child = binding.gridLayoutVideo.getChildAt(i);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) child.getLayoutParams();
            if (params != null) {
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
                params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED);
                child.setLayoutParams(params);
            }
        }

        // Configuration splits:
        // 1-2 elements: 1 column
        // 3-5 elements: 2 columns
        int cols = (count <= 2) ? 1 : 2;
        int rows = (int) Math.ceil((double) count / cols);

        binding.gridLayoutVideo.setColumnCount(cols);
        binding.gridLayoutVideo.setRowCount(rows);

        for (int i = 0; i < count; i++) {
            View child = binding.gridLayoutVideo.getChildAt(i);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) child.getLayoutParams();
            if (params == null) {
                params = new GridLayout.LayoutParams();
            }
            params.width = 0;
            params.height = 0;
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1.0f);
            
            // Apply slight margins for clear cell separation
            params.setMargins(6, 6, 6, 6);
            child.setLayoutParams(params);
        }
    }

    // --- Local Frame Listeners ---
    @Override
    public void onLocalFrameCaptured(Bitmap bitmap) {
        runOnUiThread(() -> {
            if (bitmap != null && localImageView != null) {
                localImageView.setImageBitmap(bitmap);
            }
        });
    }

    // --- UDP Group Call Listener Callbacks ---
    @Override
    public void onPeerJoined(int peerIdHash) {
        WifeLogger.log(TAG, "onPeerJoined UI callback fired for peer: [" + peerIdHash + "]. Appending grid cell.");
        runOnUiThread(() -> {
            if (peerCardViews.containsKey(peerIdHash)) return;

            CardView cardView = new CardView(this);
            cardView.setRadius(24f);

            ImageView imageView = new ImageView(this);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setBackgroundColor(0xFF222222);

            cardView.addView(imageView);
            binding.gridLayoutVideo.addView(cardView);

            peerCardViews.put(peerIdHash, cardView);
            peerImageViews.put(peerIdHash, imageView);

            updateVideoGrid();
        });
    }

    @Override
    public void onPeerLeft(int peerIdHash) {
        WifeLogger.log(TAG, "onPeerLeft UI callback fired for peer: [" + peerIdHash + "]. Removing grid cell.");
        runOnUiThread(() -> {
            CardView cardView = peerCardViews.remove(peerIdHash);
            peerImageViews.remove(peerIdHash);

            if (cardView != null) {
                binding.gridLayoutVideo.removeView(cardView);
                updateVideoGrid();
            }
        });
    }

    @Override
    public void onVideoFrameReceived(int peerIdHash, Bitmap bitmap) {
        runOnUiThread(() -> {
            ImageView imageView = peerImageViews.get(peerIdHash);
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
        });
    }

    @Override
    public void onError(String error) {
        WifeLogger.log(TAG, "onGroupCallError UI callback received: " + error);
        runOnUiThread(() -> {
            Toast.makeText(this, "Group Call Connection Error: " + error, Toast.LENGTH_SHORT).show();
            hangUp();
        });
    }

    @Override
    public void onSignalReceived(String action, String senderIp, JsonObject payload) {
        WifeLogger.log(TAG, "onSignalReceived callback triggered inside Group Call. Action: " + action);
        runOnUiThread(() -> {
            if (Constants.SIGNAL_GROUP_CALL_END.equals(action)) {
                Toast.makeText(this, "Group Call session ended by peer.", Toast.LENGTH_SHORT).show();
                hangUp();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WifeLogger.log(TAG, "onDestroy() invoked. Releasing handlers.");
        stopRingtone();
        VideoCaptureManager.setLocalFrameListener(null);
    }

    @Override
    public void onBackPressed() {
        WifeLogger.log(TAG, "onBackPressed() invoked. Triggering parallel call teardown.");
        hangUp();
        super.onBackPressed();
    }
}