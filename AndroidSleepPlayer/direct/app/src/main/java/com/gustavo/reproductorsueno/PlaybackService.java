package com.gustavo.reproductorsueno;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Random;

public class PlaybackService extends Service {
    public static final String ACTION_PLAY_INDEX = "com.gustavo.reproductorsueno.PLAY_INDEX";
    public static final String ACTION_PLAY = "com.gustavo.reproductorsueno.PLAY";
    public static final String ACTION_PAUSE = "com.gustavo.reproductorsueno.PAUSE";
    public static final String ACTION_NEXT = "com.gustavo.reproductorsueno.NEXT";
    public static final String ACTION_PREVIOUS = "com.gustavo.reproductorsueno.PREVIOUS";
    public static final String ACTION_SETTINGS = "com.gustavo.reproductorsueno.SETTINGS";
    public static final String ACTION_SET_VOLUME = "com.gustavo.reproductorsueno.SET_VOLUME";
    public static final String ACTION_PLAY_NEXT = "com.gustavo.reproductorsueno.PLAY_NEXT";
    public static final String ACTION_SEEK = "com.gustavo.reproductorsueno.SEEK";
    public static final String ACTION_QUERY_STATE = "com.gustavo.reproductorsueno.QUERY_STATE";
    public static final String BROADCAST_STATE = "com.gustavo.reproductorsueno.STATE";

    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_ATTENUATION_DB = "attenuation_db";
    public static final String EXTRA_SHUFFLE = "shuffle";
    public static final String EXTRA_NIGHT_MODE = "night_mode";
    public static final String EXTRA_FM_PROCESSOR = "fm_processor";
    public static final String EXTRA_PROFILE_GAINS = "profile_gains";
    public static final String EXTRA_PROFILE_PREAMP_DB = "profile_preamp_db";
    public static final String EXTRA_PROFILE_NAME = "profile_name";
    public static final String EXTRA_EXPECT_HEADPHONES = "expect_headphones";
    public static final String EXTRA_NEXT_URI = "next_uri";
    public static final String EXTRA_NEXT_NAME = "next_name";
    public static final String EXTRA_SEEK_POSITION = "seek_position";
    public static final String EXTRA_RESUME_POSITION = "resume_position";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_MISSING_URI = "missing_uri";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_CURRENT_INDEX = "current_index";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_LOADING = "loading";
    public static final String STATE_PLAYING = "playing";
    public static final String STATE_PAUSED = "paused";

    private static final String CHANNEL_ID = "radioenlace_player";
    private static final int NOTIFICATION_ID = 1040;
    private static final int[] PROFILE_BANDS = new int[]{60, 230, 910, 3600, 14000};

