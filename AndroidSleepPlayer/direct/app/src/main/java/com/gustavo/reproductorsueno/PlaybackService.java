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
import android.media.audiofx.Equalizer;
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
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PLAYING = "playing";

    private static final String CHANNEL_ID = "sleep_player";
    private static final int NOTIFICATION_ID = 1040;

    private final ArrayList<String> uris = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private MediaPlayer player;
    private Equalizer equalizer;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int currentIndex = 0;
    private int attenuationDb = -40;
    private boolean sunvitoProfile = true;
    private boolean shuffle = false;
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
            int requested = intent.getIntExtra(EXTRA_INDEX, 0);
            startTrack(requested);
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_NEXT.equals(action)) {
            nextTrack();
        } else if (ACTION_PREVIOUS.equals(action)) {
            previousTrack();
        } else if (ACTION_SETTINGS.equals(action)) {
            applyCurrentSettings();
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
            attenuationDb = Math.max(-60, Math.min(-20,
                    intent.getIntExtra(EXTRA_ATTENUATION_DB, -40)));
        }
        if (intent.hasExtra(EXTRA_PROFILE)) {
            sunvitoProfile = intent.getBooleanExtra(EXTRA_PROFILE, true);
        }
        if (intent.hasExtra(EXTRA_SHUFFLE)) {
            shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false);
        }
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
            applyVolumeImmediately();
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
                attachSunvitoProfile();
                requestAudioFocus();
                mp.setVolume(0f, 0f);
                mp.start();
                fadeToTarget();
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
            do {
                next = random.nextInt(uris.size());
            } while (next == currentIndex);
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

    private void applyCurrentSettings() {
        if (player == null) return;
        attachSunvitoProfile();
        applyVolumeImmediately();
        startForeground(NOTIFICATION_ID, buildNotification(player.isPlaying()));
        broadcastState(player.isPlaying());
    }

    private void attachSunvitoProfile() {
        releaseEqualizer();
        if (!sunvitoProfile || player == null) return;

        try {
            equalizer = new Equalizer(0, player.getAudioSessionId());
            short[] range = equalizer.getBandLevelRange();
            short bandCount = equalizer.getNumberOfBands();
            for (short band = 0; band < bandCount; band++) {
                int centerHz = equalizer.getCenterFreq(band) / 1000;
                int requested = profileCutMilliBel(centerHz);
                short safe = (short) Math.max(range[0], Math.min(range[1], requested));
                equalizer.setBandLevel(band, safe);
            }
            equalizer.setEnabled(true);
        } catch (Exception ignored) {
            releaseEqualizer();
        }
    }

    private int profileCutMilliBel(int centerHz) {
        if (centerHz < 120) return -1000;
        if (centerHz < 500) return -1500;
        if (centerHz < 2000) return 0;
        if (centerHz < 6000) return -1800;
        return -2500;
    }

    private void fadeToTarget() {
        int generation = ++fadeGeneration;
        float target = targetAmplitude();
        int steps = 30;
        for (int step = 1; step <= steps; step++) {
            final int currentStep = step;
            handler.postDelayed(() -> {
                if (generation != fadeGeneration || player == null) return;
                float value = target * currentStep / steps;
                try { player.setVolume(value, value); } catch (Exception ignored) { }
            }, step * 100L);
        }
    }

    private void applyVolumeImmediately() {
        fadeGeneration++;
        if (player == null) return;
        float value = targetAmplitude();
        try { player.setVolume(value, value); } catch (Exception ignored) { }
    }

    private float targetAmplitude() {
        return (float) Math.pow(10.0, attenuationDb / 20.0);
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        AudioManager.OnAudioFocusChangeListener listener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pausePlayback();
            }
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
        Intent previousIntent = new Intent(this, PlaybackService.class).setAction(ACTION_PREVIOUS);
        Intent toggleIntent = new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE);
        Intent nextIntent = new Intent(this, PlaybackService.class).setAction(ACTION_NEXT);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent previous = PendingIntent.getService(this, 1, previousIntent, pendingFlags);
        PendingIntent toggle = PendingIntent.getService(this, 2, toggleIntent, pendingFlags);
        PendingIntent next = PendingIntent.getService(this, 3, nextIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Reproductor Sueño")
                .setContentText(currentTitle() + "  ·  " + displayDb())
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
        sendBroadcast(state);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Reproducción nocturna", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controles del reproductor durante la noche");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void releaseEqualizer() {
        if (equalizer != null) {
            try { equalizer.release(); } catch (Exception ignored) { }
            equalizer = null;
        }
    }

    private void releasePlayer() {
        fadeGeneration++;
        releaseEqualizer();
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
        if (Build.VERSION.SDK_INT >= 26 && audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
