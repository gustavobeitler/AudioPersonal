package com.gustavo.reproductorsueno;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 1001;
    private static final int PICK_PLAYLIST = 1002;
    private static final int CREATE_PLAYLIST = 1003;
    private static final int REQUEST_MEDIA_PERMISSION = 77;
    private static final int REQUEST_NOTIFICATION_PERMISSION = 78;
    private static final int DEFAULT_NIGHT_DB = -20;
    private static final int DEFAULT_NORMAL_DB = -6;

    private static final int C_BG = Color.rgb(5, 13, 21);
    private static final int C_PANEL = Color.rgb(15, 34, 48);
    private static final int C_PANEL_2 = Color.rgb(20, 44, 59);
    private static final int C_CYAN = Color.rgb(39, 203, 216);
    private static final int C_GREEN = Color.rgb(58, 210, 142);
    private static final int C_BLUE = Color.rgb(45, 121, 203);
    private static final int C_TEXT = Color.rgb(238, 245, 248);
    private static final int C_MUTED = Color.rgb(156, 178, 192);
    private static final int C_WARNING = Color.rgb(226, 179, 111);

    private final LinkedHashMap<String, String> playlists = new LinkedHashMap<>();
    private final ArrayList<String> trackUris = new ArrayList<>();
    private final ArrayList<String> trackNames = new ArrayList<>();
    private final ArrayList<LibraryTrack> library = new ArrayList<>();
    private final ArrayList<LibraryTrack> visibleLibrary = new ArrayList<>();
    private final ArrayList<SoundProfile> profiles = new ArrayList<>();
    private final ArrayList<View> pages = new ArrayList<>();

    private SharedPreferences prefs;
    private PlaybackService playbackService;
    private boolean serviceBound;
    private long lastHardwareVolumeEventMs;
    private ViewPager2 pager;
    private Button[] navButtons;
    private Button normalModeButton;
    private Button nightModeButton;
    private Button fmButton;
    private Button mainPlayButton;
    private Button miniPlayButton;
    private Button saveNightLimitButton;
    private TextView nowTitle;
    private TextView nowStatus;
    private TextView outputSummary;
    private TextView profileInfo;
    private TextView volumeLabel;
    private TextView trackCount;
    private TextView libraryCount;
    private TextView miniTitle;
    private TextView miniStatus;
    private TextView elapsedLabel;
    private TextView durationLabel;
    private LinearLayout miniPlayer;
    private SeekBar volumeBar;
    private SeekBar progressBar;
    private CheckBox lockNightMaximum;
    private CheckBox shuffle;
    private Spinner playlistSpinner;
    private Spinner profileSpinner;
    private ArrayAdapter<String> playlistAdapter;
    private ArrayAdapter<String> playlistTrackAdapter;
    private ArrayAdapter<String> libraryAdapter;
    private ArrayAdapter<String> profileAdapter;

    private boolean nightMode;
    private boolean fmEnabled;
    private boolean servicePlaying;
    private boolean serviceLoading;
    private boolean stateKnown;
    private boolean userSeeking;
    private boolean userChangingVolume;
    private boolean pendingPlayAfterPermission;
    private int currentServiceIndex;
    private int currentServicePosition;
    private String currentPlaylistId;
    private String activeProfileId;

    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            PlaybackService.LocalBinder binder = (PlaybackService.LocalBinder) service;
            playbackService = binder.getService();
            serviceBound = true;
            syncBoundService();
            playbackService.requestStateFromClient();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            playbackService = null;
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!PlaybackService.BROADCAST_STATE.equals(intent.getAction())) return;
            String title = intent.getStringExtra(PlaybackService.EXTRA_TITLE);
            String message = intent.getStringExtra(PlaybackService.EXTRA_MESSAGE);
            String state = intent.getStringExtra(PlaybackService.EXTRA_STATE);
            int position = intent.getIntExtra(PlaybackService.EXTRA_POSITION, 0);
            currentServicePosition = position;
            int duration = intent.getIntExtra(PlaybackService.EXTRA_DURATION, 0);
            int attenuation = intent.getIntExtra(PlaybackService.EXTRA_ATTENUATION_DB,
                    prefs.getInt(dbKey(), nightMode ? DEFAULT_NIGHT_DB : DEFAULT_NORMAL_DB));
            currentServiceIndex = intent.getIntExtra(PlaybackService.EXTRA_CURRENT_INDEX, currentServiceIndex);
            servicePlaying = PlaybackService.STATE_PLAYING.equals(state);
            serviceLoading = PlaybackService.STATE_LOADING.equals(state);
            stateKnown = true;

            String clean = title == null || title.trim().isEmpty()
                    ? prefs.getString("last_title", "Sin selección") : cleanTitle(title);
            if (nowTitle != null) nowTitle.setText(clean);
            if (miniTitle != null) miniTitle.setText(clean);

            String status;
            int statusColor;
            if (message != null && !message.isEmpty()) {
                status = message;
                statusColor = C_WARNING;
            } else if (serviceLoading) {
                status = "Cargando…";
                statusColor = C_CYAN;
            } else if (servicePlaying) {
                status = "Reproduciendo";
                statusColor = C_GREEN;
            } else {
                status = "En pausa";
                statusColor = C_MUTED;
            }
            if (nowStatus != null) {
                nowStatus.setText(status.toUpperCase(Locale.ROOT));
                nowStatus.setTextColor(statusColor);
            }
            if (miniStatus != null) {
                miniStatus.setText(status);
                miniStatus.setTextColor(statusColor);
            }
            updatePlayButtons();
            updateProgress(position, duration);
            if (!userChangingVolume && volumeBar != null) {
                int clamped = Math.max(-60, Math.min(0, attenuation));
                if (volumeBar.getProgress() != clamped + 60) volumeBar.setProgress(clamped + 60);
                volumeLabel.setText(formatDb(clamped));
            }
            prefs.edit()
                    .putString("last_title", clean)
                    .putBoolean("last_playing", servicePlaying)
                    .putInt("last_service_index", currentServiceIndex)
                    .putInt("last_position", currentServicePosition)
                    .apply();

            String missing = intent.getStringExtra(PlaybackService.EXTRA_MISSING_URI);
            if (missing != null && !missing.isEmpty()) markMissingTrack(missing);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        prefs = getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        migrateSettings();
        nightMode = prefs.getBoolean("night_mode", false);
        fmEnabled = prefs.getBoolean(fmKey(), false);
        currentServiceIndex = prefs.getInt("last_service_index", 0);
        currentServicePosition = prefs.getInt("last_position", 0);
        loadPlaylists();
        loadProfiles();
        activeProfileId = prefs.getString("active_profile", "auto");
        buildInterface();
        registerStateReceiver();
        consumePendingProfileResult();
        requestNotificationPermission();
        if (hasAudioPermission()) scanLibrary();
        else requestAudioPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        consumePendingProfileResult();
        refreshProfileSummary();
        queryPlaybackState();
    }

    @Override protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        startPlaybackService(serviceIntent);
        try { bindService(serviceIntent, playbackConnection, Context.BIND_AUTO_CREATE); }
        catch (Exception ignored) { }
    }

    @Override protected void onStop() {
        if (serviceBound) {
            try { unbindService(playbackConnection); } catch (Exception ignored) { }
            serviceBound = false;
            playbackService = null;
        }
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && volumeBar != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                long now = event.getEventTime();
                if (event.getRepeatCount() == 0 || now - lastHardwareVolumeEventMs >= 140L) {
                    lastHardwareVolumeEventMs = now;
                    changeAppVolumeBy(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void migrateSettings() {
        if (prefs.getBoolean("beta05_migrated", false)) return;
        SharedPreferences.Editor editor = prefs.edit().putBoolean("beta05_migrated", true);
        if (!prefs.contains("night_mode")) editor.putBoolean("night_mode", false);
        if (!prefs.contains("night_db")) editor.putInt("night_db", DEFAULT_NIGHT_DB);
        if (!prefs.contains("normal_db")) editor.putInt("normal_db", DEFAULT_NORMAL_DB);
        if (!prefs.contains("lock_night_maximum")) editor.putBoolean("lock_night_maximum", true);
        editor.apply();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(12), dp(10) + top, dp(12), dp(8) + bottom);
            return insets;
        });

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(text("REPRODUCTOR DE MÚSICA", 22, C_TEXT, true), matchWrap());
        heading.addView(text("RADIOENLACE AUDIO  ·  BETA " + "0.9", 11, C_CYAN, true), matchWrap());
        root.addView(heading, matchWrap());

        outputSummary = text("SALIDA AUTOMÁTICA · PERFIL ESTÁNDAR", 12, C_GREEN, true);
        outputSummary.setGravity(Gravity.CENTER_VERTICAL);
        outputSummary.setPadding(dp(12), dp(8), dp(12), dp(8));
        outputSummary.setMaxLines(2);
        outputSummary.setBackground(roundRect(Color.rgb(17, 55, 54), dp(14), C_GREEN, 1));
        LinearLayout.LayoutParams outputParams = matchWrap();
        outputParams.setMargins(0, dp(8), 0, 0);
        root.addView(outputSummary, outputParams);

        pages.clear();
        pages.add(buildNowPlayingPage());
        pages.add(buildLibraryPage());
        pages.add(buildPlaylistsPage());
        pages.add(buildSoundPage());

        pager = new ViewPager2(this);
        pager.setAdapter(new FixedPageAdapter());
        pager.setOffscreenPageLimit(4);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                updateNavigation(position);
                if (miniPlayer != null) miniPlayer.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
            }
        });
        outputSummary.setOnClickListener(v -> pager.setCurrentItem(3, true));
        root.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        miniPlayer = buildMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, matchHeight(dp(66)));

        String[] tabs = {"REPRODUCIENDO", "BIBLIOTECA", "LISTAS", "SONIDO"};
        LinearLayout navigation = horizontal();
        navigation.setPadding(0, dp(5), 0, dp(2));
        navButtons = new Button[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            final int page = i;
            Button button = button(tabs[i]);
            button.setTextSize(9);
            button.setSingleLine(true);
            button.setOnClickListener(v -> pager.setCurrentItem(page, true));
            navButtons[i] = button;
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        root.addView(navigation, matchWrap());

        setContentView(root);
        selectSavedPlaylist();
        refreshProfiles();
        applyModeVisuals();
        updateNavigation(0);
        updatePlayButtons();
    }

    private LinearLayout buildMiniPlayer() {
        LinearLayout mini = horizontal();
        mini.setPadding(dp(10), dp(7), dp(8), dp(7));
        mini.setBackground(roundRect(C_PANEL, dp(15), Color.rgb(35, 72, 90), 1));
        mini.setOnClickListener(v -> pager.setCurrentItem(0, true));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        miniTitle = text(prefs.getString("last_title", "Toca una canción para comenzar"), 14, C_TEXT, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniStatus = text("Listo para reproducir", 11, C_MUTED, false);
        textBlock.addView(miniTitle, matchWrap());
        textBlock.addView(miniStatus, matchWrap());
        mini.addView(textBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button previous = compactTransport("◀");
        miniPlayButton = compactTransport("▶");
        Button next = compactTransport("▶");
        previous.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_PREVIOUS, -1));
        miniPlayButton.setOnClickListener(v -> playOrPause());
        next.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_NEXT, -1));
        mini.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(46)));
        mini.addView(miniPlayButton, new LinearLayout.LayoutParams(dp(46), dp(46)));
        mini.addView(next, new LinearLayout.LayoutParams(dp(42), dp(46)));
        return mini;
    }

    private View buildNowPlayingPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn();
        scroll.addView(root);

        LinearLayout modeCard = card();
        modeCard.addView(sectionTitle("MODO DE ESCUCHA"), matchWrap());
        LinearLayout modes = horizontal();
        normalModeButton = button("NORMAL");
        nightModeButton = button("NOCTURNO");
        normalModeButton.setOnClickListener(v -> setMode(false));
        nightModeButton.setOnClickListener(v -> setMode(true));
        modes.addView(normalModeButton, weighted(48));
        modes.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        modes.addView(nightModeButton, weighted(48));
        modeCard.addView(modes, matchWrap());
        root.addView(modeCard, cardParams());

        LinearLayout playerCard = card();
        playerCard.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView art = text("♫", 72, C_CYAN, false);
        art.setGravity(Gravity.CENTER);
        art.setBackground(roundRect(Color.rgb(14, 52, 72), dp(22), C_BLUE, 1));
        playerCard.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(148)));

        nowStatus = text("EN PAUSA", 11, C_MUTED, true);
        nowStatus.setGravity(Gravity.CENTER);
        nowStatus.setPadding(0, dp(12), 0, dp(3));
        playerCard.addView(nowStatus, matchWrap());

        nowTitle = text(prefs.getString("last_title", "Sin selección"), 20, C_TEXT, true);
        nowTitle.setGravity(Gravity.CENTER);
        nowTitle.setMaxLines(2);
        playerCard.addView(nowTitle, matchWrap());

        progressBar = new SeekBar(this);
        progressBar.setMax(1000);
        progressBar.setPadding(0, dp(8), 0, 0);
        playerCard.addView(progressBar, matchWrap());
        LinearLayout times = horizontal();
        elapsedLabel = text("0:00", 11, C_MUTED, false);
        durationLabel = text("0:00", 11, C_MUTED, false);
        times.addView(elapsedLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        durationLabel.setGravity(Gravity.END);
        times.addView(durationLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        playerCard.addView(times, matchWrap());
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                int duration = prefs.getInt("last_duration", 0);
                if (duration > 0) sendSeek(Math.round(duration * seekBar.getProgress() / 1000f));
            }
        });

        LinearLayout controls = horizontal();
        controls.setPadding(0, dp(12), 0, 0);
        Button previous = transportButton("◀");
        mainPlayButton = transportButton("▶");
        Button next = transportButton("▶");
        previous.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_PREVIOUS, -1));
        mainPlayButton.setOnClickListener(v -> playOrPause());
        next.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_NEXT, -1));
        mainPlayButton.setBackground(roundRect(C_GREEN, dp(28), 0, 0));
        mainPlayButton.setTextColor(Color.rgb(3, 22, 18));
        controls.addView(previous, weighted(54));
        controls.addView(space(dp(10)), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(mainPlayButton, weighted(60));
        controls.addView(space(dp(10)), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(next, weighted(54));
        playerCard.addView(controls, matchWrap());
        root.addView(playerCard, cardParams());

        LinearLayout quickSound = card();
        quickSound.addView(sectionTitle("VOLUMEN Y PROCESAMIENTO"), matchWrap());
        fmButton = button("SONIDO FM");
        fmButton.setOnClickListener(v -> {
            fmEnabled = !fmEnabled;
            prefs.edit().putBoolean(fmKey(), fmEnabled).apply();
            updateFmButton();
            sendSettings();
        });
        quickSound.addView(fmButton, matchHeight(dp(48)));
        TextView label = text("Atenuación de la aplicación", 12, C_MUTED, false);
        label.setPadding(0, dp(8), 0, 0);
        quickSound.addView(label, matchWrap());
        volumeLabel = text("", 27, C_CYAN, true);
        volumeLabel.setGravity(Gravity.CENTER);
        quickSound.addView(volumeLabel, matchWrap());
        volumeBar = new SeekBar(this);
        volumeBar.setMax(60);
        quickSound.addView(volumeBar, matchWrap());
        quickSound.addView(text(
                "También puedes usar los botones físicos del teléfono. El número es una reducción digital, no dB(A) reales.",
                11, C_MUTED, false), matchWrap());
        root.addView(quickSound, cardParams());

        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int db = constrainedDb(progress - 60);
                if (seekBar.getProgress() != db + 60) {
                    seekBar.setProgress(db + 60);
                    return;
                }
                if (volumeLabel != null) volumeLabel.setText(formatDb(db));
                if (fromUser) {
                    userChangingVolume = true;
                    prefs.edit().putInt(dbKey(), db).apply();
                    sendVolumeOnly(db);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userChangingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userChangingVolume = false;
                int db = constrainedDb(seekBar.getProgress() - 60);
                prefs.edit().putInt(dbKey(), db).apply();
                sendVolumeOnly(db);
            }
        });
        return scroll;
    }

    private View buildLibraryPage() {
        LinearLayout root = pageColumn();
        LinearLayout header = card();
        LinearLayout titleRow = horizontal();
        titleRow.addView(sectionTitle("BIBLIOTECA DEL TELÉFONO"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        libraryCount = text("Sin escanear", 11, C_CYAN, true);
        titleRow.addView(libraryCount, wrapWrap());
        header.addView(titleRow, matchWrap());

        EditText search = new EditText(this);
        search.setHint("Buscar canción, intérprete o álbum");
        search.setTextColor(C_TEXT);
        search.setHintTextColor(C_MUTED);
        search.setSingleLine(true);
        search.setBackground(roundRect(C_PANEL_2, dp(12), Color.rgb(35, 72, 90), 1));
        search.setPadding(dp(12), 0, dp(12), 0);
        header.addView(search, matchHeight(dp(48)));

        Button scan = button("ACTUALIZAR BIBLIOTECA");
        scan.setOnClickListener(v -> {
            if (hasAudioPermission()) scanLibrary();
            else requestAudioPermission();
        });
        LinearLayout.LayoutParams scanParams = matchHeight(dp(45));
        scanParams.setMargins(0, dp(8), 0, 0);
        header.addView(scan, scanParams);
        root.addView(header, cardParams());

        ListView list = new ListView(this);
        libraryAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, new ArrayList<>()) {
            @NonNull @Override public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView title = view.findViewById(android.R.id.text1);
                TextView detail = view.findViewById(android.R.id.text2);
                LibraryTrack track = visibleLibrary.get(position);
                title.setText(track.title);
                title.setTextColor(C_TEXT);
                title.setTextSize(15);
                detail.setText(track.artist + (track.album.isEmpty() ? "" : "  ·  " + track.album));
                detail.setTextColor(C_MUTED);
                detail.setTextSize(12);
                view.setPadding(dp(5), dp(7), dp(5), dp(7));
                view.setBackgroundColor(C_BG);
                return view;
            }
        };
        list.setAdapter(libraryAdapter);
        list.setDividerHeight(1);
        list.setOnItemClickListener((parent, view, position, id) ->
                playLibraryTrack(visibleLibrary.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showLibraryTrackActions(visibleLibrary.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) { filterLibrary(editable.toString()); }
        });
        return root;
    }

    private View buildPlaylistsPage() {
        LinearLayout root = pageColumn();
        LinearLayout top = card();
        LinearLayout titleRow = horizontal();
        titleRow.addView(sectionTitle("LISTAS DE REPRODUCCIÓN"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        trackCount = text("0 pistas", 11, C_CYAN, true);
        titleRow.addView(trackCount, wrapWrap());
        top.addView(titleRow, matchWrap());

        playlistSpinner = new Spinner(this);
        top.addView(playlistSpinner, matchHeight(dp(50)));
        playlistSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= playlists.size()) return;
                currentPlaylistId = new ArrayList<>(playlists.keySet()).get(position);
                prefs.edit().putString("current_playlist", currentPlaylistId).apply();
                loadTracks(currentPlaylistId);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout row1 = horizontal();
        Button newList = smallButton("NUEVA");
        Button deleteList = smallButton("ELIMINAR");
        newList.setOnClickListener(v -> createPlaylistDialog());
        deleteList.setOnClickListener(v -> deleteCurrentPlaylist());
        row1.addView(newList, weighted(44));
        row1.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        row1.addView(deleteList, weighted(44));
        top.addView(row1, matchWrap());

        LinearLayout row2 = horizontal();
        Button addMusic = smallButton("AGREGAR");
        Button importList = smallButton("IMPORTAR");
        Button exportList = smallButton("EXPORTAR");
        addMusic.setOnClickListener(v -> openAudioPicker());
        importList.setOnClickListener(v -> openPlaylistPicker());
        exportList.setOnClickListener(v -> exportCurrentPlaylist());
        row2.addView(addMusic, weighted(42));
        row2.addView(space(dp(6)), new LinearLayout.LayoutParams(dp(6), 1));
        row2.addView(importList, weighted(42));
        row2.addView(space(dp(6)), new LinearLayout.LayoutParams(dp(6), 1));
        row2.addView(exportList, weighted(42));
        top.addView(row2, matchWrap());
        root.addView(top, cardParams());

        ListView list = new ListView(this);
        playlistTrackAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, trackNames) {
            @NonNull @Override public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(trackNames.get(position).startsWith("⚠") ? C_WARNING : C_TEXT);
                view.setTextSize(15);
                view.setPadding(dp(12), dp(13), dp(12), dp(13));
                view.setBackgroundColor(position == currentServiceIndex ? C_PANEL_2 : C_BG);
                return view;
            }
        };
        list.setAdapter(playlistTrackAdapter);
        list.setDividerHeight(1);
        list.setOnItemClickListener((parent, view, position, id) -> {
            currentServiceIndex = position;
            currentServicePosition = 0;
            sendPlayerCommand(PlaybackService.ACTION_PLAY_INDEX, position);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showPlaylistTrackActions(position);
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildSoundPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn();
        scroll.addView(root);

        LinearLayout selected = card();
        selected.addView(sectionTitle("DISPOSITIVO DE SONIDO"), matchWrap());
        profileSpinner = new Spinner(this);
        selected.addView(profileSpinner, matchHeight(dp(52)));
        profileInfo = text("", 13, C_MUTED, false);
        profileInfo.setPadding(0, dp(8), 0, dp(10));
        selected.addView(profileInfo, matchWrap());
        LinearLayout profileButtons = horizontal();
        Button add = smallButton("AGREGAR AURICULARES");
        Button remove = smallButton("ELIMINAR PERFIL");
        add.setOnClickListener(v -> showAddHeadphonesDialog());
        remove.setOnClickListener(v -> deleteSelectedProfile());
        profileButtons.addView(add, weighted(52));
        profileButtons.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        profileButtons.addView(remove, weighted(44));
        selected.addView(profileButtons, matchWrap());
        root.addView(selected, cardParams());

        LinearLayout safety = card();
        safety.addView(sectionTitle("SEGURIDAD Y REPRODUCCIÓN"), matchWrap());
        lockNightMaximum = new CheckBox(this);
        styleCheckBox(lockNightMaximum, "Usar mi máximo nocturno: " + formatDb(getNightLimit()));
        lockNightMaximum.setChecked(prefs.getBoolean("lock_night_maximum", true));
        lockNightMaximum.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("lock_night_maximum", checked).apply();
            enforceNightLimit();
        });
        safety.addView(lockNightMaximum, matchWrap());

        saveNightLimitButton = button("GUARDAR NIVEL ACTUAL COMO MÁXIMO NOCTURNO");
        saveNightLimitButton.setTextSize(11);
        saveNightLimitButton.setOnClickListener(v -> saveCurrentNightLimit());
        safety.addView(saveNightLimitButton, matchHeight(dp(48)));

        shuffle = new CheckBox(this);
        styleCheckBox(shuffle, "Orden aleatorio");
        shuffle.setChecked(prefs.getBoolean("shuffle", false));
        shuffle.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("shuffle", checked).apply();
            sendSettings();
        });
        safety.addView(shuffle, matchWrap());
        safety.addView(text(
                "Si se desconectan auriculares Bluetooth, cableados o USB, la reproducción se pausa y no pasa al parlante.",
                13, C_WARNING, false), matchWrap());
        root.addView(safety, cardParams());

        LinearLayout standards = card();
        standards.addView(sectionTitle("FUNCIONAMIENTO INMEDIATO"), matchWrap());
        standards.addView(text(
                "No necesitas configurar nada para escuchar. En Automático se utiliza un perfil estándar adecuado para auriculares o para los parlantes del teléfono.",
                13, C_MUTED, false), matchWrap());
        TextView phone = text("Teléfono detectado: " + Build.MANUFACTURER + " " + Build.MODEL,
                13, C_CYAN, true);
        phone.setPadding(0, dp(10), 0, 0);
        standards.addView(phone, matchWrap());
        root.addView(standards, cardParams());
        return scroll;
    }

    private void playOrPause() {
        boolean actuallyLoading = serviceBound && playbackService != null
                ? playbackService.isLoadingForClient() : serviceLoading;
        if (actuallyLoading) return;

        boolean actuallyPlaying = serviceBound && playbackService != null
                ? playbackService.isPlayingForClient() : servicePlaying;
        if (actuallyPlaying) {
            if (serviceBound && playbackService != null) playbackService.pauseFromClient();
            else startPlaybackService(new Intent(this, PlaybackService.class)
                    .setAction(PlaybackService.ACTION_PAUSE));
            return;
        }
        ensurePlayableListAndPlay();
    }

    private void ensurePlayableListAndPlay() {
        if (trackUris.isEmpty()) {
            if (!hasAudioPermission()) {
                pendingPlayAfterPermission = true;
                requestAudioPermission();
                return;
            }
            if (library.isEmpty()) scanLibrary();
            if (!library.isEmpty()) {
                LibraryTrack first = library.get(0);
                addTrack(first.uri, first.title);
                saveTracks();
                refreshTrackList();
                currentServiceIndex = 0;
            }
        }
        if (trackUris.isEmpty()) {
            Toast.makeText(this, "No se encontró música para reproducir", Toast.LENGTH_LONG).show();
            return;
        }
        Intent playIntent = basePlayerIntent(PlaybackService.ACTION_PLAY_INDEX);
        playIntent.putStringArrayListExtra(PlaybackService.EXTRA_URIS, new ArrayList<>(trackUris));
        playIntent.putStringArrayListExtra(PlaybackService.EXTRA_NAMES, new ArrayList<>(trackNames));
        playIntent.putExtra(PlaybackService.EXTRA_INDEX, currentServiceIndex);
        playIntent.putExtra(PlaybackService.EXTRA_RESUME_POSITION, currentServicePosition);
        startPlaybackService(playIntent);
    }

    private void queryPlaybackState() {
        if (serviceBound && playbackService != null) {
            playbackService.requestStateFromClient();
            return;
        }
        Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_QUERY_STATE);
        startPlaybackService(intent);
    }

    private void changeAppVolumeBy(int delta) {
        int db = constrainedDb(volumeBar.getProgress() - 60 + delta);
        volumeBar.setProgress(db + 60);
        volumeLabel.setText(formatDb(db));
        prefs.edit().putInt(dbKey(), db).apply();
        sendVolumeOnly(db);
    }

    private int constrainedDb(int requested) {
        int db = Math.max(-60, Math.min(0, requested));
        if (nightMode && lockNightMaximum != null && lockNightMaximum.isChecked()) {
            db = Math.min(db, getNightLimit());
        }
        return db;
    }

    private void sendVolumeOnly(int db) {
        if (serviceBound && playbackService != null) {
            playbackService.setVolumeFromClient(db);
            return;
        }
        Intent intent = new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.ACTION_SET_VOLUME)
                .putExtra(PlaybackService.EXTRA_ATTENUATION_DB, db);
        startPlaybackService(intent);
    }

    private void setMode(boolean night) {
        if (nightMode == night) return;
        prefs.edit().putInt(dbKey(), volumeBar.getProgress() - 60).apply();
        nightMode = night;
        prefs.edit().putBoolean("night_mode", nightMode).apply();
        fmEnabled = prefs.getBoolean(fmKey(), false);
        int db = prefs.getInt(dbKey(), nightMode ? DEFAULT_NIGHT_DB : DEFAULT_NORMAL_DB);
        volumeBar.setProgress(constrainedDb(db) + 60);
        applyModeVisuals();
        sendSettings();
    }

    private void applyModeVisuals() {
        styleModeButton(normalModeButton, !nightMode);
        styleModeButton(nightModeButton, nightMode);
        if (lockNightMaximum != null) lockNightMaximum.setVisibility(nightMode ? View.VISIBLE : View.GONE);
        if (saveNightLimitButton != null) saveNightLimitButton.setVisibility(nightMode ? View.VISIBLE : View.GONE);
        int db = constrainedDb(prefs.getInt(dbKey(), nightMode ? DEFAULT_NIGHT_DB : DEFAULT_NORMAL_DB));
        if (volumeBar != null) volumeBar.setProgress(db + 60);
        if (volumeLabel != null) volumeLabel.setText(formatDb(db));
        updateFmButton();
        refreshProfileSummary();
    }

    private void updateFmButton() {
        if (fmButton == null) return;
        fmButton.setText(fmEnabled ? "SONIDO FM  ·  ACTIVO" : "SONIDO FM  ·  APAGADO");
        fmButton.setTextColor(fmEnabled ? Color.rgb(3, 22, 18) : C_TEXT);
        fmButton.setBackground(roundRect(fmEnabled ? C_GREEN : C_PANEL_2,
                dp(14), fmEnabled ? 0 : C_BLUE, fmEnabled ? 0 : 1));
    }

    private void updatePlayButtons() {
        String symbol = serviceLoading ? "…" : (servicePlaying ? "❚❚" : "▶");
        if (mainPlayButton != null) {
            mainPlayButton.setText(symbol);
            mainPlayButton.setEnabled(!serviceLoading);
        }
        if (miniPlayButton != null) {
            miniPlayButton.setText(symbol);
            miniPlayButton.setEnabled(!serviceLoading);
        }
    }

    private void updateProgress(int position, int duration) {
        prefs.edit().putInt("last_duration", duration).apply();
        if (!userSeeking && progressBar != null) {
            progressBar.setProgress(duration <= 0 ? 0 : Math.round(position * 1000f / duration));
        }
        if (elapsedLabel != null) elapsedLabel.setText(formatTime(position));
        if (durationLabel != null) durationLabel.setText(formatTime(duration));
        if (playlistTrackAdapter != null) playlistTrackAdapter.notifyDataSetChanged();
    }

    private void sendSeek(int position) {
        if (serviceBound && playbackService != null) {
            playbackService.seekFromClient(position);
            return;
        }
        Intent intent = new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.ACTION_SEEK)
                .putExtra(PlaybackService.EXTRA_SEEK_POSITION, position);
        startPlaybackService(intent);
    }

    private void sendPlayerCommand(String action, int index) {
        if (trackUris.isEmpty() && !PlaybackService.ACTION_QUERY_STATE.equals(action)) {
            ensurePlayableListAndPlay();
            return;
        }

        int targetIndex = index >= 0 ? index : currentServiceIndex;
        if (serviceBound && playbackService != null) {
            syncBoundService();
            if (PlaybackService.ACTION_PLAY.equals(action)
                    || PlaybackService.ACTION_PLAY_INDEX.equals(action)) {
                int resume = PlaybackService.ACTION_PLAY_INDEX.equals(action)
                        ? 0 : currentServicePosition;
                playbackService.playFromClient(targetIndex, resume);
            } else if (PlaybackService.ACTION_PAUSE.equals(action)) {
                playbackService.pauseFromClient();
            } else if (PlaybackService.ACTION_NEXT.equals(action)) {
                playbackService.nextFromClient();
            } else if (PlaybackService.ACTION_PREVIOUS.equals(action)) {
                playbackService.previousFromClient();
            } else if (PlaybackService.ACTION_SETTINGS.equals(action)) {
                playbackService.applySettingsFromClient();
            } else if (PlaybackService.ACTION_QUERY_STATE.equals(action)) {
                playbackService.requestStateFromClient();
            }
            return;
        }

        Intent intent = basePlayerIntent(action);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_URIS, new ArrayList<>(trackUris));
        intent.putStringArrayListExtra(PlaybackService.EXTRA_NAMES, new ArrayList<>(trackNames));
        intent.putExtra(PlaybackService.EXTRA_INDEX, targetIndex);
        intent.putExtra(PlaybackService.EXTRA_RESUME_POSITION,
                PlaybackService.ACTION_PLAY_INDEX.equals(action) ? 0 : currentServicePosition);
        startPlaybackService(intent);
    }

    private void syncBoundService() {
        if (!serviceBound || playbackService == null || profiles.isEmpty()) return;
        SoundProfile profile = resolvedProfile();
        playbackService.syncQueueFromClient(new ArrayList<>(trackUris),
                new ArrayList<>(trackNames), currentServiceIndex);
        playbackService.configureFromClient(volumeBar == null
                        ? prefs.getInt(dbKey(), nightMode ? DEFAULT_NIGHT_DB : DEFAULT_NORMAL_DB)
                        : volumeBar.getProgress() - 60,
                shuffle != null && shuffle.isChecked(), nightMode, fmEnabled,
                profile.gains, profile.preampDb, profile.name,
                "headphones".equals(profile.type));
    }

    private Intent basePlayerIntent(String action) {
        SoundProfile profile = resolvedProfile();
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        intent.putExtra(PlaybackService.EXTRA_ATTENUATION_DB, volumeBar == null
                ? prefs.getInt(dbKey(), nightMode ? DEFAULT_NIGHT_DB : DEFAULT_NORMAL_DB)
                : volumeBar.getProgress() - 60);
        intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle != null && shuffle.isChecked());
        intent.putExtra(PlaybackService.EXTRA_NIGHT_MODE, nightMode);
        intent.putExtra(PlaybackService.EXTRA_FM_PROCESSOR, fmEnabled);
        intent.putExtra(PlaybackService.EXTRA_PROFILE_GAINS, profile.gains);
        intent.putExtra(PlaybackService.EXTRA_PROFILE_PREAMP_DB, profile.preampDb);
        intent.putExtra(PlaybackService.EXTRA_PROFILE_NAME, profile.name);
        intent.putExtra(PlaybackService.EXTRA_EXPECT_HEADPHONES, "headphones".equals(profile.type));
        return intent;
    }

    private void sendSettings() {
        if (profiles.isEmpty()) return;
        if (serviceBound && playbackService != null) {
            syncBoundService();
            playbackService.applySettingsFromClient();
            return;
        }
        startPlaybackService(basePlayerIntent(PlaybackService.ACTION_SETTINGS));
    }

    private void startPlaybackService(Intent intent) {
        try { startService(intent); }
        catch (Exception error) {
            Toast.makeText(this, "No se pudo iniciar la reproducción", Toast.LENGTH_SHORT).show();
        }
    }

    private void playLibraryTrack(LibraryTrack track) {
        int index = addTrack(track.uri, track.title);
        currentServiceIndex = index;
        currentServicePosition = 0;
        saveTracks();
        refreshTrackList();
        sendPlayerCommand(PlaybackService.ACTION_PLAY_INDEX, index);
    }

    private void showLibraryTrackActions(LibraryTrack track) {
        new AlertDialog.Builder(this)
                .setTitle(track.title)
                .setItems(new String[]{"Reproducir ahora", "Agregar a la lista", "Reproducir siguiente"},
                        (dialog, which) -> {
                            int index = addTrack(track.uri, track.title);
                            saveTracks();
                            refreshTrackList();
                            if (which == 0) {
                                currentServiceIndex = index;
                                currentServicePosition = 0;
                                sendPlayerCommand(PlaybackService.ACTION_PLAY_INDEX, index);
                            }
                            else if (which == 2) sendPlayNext(index);
                        })
                .show();
    }

    private void showPlaylistTrackActions(int position) {
        if (position < 0 || position >= trackUris.size()) return;
        new AlertDialog.Builder(this)
                .setTitle(trackNames.get(position))
                .setItems(new String[]{"Reproducir siguiente", "Quitar de la lista"}, (dialog, which) -> {
                    if (which == 0) sendPlayNext(position);
                    else removeTrack(position);
                })
                .show();
    }

    private void sendPlayNext(int position) {
        Intent intent = basePlayerIntent(PlaybackService.ACTION_PLAY_NEXT);
        intent.putExtra(PlaybackService.EXTRA_NEXT_URI, trackUris.get(position));
        intent.putExtra(PlaybackService.EXTRA_NEXT_NAME, trackNames.get(position));
        startPlaybackService(intent);
        Toast.makeText(this, "Se reproducirá a continuación", Toast.LENGTH_SHORT).show();
    }

    private void showAddHeadphonesDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), 0);
        form.addView(text("Marca", 13, C_TEXT, true), matchWrap());
        EditText brand = new EditText(this);
        brand.setHint("Por ejemplo: Edifier");
        form.addView(brand, matchHeight(dp(52)));
        TextView modelLabel = text("Modelo exacto", 13, C_TEXT, true);
        modelLabel.setPadding(0, dp(8), 0, 0);
        form.addView(modelLabel, matchWrap());
        EditText model = new EditText(this);
        model.setHint("Por ejemplo: W600BT");
        form.addView(model, matchHeight(dp(52)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Agregar auriculares")
                .setMessage("Escribe la marca y el modelo. El nombre Bluetooth no se utilizará.")
                .setView(form)
                .setPositiveButton("Buscar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String b = brand.getText().toString().trim();
            String m = model.getText().toString().trim();
            if (b.isEmpty() || m.isEmpty()) {
                Toast.makeText(this, "Completa marca y modelo", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            beginProfileSearch(b, m);
        }));
        dialog.show();
    }

    private void beginProfileSearch(String brand, String model) {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager == null || network == null
                ? null : manager.getNetworkCapabilities(network);
        if (capabilities == null) {
            PendingProfileJobService.schedule(this, brand, model);
            Toast.makeText(this, "Sin conexión. La búsqueda se hará automáticamente con Wi-Fi.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        if (unmetered) {
            searchHeadphoneProfile(brand, model);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Usar datos móviles")
                .setMessage("Necesito utilizar tus datos para obtener información del modelo "
                        + brand + " " + model + ". ¿Deseas buscar ahora o esperar una conexión Wi-Fi?")
                .setPositiveButton("Usar datos ahora", (dialog, which) -> searchHeadphoneProfile(brand, model))
                .setNegativeButton("Esperar Wi-Fi", (dialog, which) -> {
                    PendingProfileJobService.schedule(this, brand, model);
                    Toast.makeText(this, "La búsqueda queda pendiente hasta que haya Wi-Fi.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void searchHeadphoneProfile(String brand, String model) {
        ProgressDialog progress = ProgressDialog.show(this, "Buscando información",
                "Buscando datos para " + brand + " " + model + "…", true, false);
        HeadphoneProfileRepository.search(this, brand, model, new HeadphoneProfileRepository.Callback() {
            @Override public void onFound(HeadphoneProfileRepository.Result result) {
                progress.dismiss();
                saveFoundProfile(brand, model, result);
                Toast.makeText(MainActivity.this,
                        "Modelo encontrado. Perfil de sonido guardado.", Toast.LENGTH_LONG).show();
            }
            @Override public void onNotFound() {
                progress.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Modelo no encontrado")
                        .setMessage("Se utilizará el perfil estándar para auriculares.")
                        .setPositiveButton("Aceptar", null)
                        .show();
            }
            @Override public void onError(String message) {
                progress.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("No se pudo completar la búsqueda")
                        .setMessage(message + "\n\nSe mantendrá el perfil estándar.")
                        .setPositiveButton("Aceptar", null)
                        .show();
            }
        });
    }

    private void saveFoundProfile(String brand, String model, HeadphoneProfileRepository.Result result) {
        String requestedName = (brand + " " + model).trim();
        for (SoundProfile profile : profiles) {
            if (!profile.builtIn && profile.name.equalsIgnoreCase(requestedName)) {
                activeProfileId = profile.id;
                refreshProfiles();
                return;
            }
        }
        String id = "hp_" + System.currentTimeMillis();
        profiles.add(new SoundProfile(id, requestedName, "headphones",
                "Perfil medido: " + result.source, result.gainsDb, result.preampDb, false));
        activeProfileId = id;
        saveProfiles();
        refreshProfiles();
    }

    private void consumePendingProfileResult() {
        String result = prefs.getString("pending_profile_result", "");
        if (result.isEmpty() || "pending".equals(result)) return;
        String display = prefs.getString("pending_profile_display", "el modelo solicitado");
        prefs.edit().remove("pending_profile_result").remove("pending_profile_display").apply();
        loadProfiles();
        activeProfileId = prefs.getString("active_profile", "auto");
        refreshProfiles();
        if ("found".equals(result)) Toast.makeText(this,
                "Perfil encontrado y guardado: " + display, Toast.LENGTH_LONG).show();
        else Toast.makeText(this,
                "Modelo no encontrado: " + display + ". Se usará el perfil estándar.", Toast.LENGTH_LONG).show();
    }

    private void deleteSelectedProfile() {
        SoundProfile profile = selectedProfile();
        if (profile == null || profile.builtIn) {
            Toast.makeText(this, "Ese perfil forma parte de la aplicación", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Eliminar perfil")
                .setMessage("¿Eliminar “" + profile.name + "”?")
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    profiles.remove(profile);
                    activeProfileId = "auto";
                    saveProfiles();
                    refreshProfiles();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void refreshProfiles() {
        if (profileSpinner == null) return;
        ArrayList<String> names = new ArrayList<>();
        for (SoundProfile profile : profiles) names.add(profile.name);
        profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        int selected = 0;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id.equals(activeProfileId)) selected = i;
        profileSpinner.setSelection(selected);
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= profiles.size()) return;
                activeProfileId = profiles.get(position).id;
                prefs.edit().putString("active_profile", activeProfileId).apply();
                refreshProfileSummary();
                sendSettings();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        refreshProfileSummary();
    }

    private void refreshProfileSummary() {
        if (profiles.isEmpty()) return;
        SoundProfile profile = resolvedProfile();
        if (profileInfo != null) profileInfo.setText(profile.description + "\nModo "
                + (nightMode ? "Nocturno" : "Normal")
                + (fmEnabled ? "  ·  Sonido FM activo" : "  ·  Sonido FM apagado"));
        if (outputSummary != null) outputSummary.setText(profile.name.toUpperCase(Locale.ROOT));
    }

    private SoundProfile selectedProfile() {
        for (SoundProfile profile : profiles) if (profile.id.equals(activeProfileId)) return profile;
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private SoundProfile resolvedProfile() {
        SoundProfile selected = selectedProfile();
        if (selected != null && !"auto".equals(selected.id)) return selected;
        return hasHeadphoneOutput() ? findProfile("headphones_default") : findProfile("speaker_default");
    }

    private SoundProfile findProfile(String id) {
        for (SoundProfile profile : profiles) if (profile.id.equals(id)) return profile;
        return profiles.get(0);
    }

    private boolean hasHeadphoneOutput() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT < 23) return false;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET) return true;
        }
        return false;
    }

    private void scanLibrary() {
        if (!hasAudioPermission()) return;
        library.clear();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM};
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                while (cursor.moveToNext()) {
                    Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn));
                    library.add(new LibraryTrack(uri.toString(), value(cursor, titleColumn, "Pista"),
                            value(cursor, artistColumn, "Intérprete desconocido"), value(cursor, albumColumn, "")));
                }
            }
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo leer la biblioteca", Toast.LENGTH_LONG).show();
        }
        filterLibrary("");
        if (libraryCount != null) libraryCount.setText(library.size() + " canciones");
        if (pendingPlayAfterPermission) {
            pendingPlayAfterPermission = false;
            ensurePlayableListAndPlay();
        }
    }

    private String value(Cursor cursor, int column, String fallback) {
        String value = cursor.getString(column);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void filterLibrary(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        visibleLibrary.clear();
        for (LibraryTrack track : library) {
            String haystack = (track.title + " " + track.artist + " " + track.album).toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || haystack.contains(normalized)) visibleLibrary.add(track);
        }
        if (libraryAdapter != null) {
            libraryAdapter.clear();
            for (LibraryTrack track : visibleLibrary) libraryAdapter.add(track.title);
            libraryAdapter.notifyDataSetChanged();
        }
    }

    private int addTrack(String uri, String name) {
        int existing = trackUris.indexOf(uri);
        if (existing >= 0) return existing;
        trackUris.add(uri);
        trackNames.add(name == null || name.trim().isEmpty() ? "Pista de audio" : name.trim());
        return trackUris.size() - 1;
    }

    private void markMissingTrack(String uri) {
        int index = trackUris.indexOf(uri);
        if (index >= 0 && !trackNames.get(index).startsWith("⚠")) {
            trackNames.set(index, "⚠ Archivo no encontrado · " + trackNames.get(index));
            saveTracks();
            refreshTrackList();
        }
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_AUDIO);
    }

    private void openPlaylistPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/x-mpegurl", "audio/mpegurl",
                "application/vnd.apple.mpegurl", "audio/x-scpls", "text/plain"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_PLAYLIST);
    }

    private void exportCurrentPlaylist() {
        if (trackUris.isEmpty()) {
            Toast.makeText(this, "La lista está vacía", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("audio/x-mpegurl");
        intent.putExtra(Intent.EXTRA_TITLE, safeFileName(playlists.get(currentPlaylistId)) + ".m3u8");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, CREATE_PLAYLIST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_AUDIO) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) addUri(clipData.getItemAt(i).getUri(), null);
            } else if (data.getData() != null) addUri(data.getData(), null);
            saveTracks();
            refreshTrackList();
        } else if (requestCode == PICK_PLAYLIST && data.getData() != null) {
            importPlaylistFile(data.getData());
        } else if (requestCode == CREATE_PLAYLIST && data.getData() != null) {
            writePlaylist(data.getData());
        }
    }

    private void importPlaylistFile(Uri uri) {
        int before = trackUris.size();
        String pendingTitle = null;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        try (InputStream input = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF:")) {
                    int comma = line.indexOf(',');
                    if (comma >= 0) pendingTitle = line.substring(comma + 1).trim();
                    continue;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (line.startsWith("#") || line.startsWith("[playlist]") || lower.startsWith("numberofentries")) continue;
                if (lower.startsWith("file") && line.contains("=")) line = line.substring(line.indexOf('=') + 1).trim();
                else if (lower.startsWith("title") && line.contains("=")) {
                    pendingTitle = line.substring(line.indexOf('=') + 1).trim();
                    continue;
                } else if (lower.startsWith("length") && line.contains("=")) continue;
                Uri trackUri = parsePlaylistLocation(line);
                if (trackUri != null) addUri(trackUri, pendingTitle);
                pendingTitle = null;
            }
            saveTracks();
            refreshTrackList();
            Toast.makeText(this, (trackUris.size() - before) + " pistas importadas", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo leer esa lista", Toast.LENGTH_LONG).show();
        }
    }

    private void writePlaylist(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output))) {
            writer.write("#EXTM3U\n");
            for (int i = 0; i < trackUris.size(); i++) {
                writer.write("#EXTINF:-1," + trackNames.get(i) + "\n");
                writer.write(trackUris.get(i) + "\n");
            }
            Toast.makeText(this, "Lista exportada", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo exportar la lista", Toast.LENGTH_LONG).show();
        }
    }

    private Uri parsePlaylistLocation(String value) {
        try {
            if (value.startsWith("content://") || value.startsWith("file://")) return Uri.parse(value);
            if (value.startsWith("/")) return Uri.fromFile(new File(value));
            return Uri.parse(value);
        } catch (Exception ignored) { return null; }
    }

    private void addUri(Uri uri, String preferredName) {
        if (uri == null) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        addTrack(uri.toString(), preferredName == null || preferredName.trim().isEmpty()
                ? resolveName(uri) : preferredName.trim());
    }

    private String resolveName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "Pista de audio" : last;
    }

    private void createPlaylistDialog() {
        EditText input = new EditText(this);
        input.setHint("Nombre de la lista");
        new AlertDialog.Builder(this)
                .setTitle("Nueva lista")
                .setView(input)
                .setPositiveButton("Crear", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Nueva lista";
                    String id = "list_" + System.currentTimeMillis();
                    playlists.put(id, name);
                    currentPlaylistId = id;
                    savePlaylists();
                    refreshPlaylistAdapter();
                    playlistSpinner.setSelection(playlists.size() - 1);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void deleteCurrentPlaylist() {
        if (playlists.size() <= 1) {
            Toast.makeText(this, "Debe quedar al menos una lista", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Eliminar lista")
                .setMessage("¿Eliminar “" + playlists.get(currentPlaylistId) + "”?")
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    prefs.edit().remove("tracks_" + currentPlaylistId).apply();
                    playlists.remove(currentPlaylistId);
                    currentPlaylistId = playlists.keySet().iterator().next();
                    savePlaylists();
                    refreshPlaylistAdapter();
                    playlistSpinner.setSelection(0);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void removeTrack(int position) {
        if (position < 0 || position >= trackUris.size()) return;
        trackUris.remove(position);
        trackNames.remove(position);
        saveTracks();
        refreshTrackList();
    }

    private void loadPlaylists() {
        playlists.clear();
        String raw = prefs.getString("playlists", null);
        try {
            if (raw != null) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    playlists.put(item.getString("id"), item.getString("name"));
                }
            }
        } catch (Exception ignored) { }
        if (playlists.isEmpty()) playlists.put("default", "Mi música");
        currentPlaylistId = prefs.getString("current_playlist", playlists.keySet().iterator().next());
        if (!playlists.containsKey(currentPlaylistId)) currentPlaylistId = playlists.keySet().iterator().next();
        savePlaylists();
    }

    private void savePlaylists() {
        JSONArray array = new JSONArray();
        try {
            for (Map.Entry<String, String> entry : playlists.entrySet()) {
                JSONObject item = new JSONObject();
                item.put("id", entry.getKey());
                item.put("name", entry.getValue());
                array.put(item);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("playlists", array.toString())
                .putString("current_playlist", currentPlaylistId).apply();
    }

    private void refreshPlaylistAdapter() {
        if (playlistSpinner == null) return;
        playlistAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new ArrayList<>(playlists.values()));
        playlistAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playlistSpinner.setAdapter(playlistAdapter);
    }

    private void selectSavedPlaylist() {
        refreshPlaylistAdapter();
        int index = new ArrayList<>(playlists.keySet()).indexOf(currentPlaylistId);
        playlistSpinner.setSelection(Math.max(0, index));
        loadTracks(currentPlaylistId);
    }

    private void loadTracks(String playlistId) {
        trackUris.clear();
        trackNames.clear();
        String raw = prefs.getString("tracks_" + playlistId, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                trackUris.add(item.getString("uri"));
                trackNames.add(item.getString("name"));
            }
        } catch (Exception ignored) { }
        currentServiceIndex = Math.max(0, Math.min(currentServiceIndex, Math.max(0, trackUris.size() - 1)));
        refreshTrackList();
        syncBoundService();
    }

    private void saveTracks() {
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < trackUris.size(); i++) {
                JSONObject item = new JSONObject();
                item.put("uri", trackUris.get(i));
                item.put("name", trackNames.get(i));
                array.put(item);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("tracks_" + currentPlaylistId, array.toString()).apply();
    }

    private void refreshTrackList() {
        if (playlistTrackAdapter != null) playlistTrackAdapter.notifyDataSetChanged();
        if (trackCount != null) trackCount.setText(trackUris.size() + (trackUris.size() == 1 ? " pista" : " pistas"));
    }

    private void loadProfiles() {
        profiles.clear();
        profiles.add(new SoundProfile("auto", "Automático · Perfil estándar", "auto",
                "Selecciona automáticamente el perfil estándar apropiado para la salida activa.",
                new float[]{0, 0, 0, 0, 0}, 0f, true));
        profiles.add(new SoundProfile("speaker_default",
                "Parlantes del teléfono · " + Build.MANUFACTURER + " " + Build.MODEL,
                "speaker", "Perfil estándar conservador para parlantes móviles.",
                new float[]{-3.5f, -1.5f, 0f, 0.5f, -1.5f}, -0.5f, true));
        profiles.add(new SoundProfile("headphones_default", "Auriculares estándar", "headphones",
                "Perfil neutro estándar para auriculares sin medición disponible.",
                new float[]{0f, 0f, 0f, -0.5f, -1f}, 0f, true));
        profiles.add(new SoundProfile("sunvito_s20", "Sunvito S20", "headphones",
                "Perfil estimado y suave para las pruebas personales.",
                new float[]{-1f, -1.5f, 0f, -1.8f, -2.5f}, 0f, true));
        try {
            JSONArray array = new JSONArray(prefs.getString("sound_profiles", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONArray gainsArray = item.getJSONArray("gains");
                float[] gains = new float[5];
                for (int j = 0; j < 5; j++) gains[j] = (float) gainsArray.optDouble(j, 0);
                profiles.add(new SoundProfile(item.getString("id"), item.getString("name"),
                        "headphones", item.optString("description", "Perfil encontrado"), gains,
                        (float) item.optDouble("preamp", 0), false));
            }
        } catch (Exception ignored) { }
    }

    private void saveProfiles() {
        JSONArray array = new JSONArray();
        try {
            for (SoundProfile profile : profiles) {
                if (profile.builtIn) continue;
                JSONObject item = new JSONObject();
                item.put("id", profile.id);
                item.put("name", profile.name);
                item.put("description", profile.description);
                item.put("preamp", profile.preampDb);
                JSONArray gains = new JSONArray();
                for (float gain : profile.gains) gains.put(gain);
                item.put("gains", gains);
                array.put(item);
            }
        } catch (Exception ignored) { }
        prefs.edit().putString("sound_profiles", array.toString())
                .putString("active_profile", activeProfileId).apply();
    }

    private void saveCurrentNightLimit() {
        int value = volumeBar.getProgress() - 60;
        prefs.edit().putInt(nightLimitKey(), value).putBoolean("lock_night_maximum", true).apply();
        lockNightMaximum.setChecked(true);
        lockNightMaximum.setText("Usar mi máximo nocturno: " + formatDb(value));
        Toast.makeText(this, "Máximo nocturno guardado para este perfil", Toast.LENGTH_SHORT).show();
    }

    private int getNightLimit() {
        return prefs.getInt(nightLimitKey(), DEFAULT_NIGHT_DB);
    }

    private String nightLimitKey() {
        return "night_limit_" + (activeProfileId == null ? "auto" : activeProfileId);
    }

    private void enforceNightLimit() {
        if (!nightMode || lockNightMaximum == null || !lockNightMaximum.isChecked()) return;
        int db = volumeBar.getProgress() - 60;
        if (db > getNightLimit()) volumeBar.setProgress(getNightLimit() + 60);
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(
                new String[]{Manifest.permission.READ_MEDIA_AUDIO}, REQUEST_MEDIA_PERMISSION);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_MEDIA_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                      @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) scanLibrary();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERMISSION);
        }
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(PlaybackService.BROADCAST_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private String dbKey() { return nightMode ? "night_db" : "normal_db"; }
    private String fmKey() { return nightMode ? "fm_night" : "fm_normal"; }

    private String cleanTitle(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 && value.length() - dot <= 6 ? value.substring(0, dot) : value;
    }

    private String formatDb(int db) {
        return (db < 0 ? "−" + Math.abs(db) : String.valueOf(db)) + " dB";
    }

    private String formatTime(int milliseconds) {
        int total = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private String safeFileName(String value) {
        return (value == null ? "Lista" : value).replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void updateNavigation(int selected) {
        if (navButtons == null) return;
        for (int i = 0; i < navButtons.length; i++) {
            boolean active = i == selected;
            navButtons[i].setTextColor(active ? Color.rgb(3, 22, 18) : C_MUTED);
            navButtons[i].setBackground(roundRect(active ? C_GREEN : C_PANEL,
                    dp(10), active ? 0 : C_PANEL_2, active ? 0 : 1));
        }
    }

    private ScrollView pageScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);
        return scroll;
    }

    private LinearLayout pageColumn() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, dp(8));
        root.setBackgroundColor(C_BG);
        return root;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackground(roundRect(C_PANEL, dp(18), Color.rgb(35, 72, 90), 1));
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 13, C_CYAN, true);
        view.setPadding(0, 0, 0, dp(9));
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
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
        view.setTextColor(C_TEXT);
        view.setBackground(roundRect(C_PANEL_2, dp(12), C_BLUE, 1));
        view.setPadding(dp(8), 0, dp(8), 0);
        return view;
    }

    private Button smallButton(String value) {
        Button view = button(value);
        view.setTextSize(11);
        return view;
    }

    private Button transportButton(String value) {
        Button view = button(value);
        view.setTextSize(18);
        view.setBackground(roundRect(Color.rgb(24, 53, 68), dp(28), C_BLUE, 1));
        return view;
    }

    private Button compactTransport(String value) {
        Button view = button(value);
        view.setTextSize(14);
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    private void styleModeButton(Button button, boolean active) {
        button.setTextColor(active ? Color.rgb(3, 22, 18) : C_TEXT);
        button.setBackground(roundRect(active ? C_GREEN : C_PANEL_2,
                dp(13), active ? 0 : C_BLUE, active ? 0 : 1));
    }

    private void styleCheckBox(CheckBox box, String label) {
        box.setText(label);
        box.setTextColor(C_TEXT);
        box.setTextSize(14);
        box.setPadding(0, dp(5), 0, dp(5));
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private View space(int width) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(9), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, dp(height), 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class FixedPageAdapter extends RecyclerView.Adapter<FixedPageAdapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(MainActivity.this);
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(frame);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            View page = pages.get(position);
            if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
            holder.frame.removeAllViews();
            holder.frame.addView(page, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        @Override public int getItemCount() { return pages.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout frame;
            Holder(FrameLayout frame) { super(frame); this.frame = frame; }
        }
    }

    private static final class LibraryTrack {
        final String uri;
        final String title;
        final String artist;
        final String album;
        LibraryTrack(String uri, String title, String artist, String album) {
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.album = album;
        }
    }

    private static final class SoundProfile {
        final String id;
        final String name;
        final String type;
        final String description;
        final float[] gains;
        final float preampDb;
        final boolean builtIn;
        SoundProfile(String id, String name, String type, String description,
                     float[] gains, float preampDb, boolean builtIn) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.description = description;
            this.gains = gains;
            this.preampDb = preampDb;
            this.builtIn = builtIn;
        }
    }
}