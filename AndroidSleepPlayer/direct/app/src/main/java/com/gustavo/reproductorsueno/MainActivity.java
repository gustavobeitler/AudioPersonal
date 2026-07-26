package com.gustavo.reproductorsueno;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 1001;
    private static final int PICK_PLAYLIST = 1002;
    private static final int REQUEST_MEDIA_PERMISSION = 77;
    private static final int LOCKED_NIGHT_MAX_DB = -40;

    private static final int C_BG_NORMAL = Color.rgb(10, 21, 31);
    private static final int C_BG_NIGHT = Color.rgb(4, 8, 13);
    private static final int C_PANEL = Color.rgb(18, 35, 48);
    private static final int C_PANEL_NIGHT = Color.rgb(11, 20, 29);
    private static final int C_CYAN = Color.rgb(40, 203, 216);
    private static final int C_GREEN = Color.rgb(58, 210, 142);
    private static final int C_BLUE = Color.rgb(42, 122, 205);
    private static final int C_TEXT = Color.rgb(238, 245, 248);
    private static final int C_MUTED = Color.rgb(158, 177, 189);
    private static final int C_WARNING = Color.rgb(222, 177, 112);

    private final LinkedHashMap<String, String> playlists = new LinkedHashMap<>();
    private final ArrayList<String> trackUris = new ArrayList<>();
    private final ArrayList<String> trackNames = new ArrayList<>();

    private SharedPreferences prefs;
    private TrackAdapter trackAdapter;
    private ArrayAdapter<String> playlistAdapter;
    private Spinner playlistSpinner;
    private TextView nowTitle;
    private TextView nowStatus;
    private TextView attenuationLabel;
    private TextView modeDescription;
    private TextView fmDescription;
    private TextView trackCount;
    private SeekBar attenuationBar;
    private CheckBox lockNightMaximum;
    private CheckBox sunvitoProfile;
    private CheckBox shuffle;
    private Button normalModeButton;
    private Button nightModeButton;
    private Button fmButton;
    private ScrollView screen;
    private LinearLayout root;
    private boolean nightMode;
    private boolean fmEnabled;
    private String currentPlaylistId;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PlaybackService.BROADCAST_STATE.equals(intent.getAction())) return;
            String title = intent.getStringExtra(PlaybackService.EXTRA_TITLE);
            boolean playing = intent.getBooleanExtra(PlaybackService.EXTRA_PLAYING, false);
            nowTitle.setText(title == null || title.trim().isEmpty() ? "Sin selección" : cleanTitle(title));
            nowStatus.setText(playing ? "REPRODUCIENDO" : "EN PAUSA");
            nowStatus.setTextColor(playing ? C_GREEN : C_MUTED);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        nightMode = prefs.getBoolean("night_mode", true);
        fmEnabled = prefs.getBoolean(fmKey(), false);
        loadPlaylists();
        buildInterface();
        registerStateReceiver();
        requestNotificationPermission();
    }

    private void buildInterface() {
        screen = new ScrollView(this);
        screen.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(34));
        screen.addView(root);

        LinearLayout header = horizontal();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("REPRODUCTOR DE MÚSICA", 23, C_TEXT, true);
        TextView brand = text("RADIOENLACE AUDIO  ·  BETA 0.2", 11, C_CYAN, true);
        heading.addView(title, matchWrap());
        heading.addView(brand, matchWrap());
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView device = text("SUNVITO S20", 11, C_GREEN, true);
        device.setGravity(Gravity.CENTER);
        device.setPadding(dp(12), dp(8), dp(12), dp(8));
        device.setBackground(roundRect(Color.rgb(17, 55, 54), dp(18), C_GREEN, 1));
        header.addView(device, wrapWrap());
        root.addView(header, matchWrap());

        LinearLayout modePanel = card();
        modePanel.addView(sectionTitle("MODO DE ESCUCHA"), matchWrap());
        LinearLayout modes = horizontal();
        normalModeButton = modeButton("NORMAL");
        nightModeButton = modeButton("NOCTURNO");
        normalModeButton.setOnClickListener(v -> setMode(false));
        nightModeButton.setOnClickListener(v -> setMode(true));
        modes.addView(normalModeButton, weighted(48));
        modes.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        modes.addView(nightModeButton, weighted(48));
        modePanel.addView(modes, matchWrap());
        modeDescription = text("", 13, C_MUTED, false);
        modeDescription.setPadding(0, dp(10), 0, 0);
        modePanel.addView(modeDescription, matchWrap());
        root.addView(modePanel, cardParams());

        LinearLayout nowCard = card();
        nowCard.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView albumArt = text("♫", 72, C_CYAN, false);
        albumArt.setGravity(Gravity.CENTER);
        albumArt.setBackground(roundRect(Color.rgb(14, 52, 72), dp(22), C_BLUE, 1));
        nowCard.addView(albumArt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)));
        nowStatus = text("EN PAUSA", 11, C_MUTED, true);
        nowStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        nowStatus.setPadding(0, dp(16), 0, dp(3));
        nowCard.addView(nowStatus, matchWrap());
        nowTitle = text("Sin selección", 21, C_TEXT, true);
        nowTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        nowTitle.setMaxLines(2);
        nowCard.addView(nowTitle, matchWrap());

        LinearLayout controls = horizontal();
        controls.setPadding(0, dp(14), 0, 0);
        Button previous = transportButton("◀");
        previous.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_PREVIOUS, -1));
        Button playPause = transportButton("▶  ❚❚");
        playPause.setTextSize(18);
        playPause.setBackground(roundRect(C_GREEN, dp(28), 0, 0));
        playPause.setTextColor(Color.rgb(3, 22, 18));
        playPause.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_TOGGLE, -1));
        Button next = transportButton("▶");
        next.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_NEXT, -1));
        controls.addView(previous, weighted(56));
        controls.addView(space(dp(10)), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(playPause, weighted(60));
        controls.addView(space(dp(10)), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(next, weighted(56));
        nowCard.addView(controls, matchWrap());
        root.addView(nowCard, cardParams());

        LinearLayout soundCard = card();
        soundCard.addView(sectionTitle("PROCESAMIENTO Y VOLUMEN"), matchWrap());

        fmButton = button("SONIDO FM");
        fmButton.setOnClickListener(v -> {
            fmEnabled = !fmEnabled;
            prefs.edit().putBoolean(fmKey(), fmEnabled).apply();
            updateFmButton();
            sendSettings();
        });
        soundCard.addView(fmButton, matchHeight(dp(50)));
        fmDescription = text("", 13, C_MUTED, false);
        fmDescription.setPadding(0, dp(8), 0, dp(12));
        soundCard.addView(fmDescription, matchWrap());

        attenuationLabel = text("", 29, C_CYAN, true);
        attenuationLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        soundCard.addView(attenuationLabel, matchWrap());
        attenuationBar = new SeekBar(this);
        attenuationBar.setMax(60);
        soundCard.addView(attenuationBar, matchWrap());

        lockNightMaximum = new CheckBox(this);
        styleCheckBox(lockNightMaximum, "Protección nocturna: máximo −40 dB");
        lockNightMaximum.setChecked(prefs.getBoolean("lock_night_maximum", true));
        lockNightMaximum.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("lock_night_maximum", checked).apply();
            enforceNightLimit();
        });
        soundCard.addView(lockNightMaximum, matchWrap());

        sunvitoProfile = new CheckBox(this);
        styleCheckBox(sunvitoProfile, "Perfil de auriculares Sunvito S20");
        sunvitoProfile.setChecked(prefs.getBoolean("sunvito_profile", true));
        sunvitoProfile.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("sunvito_profile", checked).apply();
            sendSettings();
        });
        soundCard.addView(sunvitoProfile, matchWrap());

        shuffle = new CheckBox(this);
        styleCheckBox(shuffle, "Orden aleatorio");
        shuffle.setChecked(prefs.getBoolean("shuffle", false));
        shuffle.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("shuffle", checked).apply();
            sendSettings();
        });
        soundCard.addView(shuffle, matchWrap());
        root.addView(soundCard, cardParams());

        attenuationBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int db = progress - 60;
                if (nightMode && lockNightMaximum.isChecked() && db > LOCKED_NIGHT_MAX_DB) {
                    db = LOCKED_NIGHT_MAX_DB;
                    if (seekBar.getProgress() != db + 60) seekBar.setProgress(db + 60);
                }
                attenuationLabel.setText(formatDb(db));
                prefs.edit().putInt(dbKey(), db).apply();
                sendSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        LinearLayout libraryCard = card();
        LinearLayout libraryHeader = horizontal();
        libraryHeader.addView(sectionTitle("LISTAS DE REPRODUCCIÓN"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        trackCount = text("0 pistas", 12, C_CYAN, true);
        libraryHeader.addView(trackCount, wrapWrap());
        libraryCard.addView(libraryHeader, matchWrap());

        playlistSpinner = new Spinner(this);
        refreshPlaylistAdapter();
        libraryCard.addView(playlistSpinner, matchHeight(dp(52)));
        playlistSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= playlists.size()) return;
                currentPlaylistId = new ArrayList<>(playlists.keySet()).get(position);
                prefs.edit().putString("current_playlist", currentPlaylistId).apply();
                loadTracks(currentPlaylistId);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        LinearLayout listActions = horizontal();
        Button newList = smallButton("NUEVA");
        newList.setOnClickListener(v -> createPlaylistDialog());
        Button deleteList = smallButton("ELIMINAR");
        deleteList.setOnClickListener(v -> deleteCurrentPlaylist());
        listActions.addView(newList, weighted(46));
        listActions.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        listActions.addView(deleteList, weighted(46));
        libraryCard.addView(listActions, matchWrap());

        LinearLayout importActions = horizontal();
        Button addMusic = smallButton("AGREGAR MÚSICA");
        addMusic.setOnClickListener(v -> openAudioPicker());
        Button importList = smallButton("IMPORTAR LISTA");
        importList.setOnClickListener(v -> showImportOptions());
        importActions.addView(addMusic, weighted(50));
        importActions.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        importActions.addView(importList, weighted(50));
        libraryCard.addView(importActions, matchWrap());

        ListView listView = new ListView(this);
        trackAdapter = new TrackAdapter(this, trackNames);
        listView.setAdapter(trackAdapter);
        listView.setDividerHeight(0);
        listView.setOnItemClickListener((parent, view, position, id) -> sendPlayerCommand(
                PlaybackService.ACTION_PLAY_INDEX, position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            removeTrack(position);
            return true;
        });
        libraryCard.addView(listView, matchHeight(dp(310)));

        Button clear = button("VACIAR LISTA ACTUAL");
        clear.setOnClickListener(v -> confirmClearPlaylist());
        libraryCard.addView(clear, matchHeight(dp(48)));
        root.addView(libraryCard, cardParams());

        TextView warning = text(
                "El valor en dB es atenuación digital. El nivel acústico real también depende del volumen multimedia del teléfono, de los auriculares y de su ajuste en el oído.",
                12, C_WARNING, false);
        warning.setPadding(dp(8), dp(8), dp(8), 0);
        root.addView(warning, matchWrap());

        setContentView(screen);
        selectSavedPlaylist();
        applyModeVisuals();
    }

    private void setMode(boolean night) {
        if (nightMode == night) return;
        prefs.edit().putInt(dbKey(), attenuationBar.getProgress() - 60).apply();
        nightMode = night;
        prefs.edit().putBoolean("night_mode", nightMode).apply();
        fmEnabled = prefs.getBoolean(fmKey(), false);
        attenuationBar.setProgress(prefs.getInt(dbKey(), nightMode ? -40 : -8) + 60);
        enforceNightLimit();
        applyModeVisuals();
        sendSettings();
    }

    private void applyModeVisuals() {
        int background = nightMode ? C_BG_NIGHT : C_BG_NORMAL;
        screen.setBackgroundColor(background);
        root.setBackgroundColor(background);
        styleModeButton(normalModeButton, !nightMode);
        styleModeButton(nightModeButton, nightMode);
        lockNightMaximum.setVisibility(nightMode ? View.VISIBLE : View.GONE);
        modeDescription.setText(nightMode
                ? "Volumen protegido y procesamiento suave para escuchar durante toda la noche."
                : "Volumen diurno independiente, con mayor presencia y margen musical.");
        updateFmButton();
        int db = prefs.getInt(dbKey(), nightMode ? -40 : -8);
        attenuationBar.setProgress(db + 60);
        attenuationLabel.setText(formatDb(attenuationBar.getProgress() - 60));
    }

    private void updateFmButton() {
        fmButton.setText(fmEnabled ? "SONIDO FM  ·  ACTIVO" : "SONIDO FM  ·  APAGADO");
        fmButton.setTextColor(fmEnabled ? Color.rgb(3, 22, 18) : C_TEXT);
        fmButton.setBackground(roundRect(fmEnabled ? C_GREEN : Color.rgb(31, 58, 74), dp(14), fmEnabled ? 0 : C_BLUE, fmEnabled ? 0 : 1));
        fmDescription.setText(fmEnabled
                ? (nightMode
                    ? "Compresión y limitación FM suave, respetando el máximo nocturno."
                    : "Compresión multibanda, presencia y limitador para un sonido de FM bien procesada.")
                : "Reproducción sin procesador FM; se conserva el perfil S20 y el volumen del modo activo.");
    }

    private void enforceNightLimit() {
        if (!nightMode || !lockNightMaximum.isChecked()) return;
        int db = attenuationBar.getProgress() - 60;
        if (db > LOCKED_NIGHT_MAX_DB) attenuationBar.setProgress(LOCKED_NIGHT_MAX_DB + 60);
    }

    private String dbKey() { return nightMode ? "night_db" : "normal_db"; }
    private String fmKey() { return nightMode ? "fm_night" : "fm_normal"; }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_AUDIO);
    }

    private void showImportOptions() {
        new AlertDialog.Builder(this)
                .setTitle("Importar lista")
                .setItems(new String[]{"Archivo M3U, M3U8 o PLS", "Listas del sistema Android"}, (dialog, which) -> {
                    if (which == 0) openPlaylistPicker();
                    else importSystemPlaylists();
                })
                .show();
    }

    private void openPlaylistPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegURL",
                "application/vnd.apple.mpegurl", "audio/x-scpls", "text/plain"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_PLAYLIST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PICK_AUDIO) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) addUri(clipData.getItemAt(i).getUri(), null);
            } else if (data.getData() != null) {
                addUri(data.getData(), null);
            }
            saveTracks();
            refreshTrackList();
        } else if (requestCode == PICK_PLAYLIST && data.getData() != null) {
            importPlaylistFile(data.getData());
        }
    }

    private void importPlaylistFile(Uri uri) {
        int before = trackUris.size();
        String pendingTitle = null;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        try (InputStream input = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF:")) {
                    int comma = line.indexOf(',');
                    if (comma >= 0 && comma + 1 < line.length()) pendingTitle = line.substring(comma + 1).trim();
                    continue;
                }
                if (line.startsWith("#") || line.startsWith("[playlist]") || line.startsWith("NumberOfEntries")) continue;
                if (line.toLowerCase(Locale.ROOT).startsWith("file") && line.contains("=")) {
                    line = line.substring(line.indexOf('=') + 1).trim();
                } else if (line.toLowerCase(Locale.ROOT).startsWith("title") && line.contains("=")) {
                    pendingTitle = line.substring(line.indexOf('=') + 1).trim();
                    continue;
                } else if (line.toLowerCase(Locale.ROOT).startsWith("length") && line.contains("=")) {
                    continue;
                }
                Uri trackUri = parsePlaylistLocation(line);
                if (trackUri != null) addUri(trackUri, pendingTitle);
                pendingTitle = null;
            }
            saveTracks();
            refreshTrackList();
            int imported = trackUris.size() - before;
            Toast.makeText(this, imported + " pistas importadas", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo leer esa lista", Toast.LENGTH_LONG).show();
        }
    }

    private Uri parsePlaylistLocation(String value) {
        try {
            if (value.startsWith("content://") || value.startsWith("file://")) return Uri.parse(value);
            if (value.startsWith("/")) return Uri.fromFile(new File(value));
            return Uri.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void importSystemPlaylists() {
        if (!hasAudioPermission()) {
            requestAudioPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            Toast.makeText(this, "Android moderno puede ocultar las listas privadas. Prueba primero importar un M3U.", Toast.LENGTH_LONG).show();
        }
        ArrayList<Long> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME},
                null, null, MediaStore.Audio.Playlists.NAME + " ASC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME);
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(idColumn));
                    names.add(cursor.getString(nameColumn));
                }
            }
        } catch (Exception ignored) { }
        if (ids.isEmpty()) {
            Toast.makeText(this, "No se encontraron listas públicas del sistema", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Listas encontradas")
                .setItems(names.toArray(new String[0]), (dialog, which) -> importSystemPlaylist(ids.get(which), names.get(which)))
                .show();
    }

    private void importSystemPlaylist(long playlistId, String playlistName) {
        String id = "system_" + playlistId + "_" + System.currentTimeMillis();
        playlists.put(id, playlistName == null ? "Lista importada" : playlistName);
        currentPlaylistId = id;
        trackUris.clear();
        trackNames.clear();
        Uri members = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
        try (Cursor cursor = getContentResolver().query(
                members,
                new String[]{MediaStore.Audio.Playlists.Members.AUDIO_ID, MediaStore.Audio.Playlists.Members.TITLE},
                null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC")) {
            if (cursor != null) {
                int audioId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID);
                int title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.TITLE);
                while (cursor.moveToNext()) {
                    long mediaId = cursor.getLong(audioId);
                    Uri mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId);
                    addUri(mediaUri, cursor.getString(title));
                }
            }
        } catch (Exception ignored) { }
        savePlaylists();
        saveTracks();
        refreshPlaylistAdapter();
        playlistSpinner.setSelection(playlists.size() - 1);
        refreshTrackList();
        Toast.makeText(this, trackUris.size() + " pistas importadas", Toast.LENGTH_LONG).show();
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, REQUEST_MEDIA_PERMISSION);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_MEDIA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            importSystemPlaylists();
        }
    }

    private void addUri(Uri uri, String preferredName) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        String value = uri.toString();
        if (trackUris.contains(value)) return;
        trackUris.add(value);
        trackNames.add(preferredName == null || preferredName.trim().isEmpty() ? resolveName(uri) : preferredName.trim());
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

    private void sendPlayerCommand(String action, int index) {
        if (trackUris.isEmpty()) {
            Toast.makeText(this, "Primero agrega música a la lista", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = basePlayerIntent(action);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_URIS, new ArrayList<>(trackUris));
        intent.putStringArrayListExtra(PlaybackService.EXTRA_NAMES, new ArrayList<>(trackNames));
        intent.putExtra(PlaybackService.EXTRA_INDEX, index);
        startPlaybackService(intent);
    }

    private void sendSettings() {
        if (attenuationBar == null || sunvitoProfile == null || shuffle == null) return;
        startPlaybackService(basePlayerIntent(PlaybackService.ACTION_SETTINGS));
    }

    private Intent basePlayerIntent(String action) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        intent.putExtra(PlaybackService.EXTRA_ATTENUATION_DB, attenuationBar.getProgress() - 60);
        intent.putExtra(PlaybackService.EXTRA_PROFILE, sunvitoProfile.isChecked());
        intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle.isChecked());
        intent.putExtra(PlaybackService.EXTRA_NIGHT_MODE, nightMode);
        intent.putExtra(PlaybackService.EXTRA_FM_PROCESSOR, fmEnabled);
        return intent;
    }

    private void startPlaybackService(Intent intent) {
        try {
            startService(intent);
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo iniciar la reproducción", Toast.LENGTH_SHORT).show();
        }
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
        String name = playlists.get(currentPlaylistId);
        new AlertDialog.Builder(this)
                .setTitle("Eliminar lista")
                .setMessage("¿Eliminar “" + name + "”?")
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
        String name = trackNames.get(position);
        new AlertDialog.Builder(this)
                .setTitle("Quitar pista")
                .setMessage("¿Quitar “" + cleanTitle(name) + "” de esta lista?")
                .setPositiveButton("Quitar", (dialog, which) -> {
                    trackUris.remove(position);
                    trackNames.remove(position);
                    saveTracks();
                    refreshTrackList();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmClearPlaylist() {
        new AlertDialog.Builder(this)
                .setTitle("Vaciar lista")
                .setMessage("Se quitarán todas las pistas de la lista actual.")
                .setPositiveButton("Vaciar", (dialog, which) -> {
                    trackUris.clear();
                    trackNames.clear();
                    saveTracks();
                    refreshTrackList();
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
        if (playlists.isEmpty()) playlists.put("default", "Para dormir");
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
        ArrayList<String> names = new ArrayList<>(playlists.values());
        playlistAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(C_TEXT);
                view.setTextSize(16);
                view.setPadding(dp(12), 0, dp(12), 0);
                view.setBackground(roundRect(Color.rgb(24, 48, 63), dp(12), C_BLUE, 1));
                return view;
            }
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(Color.BLACK);
                view.setTextSize(16);
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }
        };
        playlistAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (playlistSpinner != null) playlistSpinner.setAdapter(playlistAdapter);
    }

    private void selectSavedPlaylist() {
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
        refreshTrackList();
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
        if (trackAdapter != null) trackAdapter.notifyDataSetChanged();
        if (trackCount != null) trackCount.setText(trackUris.size() == 1 ? "1 pista" : trackUris.size() + " pistas");
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(PlaybackService.BROADCAST_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, filter);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 78);
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private String cleanTitle(String value) {
        if (value == null) return "Pista de audio";
        int query = value.indexOf('?');
        if (query > 0) value = value.substring(0, query);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".m4a") || lower.endsWith(".ogg")) {
            value = value.substring(0, value.lastIndexOf('.'));
        }
        return value;
    }

    private String formatDb(int db) {
        return (db < 0 ? "−" + Math.abs(db) : String.valueOf(db)) + " dB";
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundRect(nightMode ? C_PANEL_NIGHT : C_PANEL, dp(18), Color.rgb(31, 73, 91), 1));
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 12, C_CYAN, true);
        view.setPadding(0, 0, 0, dp(12));
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
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setAllCaps(false);
        view.setTextColor(C_TEXT);
        view.setBackground(roundRect(Color.rgb(31, 58, 74), dp(13), C_BLUE, 1));
        return view;
    }

    private Button smallButton(String value) {
        Button view = button(value);
        view.setTextSize(11);
        return view;
    }

    private Button modeButton(String value) {
        Button view = button(value);
        view.setTextSize(12);
        return view;
    }

    private void styleModeButton(Button view, boolean active) {
        view.setTextColor(active ? Color.rgb(3, 22, 18) : C_TEXT);
        view.setBackground(roundRect(active ? C_GREEN : Color.rgb(27, 52, 68), dp(13), active ? 0 : C_BLUE, active ? 0 : 1));
    }

    private Button transportButton(String value) {
        Button view = button(value);
        view.setTextSize(22);
        return view;
    }

    private void styleCheckBox(CheckBox view, String value) {
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(C_TEXT);
        view.setButtonTintList(android.content.res.ColorStateList.valueOf(C_GREEN));
        view.setPadding(0, dp(3), 0, dp(3));
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
        view.setMinimumWidth(width);
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
        params.setMargins(0, dp(14), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        params.setMargins(0, dp(6), 0, dp(2));
        return params;
    }

    private LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, dp(height), 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class TrackAdapter extends ArrayAdapter<String> {
        TrackAdapter(Context context, ArrayList<String> items) {
            super(context, android.R.layout.simple_list_item_1, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            view.setText((position + 1) + ".  " + cleanTitle(getItem(position)));
            view.setTextColor(C_TEXT);
            view.setTextSize(15);
            view.setSingleLine(true);
            view.setPadding(dp(12), dp(12), dp(12), dp(12));
            view.setBackground(roundRect(position % 2 == 0 ? Color.rgb(18, 40, 53) : Color.rgb(15, 34, 46), dp(9), 0, 0));
            return view;
        }
    }
}
