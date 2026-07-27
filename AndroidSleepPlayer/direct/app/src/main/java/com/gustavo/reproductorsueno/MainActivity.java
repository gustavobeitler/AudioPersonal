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
    private static final int PICK_AUDIO = 1101;
    private static final int DEFAULT_DB = -24;
    private static final int MIN_DB = -60;
    private static final int MAX_DB = -6;
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
    private int attenuationDb = DEFAULT_DB;

    private TextView titleView;
    private TextView statusView;
    private TextView dbView;
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
            service.setAttenuationDb(attenuationDb);
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
        prefs = getSharedPreferences("safe_audio_core_v11", MODE_PRIVATE);
        if (!prefs.getBoolean("initialized", false)) {
            prefs.edit().clear().putBoolean("initialized", true)
                    .putInt("attenuation_db", DEFAULT_DB).apply();
        }
        attenuationDb = clamp(prefs.getInt("attenuation_db", DEFAULT_DB));
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
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1102);
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
        root.addView(label("BETA 0.11 · NÚCLEO SEGURO", 11, CYAN, true), mw());

        TextView safety = label(
                "El audio nace en silencio y recibe la atenuación antes de Play. Máximo: −6 dB.",
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
                    PlaybackService.Snapshot s = service.getSnapshot();
                    if (s.durationMs > 0) {
                        service.seekTo(Math.round(s.durationMs * bar.getProgress() / 1000f));
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
        head.addView(label("ATENUACIÓN DIGITAL", 12, CYAN, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        dbView = label(formatDb(attenuationDb), 23, CYAN, true);
        dbView.setGravity(Gravity.END);
        head.addView(dbView, new LinearLayout.LayoutParams(dp(105), -2));
        volume.addView(head, mw());

        volumeBar = new SeekBar(this);
        volumeBar.setMax(MAX_DB - MIN_DB);
        volumeBar.setProgress(attenuationDb - MIN_DB);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                int db = clamp(MIN_DB + p);
                dbView.setText(formatDb(db));
                if (fromUser) applyDb(db);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                applyDb(clamp(MIN_DB + bar.getProgress()));
            }
        });
        volume.addView(volumeBar, mw());

        TextView note = label(
                "Los botones físicos mueven este mismo control en pasos de 1 dB. No son dB(A) reales.",
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
        service.setAttenuationDb(attenuationDb);
        service.play(selectedUri, selectedTitle);
    }

    private void pause() {
        if (bound && service != null) service.pause();
    }

    private void stop() {
        if (bound && service != null) service.stopPlayback();
    }

    private void applyDb(int db) {
        attenuationDb = clamp(db);
        prefs.edit().putInt("attenuation_db", attenuationDb).apply();
        if (volumeBar != null && volumeBar.getProgress() != attenuationDb - MIN_DB) {
            volumeBar.setProgress(attenuationDb - MIN_DB);
        }
        if (dbView != null) dbView.setText(formatDb(attenuationDb));
        if (bound && service != null) service.setAttenuationDb(attenuationDb);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                long now = event.getEventTime();
                if (event.getRepeatCount() == 0 || now - lastVolumeKeyMs >= 140L) {
                    lastVolumeKeyMs = now;
                    applyDb(attenuationDb + (code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1));
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onPlaybackStateChanged(PlaybackService.Snapshot snapshot) {
        runOnUiThread(() -> render(snapshot));
    }

    private void render(PlaybackService.Snapshot s) {
        if (s == null) return;
        if (s.title != null && !s.title.isEmpty()) titleView.setText(s.title);
        statusView.setText(stateText(s.state));
        statusView.setTextColor(s.state == PlaybackService.State.ERROR ? RED
                : s.state == PlaybackService.State.PLAYING ? GREEN : MUTED);
        playButton.setEnabled(s.state != PlaybackService.State.PREPARING);
        pauseButton.setEnabled(s.state == PlaybackService.State.PLAYING
                || s.state == PlaybackService.State.PREPARING);
        stopButton.setEnabled(s.state != PlaybackService.State.IDLE
                && s.state != PlaybackService.State.STOPPED);

        if (!userSeeking) {
            int p = s.durationMs <= 0 ? 0 : Math.round(s.positionMs * 1000f / s.durationMs);
            progressBar.setProgress(Math.max(0, Math.min(1000, p)));
        }
        elapsedView.setText(time(s.positionMs));
        durationView.setText(time(s.durationMs));
        attenuationDb = clamp(s.attenuationDb);
        if (volumeBar.getProgress() != attenuationDb - MIN_DB) {
            volumeBar.setProgress(attenuationDb - MIN_DB);
        }
        dbView.setText(formatDb(attenuationDb));
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

    private int clamp(int db) { return Math.max(MIN_DB, Math.min(MAX_DB, db)); }
    private String formatDb(int db) { return "−" + Math.abs(db) + " dB"; }

    private String time(int ms) {
        int total = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout card() {
        LinearLayout v = column();
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        v.setBackground(box(PANEL, Color.rgb(35, 72, 90)));
        return v;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String value) {
        Button v = new Button(this);
        v.setText(value);
        v.setAllCaps(false);
        v.setTextSize(12);
        v.setTextColor(TEXT);
        v.setBackground(box(PANEL2, Color.rgb(45, 121, 203)));
        return v;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private void addWeighted(LinearLayout parent, View child) {
        parent.addView(child, new LinearLayout.LayoutParams(0, dp(50), 1f));
    }

    private View space(int widthDp) {
        View v = new View(this);
        v.setMinimumWidth(dp(widthDp));
        return v;
    }

    private LinearLayout.LayoutParams mw() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams mh(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
