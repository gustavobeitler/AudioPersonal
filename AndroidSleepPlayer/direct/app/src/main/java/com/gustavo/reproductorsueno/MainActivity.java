package com.gustavo.reproductorsueno;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity implements PlaybackService.Listener {
    private static final int PICK_AUDIO = 1201;
    private static final int DEFAULT_VOLUME_PERCENT = 50;
    private static final int MIN_VOLUME_PERCENT = 0;
    private static final int MAX_VOLUME_PERCENT = 100;
    private static final int PHYSICAL_BUTTON_STEP = 5;

    private static final int BG = Color.rgb(5, 13, 21);
    private static final int PANEL = Color.rgb(15, 34, 48);
    private static final int PANEL2 = Color.rgb(20, 44, 59);
    private static final int CYAN = Color.rgb(39, 203, 216);
    private static final int GREEN = Color.rgb(58, 210, 142);
    private static final int TEXT = Color.rgb(238, 245, 248);
    private static final int MUTED = Color.rgb(156, 178, 192);
    private static final int RED = Color.rgb(239, 96, 96);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PlaybackService service;
    private boolean bound;
    private boolean userSeeking;
    private long lastVolumeKeyMs;
    private Uri selectedUri;
    private String selectedTitle = "Sin canción seleccionada";
    private int volumePercent = DEFAULT_VOLUME_PERCENT;

    private TextView titleView;
    private TextView statusView;
    private TextView volumeValueView;
    private TextView elapsedView;
    private TextView durationView;
    private SeekBar volumeBar;
    private SeekBar progressBar;
    private Button playButton;
    private Button pauseButton;
    private Button stopButton;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (bound && service != null) {
                render(service.getSnapshot());
                handler.postDelayed(this, 500L);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            bound = true;
            service.setListener(MainActivity.this);
            service.setVolumePercent(volumePercent);
            if (selectedUri != null) service.setSelectionIfEmpty(selectedUri, selectedTitle);
            render(service.getSnapshot());
            handler.post(ticker);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            handler.removeCallbacks(ticker);
            bound = false;
            service = null;
            statusView.setText("MOTOR DESCONECTADO");
            statusView.setTextColor(RED);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        prefs = getSharedPreferences("audio_core_v12", MODE_PRIVATE);
        if (!prefs.getBoolean("initialized", false)) {
            SharedPreferences old = getSharedPreferences("safe_audio_core_v11", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit()
                    .putBoolean("initialized", true)
                    .putInt("volume_percent", DEFAULT_VOLUME_PERCENT);
            String oldUri = old.getString("selected_uri", null);
            String oldTitle = old.getString("selected_title", null);
            if (oldUri != null && !oldUri.isEmpty()) editor.putString("selected_uri", oldUri);
            if (oldTitle != null && !oldTitle.isEmpty()) editor.putString("selected_title", oldTitle);
            editor.apply();
        }

        volumePercent = clampVolume(prefs.getInt("volume_percent", DEFAULT_VOLUME_PERCENT));
        String uriText = prefs.getString("selected_uri", null);
        selectedTitle = prefs.getString("selected_title", selectedTitle);
        if (uriText != null) {
            try { selectedUri = Uri.parse(uriText); } catch (Exception ignored) { }
        }

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1202);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, PlaybackService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(ticker);
        if (bound) {
            if (service != null) service.setListener(null);
            try { unbindService(connection); } catch (Exception ignored) { }
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(14), dp(6) + top, dp(14), dp(6) + bottom);
            return insets;
        });

        root.addView(label("REPRODUCTOR DE MÚSICA", 22, TEXT, true), mw());
        root.addView(label("BETA 0.12 · VOLUMEN ESTÁNDAR", 11, CYAN, true), mw());

        TextView safety = label(
                "Volumen normal de 0 a 100%. Inicio: 50%. El valor se aplica antes de comenzar el audio.",
                12, GREEN, true);
        safety.setPadding(dp(10), dp(8), dp(10), dp(8));
        safety.setBackground(box(Color.rgb(17, 55, 54), GREEN));
        LinearLayout.LayoutParams safetyParams = mw();
        safetyParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(safety, safetyParams);

        LinearLayout player = card();
        player.setGravity(Gravity.CENTER_HORIZONTAL);

        Button choose = button("SELECCIONAR UNA CANCIÓN");
        choose.setOnClickListener(v -> chooseAudio());
        player.addView(choose, mh(44));

        statusView = label("DETENIDO", 11, MUTED, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(8), 0, dp(3));
        player.addView(statusView, mw());

        titleView = label(selectedTitle, 18, TEXT, true);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        player.addView(titleView, mw());

        progressBar = new SeekBar(this);
        progressBar.setMax(1000);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (bound && service != null) {
                    PlaybackService.Snapshot snapshot = service.getSnapshot();
                    if (snapshot.durationMs > 0) {
                        service.seekTo(Math.round(snapshot.durationMs * bar.getProgress() / 1000f));
                    }
                }
            }
        });
        player.addView(progressBar, mw());

        LinearLayout times = row();
        elapsedView = label("0:00", 11, MUTED, false);
        durationView = label("0:00", 11, MUTED, false);
        times.addView(elapsedView, new LinearLayout.LayoutParams(0, -2, 1f));
        durationView.setGravity(Gravity.END);
        times.addView(durationView, new LinearLayout.LayoutParams(0, -2, 1f));
        player.addView(times, mw());

        LinearLayout controls = row();
        playButton = button("PLAY");
        playButton.setBackground(box(GREEN, GREEN));
        playButton.setTextColor(Color.rgb(3, 22, 18));
        playButton.setOnClickListener(v -> play());
        pauseButton = button("PAUSA");
        pauseButton.setOnClickListener(v -> pause());
        stopButton = button("DETENER");
        stopButton.setBackground(box(Color.rgb(88, 35, 42), RED));
        stopButton.setOnClickListener(v -> stop());

        addWeighted(controls, playButton);
        controls.addView(space(7), new LinearLayout.LayoutParams(dp(7), 1));
        addWeighted(controls, pauseButton);
        controls.addView(space(7), new LinearLayout.LayoutParams(dp(7), 1));
        addWeighted(controls, stopButton);
        LinearLayout.LayoutParams controlsParams = mw();
        controlsParams.setMargins(0, dp(8), 0, 0);
        player.addView(controls, controlsParams);

        root.addView(player, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout volume = card();
        LinearLayout head = row();
        head.addView(label("VOLUMEN DE LA APLICACIÓN", 12, CYAN, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        volumeValueView = label(formatPercent(volumePercent), 23, CYAN, true);
        volumeValueView.setGravity(Gravity.END);
        head.addView(volumeValueView, new LinearLayout.LayoutParams(dp(105), -2));
        volume.addView(head, mw());

        volumeBar = new SeekBar(this);
        volumeBar.setMax(MAX_VOLUME_PERCENT);
        volumeBar.setProgress(volumePercent);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int percent = clampVolume(progress);
                volumeValueView.setText(formatPercent(percent));
                if (fromUser) applyVolume(percent);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                applyVolume(clampVolume(bar.getProgress()));
            }
        });
        volume.addView(volumeBar, mw());

        TextView note = label(
                "Los botones físicos mueven este mismo control en pasos de 5%. Los perfiles se agregarán después.",
                11, MUTED, false);
        note.setMaxLines(2);
        volume.addView(note, mw());

        LinearLayout.LayoutParams volumeParams = mw();
        volumeParams.setMargins(0, dp(8), 0, 0);
        root.addView(volume, volumeParams);
        setContentView(root);
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        selectedUri = uri;
        selectedTitle = resolveName(uri);
        prefs.edit().putString("selected_uri", uri.toString())
                .putString("selected_title", selectedTitle).apply();
        titleView.setText(selectedTitle);
        statusView.setText("LISTO PARA REPRODUCIR");
        if (bound && service != null) service.select(uri, selectedTitle);
    }

    private void play() {
        if (selectedUri == null) {
            chooseAudio();
            return;
        }
        if (!bound || service == null) {
            Toast.makeText(this, "El motor de audio todavía se está iniciando", Toast.LENGTH_SHORT).show();
            return;
        }
        service.setVolumePercent(volumePercent);
        service.play(selectedUri, selectedTitle);
    }

    private void pause() {
        if (bound && service != null) service.pause();
    }

    private void stop() {
        if (bound && service != null) service.stopPlayback();
    }

    private void applyVolume(int percent) {
        volumePercent = clampVolume(percent);
        prefs.edit().putInt("volume_percent", volumePercent).apply();
        if (volumeBar != null && volumeBar.getProgress() != volumePercent) {
            volumeBar.setProgress(volumePercent);
        }
        if (volumeValueView != null) volumeValueView.setText(formatPercent(volumePercent));
        if (bound && service != null) service.setVolumePercent(volumePercent);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                long now = event.getEventTime();
                if (event.getRepeatCount() == 0 || now - lastVolumeKeyMs >= 140L) {
                    lastVolumeKeyMs = now;
                    int delta = code == KeyEvent.KEYCODE_VOLUME_UP
                            ? PHYSICAL_BUTTON_STEP : -PHYSICAL_BUTTON_STEP;
                    applyVolume(volumePercent + delta);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onPlaybackStateChanged(PlaybackService.Snapshot snapshot) {
        runOnUiThread(() -> render(snapshot));
    }

    private void render(PlaybackService.Snapshot snapshot) {
        if (snapshot == null) return;
        if (snapshot.title != null && !snapshot.title.isEmpty()) titleView.setText(snapshot.title);
        statusView.setText(stateText(snapshot.state));
        statusView.setTextColor(snapshot.state == PlaybackService.State.ERROR ? RED
                : snapshot.state == PlaybackService.State.PLAYING ? GREEN : MUTED);
        playButton.setEnabled(snapshot.state != PlaybackService.State.PREPARING);
        pauseButton.setEnabled(snapshot.state == PlaybackService.State.PLAYING
                || snapshot.state == PlaybackService.State.PREPARING);
        stopButton.setEnabled(snapshot.state != PlaybackService.State.IDLE
                && snapshot.state != PlaybackService.State.STOPPED);

        if (!userSeeking) {
            int progress = snapshot.durationMs <= 0
                    ? 0 : Math.round(snapshot.positionMs * 1000f / snapshot.durationMs);
            progressBar.setProgress(Math.max(0, Math.min(1000, progress)));
        }
        elapsedView.setText(time(snapshot.positionMs));
        durationView.setText(time(snapshot.durationMs));
        volumePercent = clampVolume(snapshot.volumePercent);
        if (volumeBar.getProgress() != volumePercent) volumeBar.setProgress(volumePercent);
        volumeValueView.setText(formatPercent(volumePercent));
    }

    private String stateText(PlaybackService.State state) {
        switch (state) {
            case PREPARING: return "CARGANDO";
            case PLAYING: return "REPRODUCIENDO";
            case PAUSED: return "EN PAUSA";
            case STOPPED: return "DETENIDO";
            case ERROR: return "ERROR DE REPRODUCCIÓN";
            default: return "LISTO";
        }
    }

    private String resolveName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return cleanTitle(name);
                }
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "Pista de audio" : cleanTitle(uri.getLastPathSegment());
    }

    private String cleanTitle(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 && value.length() - dot <= 6 ? value.substring(0, dot) : value;
    }

    private int clampVolume(int percent) {
        return Math.max(MIN_VOLUME_PERCENT, Math.min(MAX_VOLUME_PERCENT, percent));
    }

    private String formatPercent(int percent) {
        return percent + " %";
    }

    private String time(int ms) {
        int total = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(box(PANEL, Color.rgb(35, 72, 90)));
        return view;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextSize(12);
        view.setTextColor(TEXT);
        view.setBackground(box(PANEL2, Color.rgb(45, 121, 203)));
        return view;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void addWeighted(LinearLayout parent, View child) {
        parent.addView(child, new LinearLayout.LayoutParams(0, dp(50), 1f));
    }

    private View space(int widthDp) {
        View view = new View(this);
        view.setMinimumWidth(dp(widthDp));
        return view;
    }

    private LinearLayout.LayoutParams mw() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams mh(int height) { return new LinearLayout.LayoutParams(-1, dp(height)); }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
