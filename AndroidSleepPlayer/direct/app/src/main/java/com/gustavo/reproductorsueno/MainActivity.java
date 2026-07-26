package com.gustavo.reproductorsueno;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 1001;
    private static final int LOCKED_MAX_DB = -40;

    private final LinkedHashMap<String, String> playlists = new LinkedHashMap<>();
    private final ArrayList<String> trackUris = new ArrayList<>();
    private final ArrayList<String> trackNames = new ArrayList<>();

    private SharedPreferences prefs;
    private ArrayAdapter<String> trackAdapter;
    private ArrayAdapter<String> playlistAdapter;
    private Spinner playlistSpinner;
    private TextView attenuationLabel;
    private TextView nowPlaying;
    private SeekBar attenuationBar;
    private CheckBox lockMaximum;
    private CheckBox sunvitoProfile;
    private CheckBox shuffle;
    private String currentPlaylistId;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PlaybackService.BROADCAST_STATE.equals(intent.getAction())) return;
            String title = intent.getStringExtra(PlaybackService.EXTRA_TITLE);
            boolean playing = intent.getBooleanExtra(PlaybackService.EXTRA_PLAYING, false);
            nowPlaying.setText((playing ? "Reproduciendo: " : "En pausa: ")
                    + (title == null || title.isBlank() ? "sin selección" : title));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        loadPlaylists();
        buildInterface();
        registerStateReceiver();
        requestNotificationPermission();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 11, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        scroll.addView(root);

        TextView title = text("Reproductor Sueño", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView subtitle = text("Reproducción continua, sin temporizador ni apagado automático.", 15,
                Color.rgb(185, 190, 202));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(5), 0, dp(18));
        root.addView(subtitle, matchWrap());

        nowPlaying = text("En pausa: sin selección", 16, Color.rgb(225, 230, 238));
        nowPlaying.setPadding(dp(12), dp(12), dp(12), dp(12));
        nowPlaying.setBackgroundColor(Color.rgb(25, 29, 38));
        root.addView(nowPlaying, matchWrap());

        TextView listTitle = text("Lista de reproducción", 18, Color.WHITE);
        listTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(listTitle, matchWrap());

        LinearLayout playlistRow = horizontal();
        playlistSpinner = new Spinner(this);
        refreshPlaylistAdapter();
        playlistRow.addView(playlistSpinner, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button addList = button("Nueva lista");
        addList.setOnClickListener(v -> createPlaylistDialog());
        playlistRow.addView(addList, wrapWrap());

        Button deleteList = button("Eliminar");
        deleteList.setOnClickListener(v -> deleteCurrentPlaylist());
        playlistRow.addView(deleteList, wrapWrap());
        root.addView(playlistRow, matchWrap());

        playlistSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= playlists.size()) return;
                currentPlaylistId = new ArrayList<>(playlists.keySet()).get(position);
                prefs.edit().putString("current_playlist", currentPlaylistId).apply();
                loadTracks(currentPlaylistId);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        Button addMusic = button("Agregar música del teléfono");
        addMusic.setOnClickListener(v -> openAudioPicker());
        root.addView(addMusic, matchHeight(dp(52)));

        ListView listView = new ListView(this);
        trackAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, trackNames);
        listView.setAdapter(trackAdapter);
        listView.setDividerHeight(1);
        listView.setOnItemClickListener((parent, view, position, id) -> sendPlayerCommand(
                PlaybackService.ACTION_PLAY_INDEX, position));
        root.addView(listView, matchHeight(dp(270)));

        LinearLayout controls = horizontal();
        Button previous = button("Anterior");
        previous.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_PREVIOUS, -1));
        controls.addView(previous, weighted());

        Button playPause = button("Reproducir / Pausar");
        playPause.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_TOGGLE, -1));
        controls.addView(playPause, weighted());

        Button next = button("Siguiente");
        next.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_NEXT, -1));
        controls.addView(next, weighted());
        root.addView(controls, matchWrap());

        TextView volumeTitle = text("Atenuación interna", 18, Color.WHITE);
        volumeTitle.setPadding(0, dp(20), 0, 0);
        root.addView(volumeTitle, matchWrap());

        attenuationLabel = text("−40 dB", 24, Color.rgb(157, 214, 255));
        attenuationLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(attenuationLabel, matchWrap());

        attenuationBar = new SeekBar(this);
        attenuationBar.setMax(40);
        int savedDb = prefs.getInt("attenuation_db", -40);
        attenuationBar.setProgress(savedDb + 60);
        root.addView(attenuationBar, matchWrap());

        lockMaximum = new CheckBox(this);
        lockMaximum.setText("Bloquear el máximo en −40 dB");
        lockMaximum.setTextColor(Color.WHITE);
        lockMaximum.setChecked(prefs.getBoolean("lock_maximum", true));
        root.addView(lockMaximum, matchWrap());

        attenuationBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int db = progress - 60;
                if (lockMaximum.isChecked() && db > LOCKED_MAX_DB) {
                    db = LOCKED_MAX_DB;
                    if (seekBar.getProgress() != db + 60) seekBar.setProgress(db + 60);
                }
                attenuationLabel.setText(formatDb(db));
                prefs.edit().putInt("attenuation_db", db).apply();
                sendSettings();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        lockMaximum.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("lock_maximum", checked).apply();
            int db = attenuationBar.getProgress() - 60;
            if (checked && db > LOCKED_MAX_DB) attenuationBar.setProgress(LOCKED_MAX_DB + 60);
        });

        sunvitoProfile = new CheckBox(this);
        sunvitoProfile.setText("Perfil suave Sunvito S20");
        sunvitoProfile.setTextColor(Color.WHITE);
        sunvitoProfile.setChecked(prefs.getBoolean("sunvito_profile", true));
        sunvitoProfile.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("sunvito_profile", checked).apply();
            sendSettings();
        });
        root.addView(sunvitoProfile, matchWrap());

        shuffle = new CheckBox(this);
        shuffle.setText("Orden aleatorio");
        shuffle.setTextColor(Color.WHITE);
        shuffle.setChecked(prefs.getBoolean("shuffle", false));
        shuffle.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("shuffle", checked).apply();
            sendSettings();
        });
        root.addView(shuffle, matchWrap());

        TextView profileInfo = text(
                "El perfil S20 solo recorta bandas: graves −1 dB, medios-graves −1,5 dB, "
                        + "presencia −1,8 dB y agudos −2,5 dB. No aumenta ninguna frecuencia.",
                14, Color.rgb(175, 180, 191));
        profileInfo.setPadding(0, dp(8), 0, dp(15));
        root.addView(profileInfo, matchWrap());

        Button clear = button("Vaciar lista actual");
        clear.setOnClickListener(v -> {
            trackUris.clear();
            trackNames.clear();
            saveTracks();
            trackAdapter.notifyDataSetChanged();
            Toast.makeText(this, "Lista vaciada", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear, matchHeight(dp(48)));

        TextView warning = text(
                "−40 dB es una atenuación digital, no una medición acústica dentro del oído. "
                        + "El volumen físico también depende del nivel multimedia del teléfono, del ajuste del auricular "
                        + "y de cómo queda colocado.",
                13, Color.rgb(200, 164, 120));
        warning.setPadding(0, dp(18), 0, 0);
        root.addView(warning, matchWrap());

        setContentView(scroll);
        selectSavedPlaylist();
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK || data == null) return;

        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                addUri(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            addUri(data.getData());
        }
        saveTracks();
        trackAdapter.notifyDataSetChanged();
    }

    private void addUri(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        String value = uri.toString();
        if (trackUris.contains(value)) return;
        trackUris.add(value);
        trackNames.add(resolveName(uri));
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
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_URIS, new ArrayList<>(trackUris));
        intent.putStringArrayListExtra(PlaybackService.EXTRA_NAMES, new ArrayList<>(trackNames));
        intent.putExtra(PlaybackService.EXTRA_INDEX, index);
        intent.putExtra(PlaybackService.EXTRA_ATTENUATION_DB, attenuationBar.getProgress() - 60);
        intent.putExtra(PlaybackService.EXTRA_PROFILE, sunvitoProfile.isChecked());
        intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle.isChecked());
        startService(intent);
    }

    private void sendSettings() {
        if (attenuationBar == null || sunvitoProfile == null || shuffle == null) return;
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(PlaybackService.ACTION_SETTINGS);
        intent.putExtra(PlaybackService.EXTRA_ATTENUATION_DB, attenuationBar.getProgress() - 60);
        intent.putExtra(PlaybackService.EXTRA_PROFILE, sunvitoProfile.isChecked());
        intent.putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle.isChecked());
        try {
            startService(intent);
        } catch (Exception ignored) { }
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
        playlistAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
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
        if (trackAdapter != null) trackAdapter.notifyDataSetChanged();
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

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(PlaybackService.BROADCAST_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private String formatDb(int db) {
        return (db < 0 ? "−" + Math.abs(db) : String.valueOf(db)) + " dB";
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
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
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(54), 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
