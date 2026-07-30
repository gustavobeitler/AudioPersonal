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
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public final class PlaybackService extends Service {
    public static final String ACTION_PLAY = "com.gustavo.reproductorsueno.core.PLAY";
    public static final String ACTION_PAUSE = "com.gustavo.reproductorsueno.core.PAUSE";
    public static final String ACTION_STOP = "com.gustavo.reproductorsueno.core.STOP";

    private static final String CHANNEL_ID = "audio_core";
    private static final int NOTIFICATION_ID = 1120;
    private static final int DEFAULT_VOLUME_PERCENT = 50;
    private static final int MIN_VOLUME_PERCENT = 0;
    private static final int MAX_VOLUME_PERCENT = 100;

    public enum State {
        IDLE, PREPARING, PLAYING, PAUSED, STOPPED, ERROR
    }

    public interface Listener {
        void onPlaybackStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final State state;
        public final String title;
        public final int volumePercent;
        public final int positionMs;
        public final int durationMs;

        Snapshot(State state, String title, int volumePercent, int positionMs, int durationMs) {
            this.state = state;
            this.title = title;
            this.volumePercent = volumePercent;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    public final class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private MediaPlayer player;
    private Uri selectedUri;
    private String selectedTitle = "Sin canción seleccionada";
    private State state = State.IDLE;
    private int volumePercent = DEFAULT_VOLUME_PERCENT;
    private boolean prepared;
    private boolean playWhenPrepared;
    private boolean foregroundStarted;
    private Listener listener;

    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            notifyState();
            if (state == State.PLAYING) handler.postDelayed(this, 500L);
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("audio_core_v12", MODE_PRIVATE);
        volumePercent = clampVolume(prefs.getInt("volume_percent", DEFAULT_VOLUME_PERCENT));

        String uriText = prefs.getString("selected_uri", null);
        selectedTitle = prefs.getString("selected_title", "Sin canción seleccionada");
        if (uriText != null && !uriText.isEmpty()) {
            try { selectedUri = Uri.parse(uriText); } catch (Exception ignored) { selectedUri = null; }
        }

        createNotificationChannel();
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, filter);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        notifyState();
    }

    public void setSelectionIfEmpty(Uri uri, String title) {
        if (selectedUri == null && uri != null) select(uri, title);
    }

    public void select(Uri uri, String title) {
        if (uri == null) return;
        boolean same = selectedUri != null && selectedUri.equals(uri);
        selectedUri = uri;
        selectedTitle = title == null || title.isEmpty() ? "Pista de audio" : title;
        prefs.edit()
                .putString("selected_uri", uri.toString())
                .putString("selected_title", selectedTitle)
                .apply();

        if (!same) {
            releasePlayer();
            state = State.IDLE;
        }
        notifyState();
    }

    public void play(Uri uri, String title) {
        if (uri == null) {
            state = State.ERROR;
            notifyState();
            return;
        }

        boolean sameTrack = selectedUri != null && selectedUri.equals(uri);
        if (!sameTrack) select(uri, title);

        if (state == State.PREPARING) {
            playWhenPrepared = true;
            notifyState();
            return;
        }

        if (player != null && prepared && sameTrack) {
            try {
                applyVolumeBeforePlayback();
                player.start();
                state = State.PLAYING;
                startOrUpdateForeground();
                startProgressTicker();
                notifyState();
            } catch (Exception error) {
                state = State.ERROR;
                notifyState();
            }
            return;
        }

        prepareSelectedTrack(0, true);
    }

    public void pause() {
        playWhenPrepared = false;
        handler.removeCallbacks(progressTicker);

        if (player != null && prepared) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Exception ignored) { }
        }

        if (state != State.IDLE && state != State.STOPPED && state != State.ERROR) {
            state = State.PAUSED;
        }
        startOrUpdateForeground();
        notifyState();
    }

    public void stopPlayback() {
        playWhenPrepared = false;
        releasePlayer();
        state = State.STOPPED;
        if (foregroundStarted) {
            stopForeground(true);
            foregroundStarted = false;
        }
        notifyState();
    }

    public void seekTo(int positionMs) {
        if (player == null || !prepared) return;
        try {
            int duration = Math.max(0, player.getDuration());
            player.seekTo(Math.max(0, Math.min(duration, positionMs)));
            notifyState();
        } catch (Exception ignored) { }
    }

    public void setVolumePercent(int percent) {
        volumePercent = clampVolume(percent);
        prefs.edit().putInt("volume_percent", volumePercent).apply();
        applyVolumeImmediately();
        startOrUpdateForeground();
        notifyState();
    }

    public Snapshot getSnapshot() {
        return new Snapshot(state, selectedTitle, volumePercent, safePosition(), safeDuration());
    }

    private void prepareSelectedTrack(int resumePositionMs, boolean shouldPlay) {
        if (selectedUri == null) {
            state = State.ERROR;
            notifyState();
            return;
        }

        releasePlayer();
        prepared = false;
        playWhenPrepared = shouldPlay;
        state = State.PREPARING;
        notifyState();

        try {
            MediaPlayer newPlayer = new MediaPlayer();
            player = newPlayer;
            newPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            newPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);

            // Evita cualquier golpe de volumen antes de cargar el valor elegido.
            newPlayer.setVolume(0f, 0f);
            newPlayer.setDataSource(this, selectedUri);

            newPlayer.setOnPreparedListener(mp -> {
                if (player != mp) return;
                prepared = true;

                try {
                    int duration = Math.max(0, mp.getDuration());
                    if (resumePositionMs > 0 && resumePositionMs < duration) {
                        mp.seekTo(resumePositionMs);
                    }
                } catch (Exception ignored) { }

                // El porcentaje seleccionado se aplica antes de cualquier start().
                applyVolumeBeforePlayback();

                if (playWhenPrepared) {
                    try {
                        mp.start();
                        state = State.PLAYING;
                        startOrUpdateForeground();
                        startProgressTicker();
                    } catch (Exception error) {
                        state = State.ERROR;
                    }
                } else {
                    state = State.PAUSED;
                    startOrUpdateForeground();
                }
                notifyState();
            });

            newPlayer.setOnCompletionListener(mp -> {
                handler.removeCallbacks(progressTicker);
                state = State.STOPPED;
                if (foregroundStarted) {
                    stopForeground(true);
                    foregroundStarted = false;
                }
                notifyState();
            });

            newPlayer.setOnErrorListener((mp, what, extra) -> {
                handler.removeCallbacks(progressTicker);
                prepared = false;
                state = State.ERROR;
                notifyState();
                return true;
            });

            newPlayer.prepareAsync();
        } catch (Exception error) {
            releasePlayer();
            state = State.ERROR;
            notifyState();
        }
    }

    private void applyVolumeBeforePlayback() {
        if (player == null) return;
        float amplitude = volumePercent / 100f;
        try { player.setVolume(amplitude, amplitude); } catch (Exception ignored) { }
    }

    private void applyVolumeImmediately() {
        if (player == null || !prepared) return;
        float amplitude = volumePercent / 100f;
        try { player.setVolume(amplitude, amplitude); } catch (Exception ignored) { }
    }

    private int clampVolume(int percent) {
        return Math.max(MIN_VOLUME_PERCENT, Math.min(MAX_VOLUME_PERCENT, percent));
    }

    private int safePosition() {
        try { return player != null && prepared ? Math.max(0, player.getCurrentPosition()) : 0; }
        catch (Exception ignored) { return 0; }
    }

    private int safeDuration() {
        try { return player != null && prepared ? Math.max(0, player.getDuration()) : 0; }
        catch (Exception ignored) { return 0; }
    }

    private void startProgressTicker() {
        handler.removeCallbacks(progressTicker);
        handler.post(progressTicker);
    }

    private void notifyState() {
        Listener current = listener;
        if (current != null) current.onPlaybackStateChanged(getSnapshot());
    }

    private void startOrUpdateForeground() {
        if (state == State.IDLE || state == State.STOPPED || state == State.ERROR) return;
        Notification notification = buildNotification();
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification);
            foregroundStarted = true;
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent, pendingFlags);

        String transportAction = state == State.PLAYING ? ACTION_PAUSE : ACTION_PLAY;
        Intent transportIntent = new Intent(this, PlaybackService.class).setAction(transportAction);
        PendingIntent transport = Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, 2, transportIntent, pendingFlags)
                : PendingIntent.getService(this, 2, transportIntent, pendingFlags);

        Intent stopIntent = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 3, stopIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setContentTitle(selectedTitle)
                .setContentText((state == State.PLAYING ? "Reproduciendo" : "En pausa")
                        + " · Volumen " + volumePercent + "%")
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(state == State.PLAYING)
                .addAction(state == State.PLAYING
                                ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        state == State.PLAYING ? "Pausa" : "Play", transport)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Reproducción de música", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controles del núcleo del reproductor");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void releasePlayer() {
        handler.removeCallbacks(progressTicker);
        prepared = false;
        if (player != null) {
            try { player.setVolume(0f, 0f); } catch (Exception ignored) { }
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                if (selectedUri != null) play(selectedUri, selectedTitle);
            } else if (ACTION_PAUSE.equals(action)) {
                pause();
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) { }
        releasePlayer();
        super.onDestroy();
    }
}
