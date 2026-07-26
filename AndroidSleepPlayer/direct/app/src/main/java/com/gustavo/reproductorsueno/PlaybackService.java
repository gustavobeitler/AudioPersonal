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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Random;

public class PlaybackService extends Service {
    public static final String ACTION_PLAY_INDEX = "com.gustavo.reproductorsueno.PLAY_INDEX";
    public static final String ACTION_TOGGLE = "com.gustavo.reproductorsueno.TOGGLE";
    public static final String ACTION_NEXT = "com.gustavo.reproductorsueno.NEXT";
    public static final String ACTION_PREVIOUS = "com.gustavo.reproductorsueno.PREVIOUS";
    public static final String ACTION_SETTINGS = "com.gustavo.reproductorsueno.SETTINGS";
    public static final String BROADCAST_STATE = "com.gustavo.reproductorsueno.STATE";

    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_ATTENUATION_DB = "attenuation_db";
    public static final String EXTRA_PROFILE = "profile";
    public static final String EXTRA_SHUFFLE = "shuffle";
    public static final String EXTRA_NIGHT_MODE = "night_mode";
    public static final String EXTRA_FM_PROCESSOR = "fm_processor";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PLAYING = "playing";

    private static final String CHANNEL_ID = "radioenlace_player";
    private static final int NOTIFICATION_ID = 1040;

    private final ArrayList<String> uris = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private MediaPlayer player;
    private Equalizer equalizer;
    private LoudnessEnhancer loudnessEnhancer;
    private DynamicsProcessing dynamicsProcessing;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int currentIndex = 0;
    private int attenuationDb = -40;
    private boolean sunvitoProfile = true;
    private boolean shuffle = false;
    private boolean nightMode = true;
    private boolean fmProcessor = false;
    private int fadeGeneration = 0;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pausePlayback();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        updatePlaylist(intent);
        updateSettings(intent);

        String action = intent.getAction();
        if (ACTION_PLAY_INDEX.equals(action)) {
            startTrack(intent.getIntExtra(EXTRA_INDEX, 0));
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_NEXT.equals(action)) {
            nextTrack();
        } else if (ACTION_PREVIOUS.equals(action)) {
            previousTrack();
        } else if (ACTION_SETTINGS.equals(action)) {
            applyCurrentSettings(true);
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
        if (currentIndex >= uris.size()) currentIndex = 0;
    }