    private final ArrayList<String> uris = new ArrayList<>();
    private final LocalBinder binder = new LocalBinder();
    private final ArrayList<String> names = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private MediaPlayer player;
    private Equalizer equalizer;
    private LoudnessEnhancer loudnessEnhancer;
    private DynamicsProcessing dynamicsProcessing;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioDeviceCallback deviceCallback;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback(null);
        }
    };
    private SharedPreferences statePrefs;

    private int currentIndex;
    private int attenuationDb = -6;
    private boolean shuffle;
    private boolean nightMode;
    private boolean fmProcessor;
    private boolean expectHeadphones;
    private boolean prepared;
    private boolean loading;
    private String profileName = "Perfil estándar";
    private float[] profileGains = new float[]{0f, 0f, 0f, 0f, 0f};
    private float profilePreampDb;
    private String playNextUri;
    private String playNextName;
    private int fadeGeneration;
    private int consecutiveErrors;
    private int pendingResumePositionMs;

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            broadcastState(null, null);
            if (safeIsPlaying()) handler.postDelayed(this, 750L);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseForDisconnectedHeadphones();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null && Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
        }
        statePrefs = getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        createNotificationChannel();
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        registerDeviceCallback();
    }


    public final class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    public void syncQueueFromClient(ArrayList<String> incomingUris, ArrayList<String> incomingNames,
                                    int preferredIndex) {
        if (incomingUris == null || incomingUris.isEmpty()) return;
        String currentUri = currentIndex >= 0 && currentIndex < uris.size() ? uris.get(currentIndex) : null;
        boolean changed = !uris.equals(incomingUris);
        uris.clear();
        uris.addAll(incomingUris);
        names.clear();
        if (incomingNames != null) names.addAll(incomingNames);
        while (names.size() < uris.size()) names.add("Pista de audio");

        if (player == null || !prepared) {
            currentIndex = Math.max(0, Math.min(uris.size() - 1, preferredIndex));
        } else if (changed && currentUri != null) {
            int preserved = uris.indexOf(currentUri);
            if (preserved >= 0) currentIndex = preserved;
            else {
                releasePlayer();
                currentIndex = Math.max(0, Math.min(uris.size() - 1, preferredIndex));
            }
        } else if (currentIndex >= uris.size()) {
            currentIndex = 0;
        }
    }

    public void configureFromClient(int clientAttenuationDb, boolean clientShuffle,
                                    boolean clientNightMode, boolean clientFmProcessor,
                                    float[] clientProfileGains, float clientProfilePreampDb,
                                    String clientProfileName, boolean clientExpectHeadphones) {
        attenuationDb = Math.max(-60, Math.min(0, clientAttenuationDb));
        shuffle = clientShuffle;
        nightMode = clientNightMode;
        fmProcessor = clientFmProcessor;
        if (clientProfileGains != null && clientProfileGains.length >= 5) {
            profileGains = clientProfileGains.clone();
        }
        profilePreampDb = Math.max(-12f, Math.min(0f, clientProfilePreampDb));
        profileName = clientProfileName == null || clientProfileName.isEmpty()
                ? "Perfil estándar" : clientProfileName;
        expectHeadphones = clientExpectHeadphones;
    }

    public void playFromClient(int preferredIndex, int resumePositionMs) {
        if (!checkExpectedOutput() || loading) return;
        if (uris.isEmpty()) {
            broadcastState("No hay canciones en la lista.", null);
            return;
        }
        int requested = Math.max(0, Math.min(uris.size() - 1, preferredIndex));
        if (player == null || !prepared || requested != currentIndex) {
            startTrack(requested, Math.max(0, resumePositionMs));
        } else {
            playExplicitly();
        }
    }

    public void pauseFromClient() { pausePlayback(null); }
    public void nextFromClient() { if (checkExpectedOutput() && !loading) nextTrack(); }
    public void previousFromClient() { if (checkExpectedOutput() && !loading) previousTrack(); }
    public void seekFromClient(int position) { seekTo(position); }
    public void setVolumeFromClient(int db) {
        attenuationDb = Math.max(-60, Math.min(0, db));
        applyVolumeImmediately();
        broadcastState(null, null);
    }
    public void applySettingsFromClient() { applyCurrentSettings(); }
    public void requestStateFromClient() { broadcastState(null, null); }
    public boolean isPlayingForClient() { return safeIsPlaying(); }
    public boolean isLoadingForClient() { return loading; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();

        if (!ACTION_SET_VOLUME.equals(action) && !ACTION_QUERY_STATE.equals(action)) {
            updatePlaylist(intent);
            updateSettings(intent);
        }

        if (ACTION_PLAY_INDEX.equals(action)) {
            if (checkExpectedOutput() && !loading) startTrack(intent.getIntExtra(EXTRA_INDEX, 0));
        } else if (ACTION_PLAY.equals(action)) {
            if (checkExpectedOutput()) {
                int resume = intent.getIntExtra(EXTRA_RESUME_POSITION,
                        statePrefs == null ? 0 : statePrefs.getInt("last_position", 0));
                if (player == null || !prepared) startTrack(currentIndex, resume);
                else playExplicitly();
            }
        } else if (ACTION_PAUSE.equals(action)) {
            pausePlayback(null);
        } else if (ACTION_NEXT.equals(action)) {
            if (checkExpectedOutput() && !loading) nextTrack();
        } else if (ACTION_PREVIOUS.equals(action)) {
            if (checkExpectedOutput() && !loading) previousTrack();
        } else if (ACTION_SETTINGS.equals(action)) {
            applyCurrentSettings();
        } else if (ACTION_SET_VOLUME.equals(action)) {
            attenuationDb = Math.max(-60, Math.min(0,
                    intent.getIntExtra(EXTRA_ATTENUATION_DB, attenuationDb)));
            applyVolumeImmediately();
            broadcastState(null, null);
        } else if (ACTION_PLAY_NEXT.equals(action)) {
            playNextUri = intent.getStringExtra(EXTRA_NEXT_URI);
            playNextName = intent.getStringExtra(EXTRA_NEXT_NAME);
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getIntExtra(EXTRA_SEEK_POSITION, 0));
        } else if (ACTION_QUERY_STATE.equals(action)) {
            broadcastState(null, null);
        }
        return START_STICKY;
    }

    private void updatePlaylist(Intent intent) {
        ArrayList<String> incomingUris = intent.getStringArrayListExtra(EXTRA_URIS);
        ArrayList<String> incomingNames = intent.getStringArrayListExtra(EXTRA_NAMES);
        if (incomingUris == null || incomingUris.isEmpty()) return;
        uris.clear();
        uris.addAll(incomingUris);
        names.clear();
        if (incomingNames != null) names.addAll(incomingNames);
        while (names.size() < uris.size()) names.add("Pista de audio");
        if (intent.hasExtra(EXTRA_INDEX) && player == null) {
            currentIndex = Math.max(0, Math.min(uris.size() - 1, intent.getIntExtra(EXTRA_INDEX, 0)));
        } else if (currentIndex >= uris.size()) currentIndex = 0;
    }

    private void updateSettings(Intent intent) {
        if (intent.hasExtra(EXTRA_NIGHT_MODE)) {
            nightMode = intent.getBooleanExtra(EXTRA_NIGHT_MODE, false);
        }
        if (intent.hasExtra(EXTRA_ATTENUATION_DB)) {
            attenuationDb = Math.max(-60, Math.min(0,
                    intent.getIntExtra(EXTRA_ATTENUATION_DB, nightMode ? -20 : -6)));
        }
        if (intent.hasExtra(EXTRA_SHUFFLE)) shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false);
        if (intent.hasExtra(EXTRA_FM_PROCESSOR)) fmProcessor = intent.getBooleanExtra(EXTRA_FM_PROCESSOR, false);
        if (intent.hasExtra(EXTRA_PROFILE_GAINS)) {
            float[] incoming = intent.getFloatArrayExtra(EXTRA_PROFILE_GAINS);
            if (incoming != null && incoming.length >= 5) profileGains = incoming.clone();
        }
        if (intent.hasExtra(EXTRA_PROFILE_PREAMP_DB)) {
            profilePreampDb = Math.max(-12f, Math.min(0f,
                    intent.getFloatExtra(EXTRA_PROFILE_PREAMP_DB, 0f)));
        }
        if (intent.hasExtra(EXTRA_PROFILE_NAME)) {
            String incomingName = intent.getStringExtra(EXTRA_PROFILE_NAME);
            profileName = incomingName == null || incomingName.isEmpty() ? "Perfil estándar" : incomingName;
        }
        if (intent.hasExtra(EXTRA_EXPECT_HEADPHONES)) {
            expectHeadphones = intent.getBooleanExtra(EXTRA_EXPECT_HEADPHONES, false);
        }
    }

    private void playExplicitly() {
        if (loading) return;
        if (player == null || !prepared) {
            if (!uris.isEmpty()) startTrack(currentIndex);
            else broadcastState("No hay canciones en la lista.", null);
            return;
        }
        if (safeIsPlaying()) {
            broadcastState(null, null);
            return;
        }
        requestAudioFocus();
        try {
            player.start();
            rampFromCurrentToTarget(450);
            startForeground(NOTIFICATION_ID, buildNotification(true));
            startProgressTicker();
            broadcastState(null, null);
        } catch (Exception ignored) { }
    }

    private void pauseForDisconnectedHeadphones() {
        pausePlayback("Auriculares desconectados. Reproducción pausada.");
    }

    private void pausePlayback(String message) {
        stopProgressTicker();
        if (player != null && safeIsPlaying()) {
            try { player.pause(); } catch (Exception ignored) { }
        }
        if (player != null) startForeground(NOTIFICATION_ID, buildNotification(false));
        broadcastState(message, null);
    }

    private void startTrack(int index) { startTrack(index, 0); }

    private void startTrack(int index, int resumePositionMs) {
        if (uris.isEmpty()) return;
        if (loading) return;
        currentIndex = Math.max(0, Math.min(uris.size() - 1, index));
        releasePlayer();
        pendingResumePositionMs = Math.max(0, resumePositionMs);
        loading = true;
        prepared = false;
        String uri = uris.get(currentIndex);
        broadcastState(null, null);

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this, Uri.parse(uri));
            player.setOnPreparedListener(mp -> {
                loading = false;
                prepared = true;
                if (!checkExpectedOutput()) return;
                consecutiveErrors = 0;
                attachAudioProcessing();
                requestAudioFocus();
                mp.setVolume(0f, 0f);
                try {
                    int duration = mp.getDuration();
                    if (pendingResumePositionMs > 0 && pendingResumePositionMs < duration - 1000) {
                        mp.seekTo(pendingResumePositionMs);
                    }
                } catch (Exception ignored) { }
                pendingResumePositionMs = 0;
                try { mp.start(); } catch (Exception ignored) { }
                fadeFromZeroToTarget(nightMode ? 1800 : 900);
                startForeground(NOTIFICATION_ID, buildNotification(true));
                startProgressTicker();
                broadcastState(null, null);
            });
            player.setOnCompletionListener(mp -> nextTrack());
            player.setOnErrorListener((mp, what, extra) -> {
                handleTrackError(uri);
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            handleTrackError(uri);
        }
    }

    private void handleTrackError(String missingUri) {
        loading = false;
        prepared = false;
        stopProgressTicker();
        consecutiveErrors++;
        broadcastState("Archivo no disponible. Se salta a la siguiente canción.", missingUri);
        if (uris.isEmpty() || consecutiveErrors >= uris.size()) {
            releasePlayer();
            broadcastState("No quedan archivos disponibles en la lista.", missingUri);
            return;
        }
        handler.postDelayed(this::nextTrack, 350L);
    }

    private void nextTrack() {
        if (uris.isEmpty() || loading) return;
        if (playNextUri != null && !playNextUri.isEmpty()) {
            String uri = playNextUri;
            String name = playNextName == null || playNextName.isEmpty() ? "Pista siguiente" : playNextName;
            playNextUri = null;
            playNextName = null;
            int existing = uris.indexOf(uri);
            if (existing < 0) {
                int insert = Math.min(currentIndex + 1, uris.size());
                uris.add(insert, uri);
                names.add(insert, name);
                existing = insert;
            }
            startTrack(existing);
            return;
        }
        int next;
        if (shuffle && uris.size() > 1) {
            do { next = random.nextInt(uris.size()); } while (next == currentIndex);
        } else next = (currentIndex + 1) % uris.size();
        startTrack(next);
    }

    private void previousTrack() {
        if (uris.isEmpty() || loading) return;
        int previous = currentIndex <= 0 ? uris.size() - 1 : currentIndex - 1;
        startTrack(previous);
    }

    private void seekTo(int position) {
        if (player == null || !prepared) return;
        try {
            int duration = player.getDuration();
            player.seekTo(Math.max(0, Math.min(duration, position)));
            broadcastState(null, null);
        } catch (Exception ignored) { }
    }

    private void applyCurrentSettings() {
        if (player == null || !prepared) {
            broadcastState(null, null);
            return;
        }
        if (expectHeadphones && !hasHeadphoneOutput()) {
            pauseForDisconnectedHeadphones();
            return;
        }
        attachAudioProcessing();
        rampFromCurrentToTarget(450);
        startForeground(NOTIFICATION_ID, buildNotification(safeIsPlaying()));
        broadcastState(null, null);
    }

    private boolean checkExpectedOutput() {
        if (!expectHeadphones || hasHeadphoneOutput()) return true;
        pauseForDisconnectedHeadphones();
        return false;
    }

    private void attachAudioProcessing() {
        releaseAudioEffects();
        if (player == null || !prepared) return;
        int sessionId = player.getAudioSessionId();
        attachEqualizer(sessionId);
        if (fmProcessor) {
            attachFmDynamics(sessionId);
            attachFmLoudness(sessionId);
        }
    }

    private void attachEqualizer(int sessionId) {
        try {
            equalizer = new Equalizer(0, sessionId);
            short[] range = equalizer.getBandLevelRange();
            short bandCount = equalizer.getNumberOfBands();
            for (short band = 0; band < bandCount; band++) {
                int centerHz = equalizer.getCenterFreq(band) / 1000;
                float requestedDb = interpolatedProfileGain(centerHz) + fmEqGain(centerHz);
                int requested = Math.round(requestedDb * 100f);
                short safe = (short) Math.max(range[0], Math.min(range[1], requested));
                equalizer.setBandLevel(band, safe);
            }
            equalizer.setEnabled(true);
        } catch (Exception ignored) { releaseEqualizer(); }
    }

    private float interpolatedProfileGain(int centerHz) {
        if (centerHz <= PROFILE_BANDS[0]) return profileGains[0];
        for (int i = 1; i < PROFILE_BANDS.length; i++) {
            if (centerHz <= PROFILE_BANDS[i]) {
                double logF = Math.log(Math.max(1, centerHz));
                double logL = Math.log(PROFILE_BANDS[i - 1]);
                double logR = Math.log(PROFILE_BANDS[i]);
                float t = (float) ((logF - logL) / Math.max(0.0001, logR - logL));
                return profileGains[i - 1] + (profileGains[i] - profileGains[i - 1]) * t;
            }
        }
        return profileGains[profileGains.length - 1];
    }

    private float fmEqGain(int centerHz) {
        if (!fmProcessor) return 0f;
        if (centerHz < 120) return nightMode ? 0.4f : 1.0f;
        if (centerHz < 500) return nightMode ? 0.1f : 0.3f;
        if (centerHz < 2000) return nightMode ? 0.2f : 0.6f;
        if (centerHz < 6000) return nightMode ? 0.2f : 0.8f;
        return nightMode ? -0.2f : 0.3f;
    }

    private void attachFmDynamics(int sessionId) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            int bands = 3;
            DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, bands);
            if (nightMode) {
                mbc.setBand(0, new DynamicsProcessing.MbcBand(true, 250f, 24f, 260f,
                        1.7f, -27f, 8f, -80f, 1f, 0f, 0.8f));
                mbc.setBand(1, new DynamicsProcessing.MbcBand(true, 2500f, 18f, 220f,
                        1.8f, -25f, 8f, -80f, 1f, 0f, 0.7f));
                mbc.setBand(2, new DynamicsProcessing.MbcBand(true, 20000f, 10f, 180f,
                        1.6f, -24f, 7f, -80f, 1f, 0f, 0.1f));
            } else {
                mbc.setBand(0, new DynamicsProcessing.MbcBand(true, 250f, 16f, 220f,
                        2.4f, -25f, 9f, -80f, 1f, 0f, 2.1f));
                mbc.setBand(1, new DynamicsProcessing.MbcBand(true, 2500f, 11f, 170f,
                        2.6f, -23f, 9f, -80f, 1f, 0f, 1.8f));
                mbc.setBand(2, new DynamicsProcessing.MbcBand(true, 20000f, 6f, 130f,
                        2.2f, -22f, 8f, -80f, 1f, 0f, 1.0f));
            }
            DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                    true, true, 0, 1.5f, nightMode ? 180f : 110f,
                    12f, nightMode ? -2.5f : -1.2f, 0f);
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, false, 0, true, bands, false, 0, true)
                    .setInputGainAllChannelsTo(nightMode ? -2.5f : -3.5f)
                    .setMbcAllChannelsTo(mbc)
                    .setLimiterAllChannelsTo(limiter)
                    .build();
            dynamicsProcessing = new DynamicsProcessing(0, sessionId, config);
            dynamicsProcessing.setEnabled(true);
        } catch (Exception ignored) { releaseDynamics(); }
    }

    private void attachFmLoudness(int sessionId) {
        try {
            loudnessEnhancer = new LoudnessEnhancer(sessionId);
            loudnessEnhancer.setTargetGain(nightMode ? 250 : 900);
            loudnessEnhancer.setEnabled(true);
        } catch (Exception ignored) { releaseLoudness(); }
    }

    private void fadeFromZeroToTarget(int durationMs) {
        int generation = ++fadeGeneration;
        float target = targetAmplitude();
        int steps = Math.max(8, durationMs / 60);
        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            handler.postDelayed(() -> {
                if (generation != fadeGeneration || player == null) return;
                float value = target * currentStep / (float) steps;
                try { player.setVolume(value, value); } catch (Exception ignored) { }
            }, (long) currentStep * durationMs / steps);
        }
    }

    private void rampFromCurrentToTarget(int durationMs) {
        int generation = ++fadeGeneration;
        float target = targetAmplitude();
        int steps = Math.max(6, durationMs / 60);
        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            handler.postDelayed(() -> {
                if (generation != fadeGeneration || player == null) return;
                float progress = currentStep / (float) steps;
                float value = target * (0.75f + 0.25f * progress);
                try { player.setVolume(value, value); } catch (Exception ignored) { }
            }, (long) currentStep * durationMs / steps);
        }
    }

    private void applyVolumeImmediately() {
        fadeGeneration++;
        if (player == null || !prepared) return;
        float value = targetAmplitude();
        try { player.setVolume(value, value); } catch (Exception ignored) { }
    }

    private float targetAmplitude() {
        float safetyHeadroom = fmProcessor ? (nightMode ? 2f : 3f) : 0f;
        float totalDb = attenuationDb + profilePreampDb - safetyHeadroom;
        return (float) Math.pow(10.0, totalDb / 20.0);
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void registerDeviceCallback() {
        if (audioManager == null || Build.VERSION.SDK_INT < 23) return;
        deviceCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                if (!expectHeadphones) return;
                for (AudioDeviceInfo device : removedDevices) {
                    if (isHeadphoneType(device.getType())) {
                        handler.postDelayed(() -> {
                            if (!hasHeadphoneOutput()) pauseForDisconnectedHeadphones();
                        }, 150L);
                        break;
                    }
                }
            }
        };
        audioManager.registerAudioDeviceCallback(deviceCallback, handler);
    }

    private boolean hasHeadphoneOutput() {
        if (audioManager == null || Build.VERSION.SDK_INT < 23) return false;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isHeadphoneType(device.getType())) return true;
        }
        return false;
    }

    private boolean isHeadphoneType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET;
    }

    private void startProgressTicker() {
        handler.removeCallbacks(progressTicker);
        handler.post(progressTicker);
    }

    private void stopProgressTicker() {
        handler.removeCallbacks(progressTicker);
    }

    private boolean safeIsPlaying() {
        try { return player != null && prepared && player.isPlaying(); }
        catch (Exception ignored) { return false; }
    }

    private int safePosition() {
        try { return player == null || !prepared ? 0 : Math.max(0, player.getCurrentPosition()); }
        catch (Exception ignored) { return 0; }
    }

    private int safeDuration() {
        try { return player == null || !prepared ? 0 : Math.max(0, player.getDuration()); }
        catch (Exception ignored) { return 0; }
    }

    private String currentState() {
        if (loading) return STATE_LOADING;
        if (safeIsPlaying()) return STATE_PLAYING;
        if (player != null && prepared) return STATE_PAUSED;
        return STATE_IDLE;
    }

    private Notification buildNotification(boolean playing) {
        Intent openIntent = new Intent(this, MainActivity.class);
        Intent previousIntent = new Intent(this, PlaybackService.class).setAction(ACTION_PREVIOUS);
        Intent toggleIntent = new Intent(this, PlaybackService.class)
                .setAction(playing ? ACTION_PAUSE : ACTION_PLAY);
        Intent nextIntent = new Intent(this, PlaybackService.class).setAction(ACTION_NEXT);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);
        PendingIntent previous = PendingIntent.getService(this, 1, previousIntent, pendingFlags);
        PendingIntent toggle = PendingIntent.getService(this, 2, toggleIntent, pendingFlags);
        PendingIntent next = PendingIntent.getService(this, 3, nextIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setContentTitle(currentTitle())
                .setContentText((nightMode ? "Nocturno" : "Normal") + "  ·  " + displayDb()
                        + "  ·  " + profileName + (fmProcessor ? "  ·  FM" : ""))
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", previous)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausar" : "Reproducir", toggle)
                .addAction(android.R.drawable.ic_media_next, "Siguiente", next)
                .build();
    }

    private String currentTitle() {
        if (currentIndex >= 0 && currentIndex < names.size()) return names.get(currentIndex);
        return "Pista de audio";
    }

    private String displayDb() {
        return (attenuationDb < 0 ? "−" + Math.abs(attenuationDb) : String.valueOf(attenuationDb)) + " dB";
    }

    private void broadcastState(String message, String missingUri) {
        Intent state = new Intent(BROADCAST_STATE);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_TITLE, currentTitle());
        state.putExtra(EXTRA_PLAYING, safeIsPlaying());
        state.putExtra(EXTRA_STATE, currentState());
        state.putExtra(EXTRA_ATTENUATION_DB, attenuationDb);
        state.putExtra(EXTRA_NIGHT_MODE, nightMode);
        state.putExtra(EXTRA_FM_PROCESSOR, fmProcessor);
        state.putExtra(EXTRA_POSITION, safePosition());
        state.putExtra(EXTRA_DURATION, safeDuration());
        state.putExtra(EXTRA_CURRENT_INDEX, currentIndex);
        if (message != null) state.putExtra(EXTRA_MESSAGE, message);
        if (missingUri != null) state.putExtra(EXTRA_MISSING_URI, missingUri);
        if (statePrefs != null) {
            statePrefs.edit()
                    .putInt("last_service_index", currentIndex)
                    .putInt("last_position", safePosition())
                    .putInt("last_duration", safeDuration())
                    .putString("last_title", currentTitle())
                    .putBoolean("last_playing", safeIsPlaying())
                    .apply();
        }
        sendBroadcast(state);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Reproducción de música", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controles del reproductor RadioEnlace Audio");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void releaseEqualizer() {
        if (equalizer != null) {
            try { equalizer.release(); } catch (Exception ignored) { }
            equalizer = null;
        }
    }

    private void releaseLoudness() {
        if (loudnessEnhancer != null) {
            try { loudnessEnhancer.release(); } catch (Exception ignored) { }
            loudnessEnhancer = null;
        }
    }

    private void releaseDynamics() {
        if (dynamicsProcessing != null) {
            try { dynamicsProcessing.release(); } catch (Exception ignored) { }
            dynamicsProcessing = null;
        }
    }

    private void releaseAudioEffects() {
        releaseEqualizer();
        releaseLoudness();
        releaseDynamics();
    }

    private void releasePlayer() {
        stopProgressTicker();
        fadeGeneration++;
        loading = false;
        prepared = false;
        releaseAudioEffects();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        if (audioManager != null && deviceCallback != null && Build.VERSION.SDK_INT >= 23) {
            try { audioManager.unregisterAudioDeviceCallback(deviceCallback); } catch (Exception ignored) { }
        }
        releasePlayer();
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null && audioManager != null) {
            try { audioManager.abandonAudioFocusRequest(focusRequest); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}