    private void updateSettings(Intent intent) {
        if (intent.hasExtra(EXTRA_ATTENUATION_DB)) {
            attenuationDb = Math.max(-60, Math.min(0,
                    intent.getIntExtra(EXTRA_ATTENUATION_DB, nightMode ? -40 : -8)));
        }
        if (intent.hasExtra(EXTRA_PROFILE)) sunvitoProfile = intent.getBooleanExtra(EXTRA_PROFILE, true);
        if (intent.hasExtra(EXTRA_SHUFFLE)) shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false);
        if (intent.hasExtra(EXTRA_NIGHT_MODE)) nightMode = intent.getBooleanExtra(EXTRA_NIGHT_MODE, true);
        if (intent.hasExtra(EXTRA_FM_PROCESSOR)) fmProcessor = intent.getBooleanExtra(EXTRA_FM_PROCESSOR, false);
    }

    private void togglePlayback() {
        if (player == null) {
            if (!uris.isEmpty()) startTrack(currentIndex);
            return;
        }
        if (player.isPlaying()) {
            pausePlayback();
        } else {
            requestAudioFocus();
            player.start();
            fadeToTarget(800);
            startForeground(NOTIFICATION_ID, buildNotification(true));
            broadcastState(true);
        }
    }

    private void pausePlayback() {
        if (player != null && player.isPlaying()) player.pause();
        if (player != null) {
            startForeground(NOTIFICATION_ID, buildNotification(false));
            broadcastState(false);
        }
    }

    private void startTrack(int index) {
        if (uris.isEmpty()) return;
        if (index < 0 || index >= uris.size()) index = 0;
        currentIndex = index;
        releasePlayer();

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this, Uri.parse(uris.get(currentIndex)));
            player.setOnPreparedListener(mp -> {
                attachAudioProcessing();
                requestAudioFocus();
                mp.setVolume(0f, 0f);
                mp.start();
                fadeToTarget(nightMode ? 3000 : 1400);
                startForeground(NOTIFICATION_ID, buildNotification(true));
                broadcastState(true);
            });
            player.setOnCompletionListener(mp -> nextTrack());
            player.setOnErrorListener((mp, what, extra) -> {
                handler.postDelayed(this::nextTrack, 400);
                return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            handler.postDelayed(this::nextTrack, 400);
        }
    }

    private void nextTrack() {
        if (uris.isEmpty()) return;
        int next;
        if (shuffle && uris.size() > 1) {
            do { next = random.nextInt(uris.size()); } while (next == currentIndex);
        } else {
            next = (currentIndex + 1) % uris.size();
        }
        startTrack(next);
    }

    private void previousTrack() {
        if (uris.isEmpty()) return;
        int previous = currentIndex <= 0 ? uris.size() - 1 : currentIndex - 1;
        startTrack(previous);
    }

    private void applyCurrentSettings(boolean smooth) {
        if (player == null) return;
        attachAudioProcessing();
        if (smooth && player.isPlaying()) fadeToTarget(900);
        else applyVolumeImmediately();
        startForeground(NOTIFICATION_ID, buildNotification(player.isPlaying()));
        broadcastState(player.isPlaying());
    }

    private void attachAudioProcessing() {
        releaseAudioEffects();
        if (player == null) return;
        int sessionId = player.getAudioSessionId();

        if (sunvitoProfile || fmProcessor) attachEqualizer(sessionId);
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
                int requested = requestedEqMilliBel(centerHz);
                short safe = (short) Math.max(range[0], Math.min(range[1], requested));
                equalizer.setBandLevel(band, safe);
            }
            equalizer.setEnabled(true);
        } catch (Exception ignored) {
            releaseEqualizer();
        }
    }

    private int requestedEqMilliBel(int centerHz) {
        int value = 0;
        if (sunvitoProfile) {
            if (nightMode) {
                if (centerHz < 120) value -= 900;
                else if (centerHz < 500) value -= 1200;
                else if (centerHz < 2000) value -= 200;
                else if (centerHz < 6000) value -= 1500;
                else value -= 2200;
            } else {
                if (centerHz < 120) value += 300;
                else if (centerHz < 500) value -= 400;
                else if (centerHz < 2000) value += 100;
                else if (centerHz < 6000) value -= 500;
                else value -= 800;
            }
        }
        if (fmProcessor) {
            if (centerHz < 120) value += nightMode ? 500 : 1100;
            else if (centerHz < 500) value += nightMode ? 100 : 300;
            else if (centerHz < 2000) value += nightMode ? 200 : 600;
            else if (centerHz < 6000) value += nightMode ? 250 : 900;
            else value += nightMode ? -100 : 350;
        }
        return value;
    }

    private void attachFmDynamics(int sessionId) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            int bands = 3;
            DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, bands);
            if (nightMode) {
                mbc.setBand(0, new DynamicsProcessing.MbcBand(true, 250f, 24f, 260f,
                        1.7f, -27f, 8f, -80f, 1f, 0f, 1.0f));
                mbc.setBand(1, new DynamicsProcessing.MbcBand(true, 2500f, 18f, 220f,
                        1.8f, -25f, 8f, -80f, 1f, 0f, 0.8f));
                mbc.setBand(2, new DynamicsProcessing.MbcBand(true, 20000f, 10f, 180f,
                        1.6f, -24f, 7f, -80f, 1f, 0f, 0.2f));
            } else {
                mbc.setBand(0, new DynamicsProcessing.MbcBand(true, 250f, 16f, 220f,
                        2.4f, -25f, 9f, -80f, 1f, 0f, 2.3f));
                mbc.setBand(1, new DynamicsProcessing.MbcBand(true, 2500f, 11f, 170f,
                        2.6f, -23f, 9f, -80f, 1f, 0f, 2.0f));
                mbc.setBand(2, new DynamicsProcessing.MbcBand(true, 20000f, 6f, 130f,
                        2.2f, -22f, 8f, -80f, 1f, 0f, 1.2f));
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
        } catch (Exception ignored) {
            releaseDynamics();
        }
    }

    private void attachFmLoudness(int sessionId) {
        try {
            loudnessEnhancer = new LoudnessEnhancer(sessionId);
            loudnessEnhancer.setTargetGain(nightMode ? 300 : 1000);
            loudnessEnhancer.setEnabled(true);
        } catch (Exception ignored) {
            releaseLoudness();
        }
    }

    private void fadeToTarget(int durationMs) {
        int generation = ++fadeGeneration;
        float target = targetAmplitude();
        int steps = Math.max(8, durationMs / 60);
        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            handler.postDelayed(() -> {
                if (generation != fadeGeneration || player == null) return;
                float progress = currentStep / (float) steps;
                float value = target * progress;
                try { player.setVolume(value, value); } catch (Exception ignored) { }
            }, (long) currentStep * durationMs / steps);
        }
    }

    private void applyVolumeImmediately() {
        fadeGeneration++;
        if (player == null) return;
        float value = targetAmplitude();
        try { player.setVolume(value, value); } catch (Exception ignored) { }
    }

    private float targetAmplitude() {
        int safetyHeadroom = fmProcessor ? (nightMode ? 2 : 3) : 0;
        return (float) Math.pow(10.0, (attenuationDb - safetyHeadroom) / 20.0);
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        AudioManager.OnAudioFocusChangeListener listener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausePlayback();
        };
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private Notification buildNotification(boolean playing) {
        Intent openIntent = new Intent(this, MainActivity.class);
        Intent previousIntent = new Intent(this, PlaybackService.class).setAction(ACTION_PREVIOUS);
        Intent toggleIntent = new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE);
        Intent nextIntent = new Intent(this, PlaybackService.class).setAction(ACTION_NEXT);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);
        PendingIntent previous = PendingIntent.getService(this, 1, previousIntent, pendingFlags);
        PendingIntent toggle = PendingIntent.getService(this, 2, toggleIntent, pendingFlags);
        PendingIntent next = PendingIntent.getService(this, 3, nextIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setContentTitle(currentTitle())
                .setContentText((nightMode ? "Nocturno" : "Normal") + "  ·  " + displayDb()
                        + (fmProcessor ? "  ·  FM" : ""))
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

    private void broadcastState(boolean playing) {
        Intent state = new Intent(BROADCAST_STATE);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_TITLE, currentTitle());
        state.putExtra(EXTRA_PLAYING, playing);
        state.putExtra(EXTRA_ATTENUATION_DB, attenuationDb);
        state.putExtra(EXTRA_NIGHT_MODE, nightMode);
        state.putExtra(EXTRA_FM_PROCESSOR, fmProcessor);
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
        fadeGeneration++;
        releaseAudioEffects();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        releasePlayer();
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null && audioManager != null) {
            try { audioManager.abandonAudioFocusRequest(focusRequest); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
