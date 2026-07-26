package com.gustavo.reproductorsueno;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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
    private static final int LOCKED_NIGHT_MAX_DB = -40;

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
    private final ArrayList<SoundProfile> profiles = new ArrayList<>();
    private final ArrayList<View> pages = new ArrayList<>();

    private SharedPreferences prefs;
    private ViewPager2 pager;
    private Button[] navButtons;
    private TextView nowTitle;
    private TextView nowStatus;
    private TextView outputSummary;
    private TextView profileInfo;
    private TextView volumeLabel;
    private TextView trackCount;
    private TextView libraryCount;
    private Button normalModeButton;
    private Button nightModeButton;
    private Button fmButton;
    private SeekBar volumeBar;
    private CheckBox lockNightMaximum;
    private CheckBox shuffle;
    private Spinner playlistSpinner;
    private Spinner profileSpinner;
    private ArrayAdapter<String> playlistAdapter;
    private ArrayAdapter<String> profileAdapter;
    private ArrayAdapter<String> playlistTrackAdapter;
    private ArrayAdapter<String> libraryAdapter;
    private ArrayList<LibraryTrack> visibleLibrary = new ArrayList<>();

    private boolean nightMode;
    private boolean fmEnabled;
    private String currentPlaylistId;
    private String activeProfileId;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PlaybackService.BROADCAST_STATE.equals(intent.getAction())) return;
            String title = intent.getStringExtra(PlaybackService.EXTRA_TITLE);
            String message = intent.getStringExtra(PlaybackService.EXTRA_MESSAGE);
            boolean playing = intent.getBooleanExtra(PlaybackService.EXTRA_PLAYING, false);
            if (nowTitle != null) nowTitle.setText(title == null || title.trim().isEmpty() ? "Sin selección" : cleanTitle(title));
            if (nowStatus != null) {
                nowStatus.setText(message != null && !message.isEmpty()
                        ? message.toUpperCase(Locale.ROOT)
                        : (playing ? "REPRODUCIENDO" : "EN PAUSA"));
                nowStatus.setTextColor(message != null && !message.isEmpty() ? C_WARNING : (playing ? C_GREEN : C_MUTED));
            }
            String missing = intent.getStringExtra(PlaybackService.EXTRA_MISSING_URI);
            if (missing != null && !missing.isEmpty()) markMissingTrack(missing);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        nightMode = prefs.getBoolean("night_mode", true);
        fmEnabled = prefs.getBoolean(fmKey(), false);
        loadPlaylists();
        loadProfiles();
        activeProfileId = prefs.getString("active_profile", "auto");
        buildInterface();
        registerStateReceiver();
        requestNotificationPermission();
        if (hasAudioPermission()) scanLibrary();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout header = horizontal();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(text("REPRODUCTOR DE MÚSICA", 22, C_TEXT, true), matchWrap());
        heading.addView(text("RADIOENLACE AUDIO  ·  BETA 0.3", 11, C_CYAN, true), matchWrap());
        header.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        outputSummary = text("SALIDA ESTÁNDAR", 10, C_GREEN, true);
        outputSummary.setGravity(Gravity.CENTER);
        outputSummary.setPadding(dp(10), dp(7), dp(10), dp(7));
        outputSummary.setBackground(roundRect(Color.rgb(17, 55, 54), dp(16), C_GREEN, 1));
        header.addView(outputSummary, wrapWrap());
        root.addView(header, matchWrap());

        pages.clear();
        pages.add(buildNowPlayingPage());
        pages.add(buildLibraryPage());
        pages.add(buildPlaylistsPage());
        pages.add(buildSoundPage());

        pager = new ViewPager2(this);
        pager.setAdapter(new FixedPageAdapter());
        pager.setOffscreenPageLimit(4);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateNavigation(position); }
        });
        root.addView(pager, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        String[] tabs = {"REPRODUCIENDO", "BIBLIOTECA", "LISTAS", "SONIDO"};
        LinearLayout navigation = horizontal();
        navButtons = new Button[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            final int page = i;
            Button button = button(tabs[i]);
            button.setTextSize(10);
            button.setOnClickListener(v -> pager.setCurrentItem(page, true));
            navButtons[i] = button;
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(50), 1f));
        }
        root.addView(navigation, matchWrap());

        setContentView(root);
        selectSavedPlaylist();
        refreshProfiles();
        applyModeVisuals();
        updateNavigation(0);
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
        TextView art = text("♫", 76, C_CYAN, false);
        art.setGravity(Gravity.CENTER);
        art.setBackground(roundRect(Color.rgb(14, 52, 72), dp(24), C_BLUE, 1));
        playerCard.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(175)));
        nowStatus = text("EN PAUSA", 11, C_MUTED, true);
        nowStatus.setGravity(Gravity.CENTER);
        nowStatus.setPadding(0, dp(14), 0, dp(4));
        playerCard.addView(nowStatus, matchWrap());
        nowTitle = text("Sin selección", 21, C_TEXT, true);
        nowTitle.setGravity(Gravity.CENTER);
        nowTitle.setMaxLines(2);
        playerCard.addView(nowTitle, matchWrap());

        LinearLayout controls = horizontal();
        controls.setPadding(0, dp(14), 0, 0);
        Button previous = transportButton("◀");
        Button playPause = transportButton("▶  ❚❚");
        Button next = transportButton("▶");
        previous.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_PREVIOUS, -1));
        playPause.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_TOGGLE, -1));
        next.setOnClickListener(v -> sendPlayerCommand(PlaybackService.ACTION_NEXT, -1));
        playPause.setBackground(roundRect(C_GREEN, dp(28), 0, 0));
        playPause.setTextColor(Color.rgb(3, 22, 18));
        controls.addView(previous, weighted(56));
        controls.addView(space(dp(10)), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(playPause, weighted(60));
        controls.addView(space(dp(10)), new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(next, weighted(56));
        playerCard.addView(controls, matchWrap());
        root.addView(playerCard, cardParams());

        LinearLayout quickSound = card();
        quickSound.addView(sectionTitle("CONTROL RÁPIDO"), matchWrap());
        fmButton = button("SONIDO FM");
        fmButton.setOnClickListener(v -> {
            fmEnabled = !fmEnabled;
            prefs.edit().putBoolean(fmKey(), fmEnabled).apply();
            updateFmButton();
            sendSettings();
        });
        quickSound.addView(fmButton, matchHeight(dp(48)));
        volumeLabel = text("", 27, C_CYAN, true);
        volumeLabel.setGravity(Gravity.CENTER);
        volumeLabel.setPadding(0, dp(9), 0, 0);
        quickSound.addView(volumeLabel, matchWrap());
        volumeBar = new SeekBar(this);
        volumeBar.setMax(60);
        quickSound.addView(volumeBar, matchWrap());
        root.addView(quickSound, cardParams());

        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int db = progress - 60;
                if (nightMode && lockNightMaximum != null && lockNightMaximum.isChecked()
                        && db > LOCKED_NIGHT_MAX_DB) {
                    db = LOCKED_NIGHT_MAX_DB;
                    if (seekBar.getProgress() != db + 60) seekBar.setProgress(db + 60);
                }
                volumeLabel.setText(formatDb(db));
                prefs.edit().putInt(dbKey(), db).apply();
                sendSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        return scroll;
    }

    private View buildLibraryPage() {
        LinearLayout root = pageColumn();
        LinearLayout header = card();
        header.addView(sectionTitle("BIBLIOTECA DEL TELÉFONO"), matchWrap());
        libraryCount = text("Sin escanear", 12, C_MUTED, false);
        header.addView(libraryCount, matchWrap());

        EditText search = new EditText(this);
        search.setHint("Buscar canción, intérprete o álbum");
        search.setTextColor(C_TEXT);
        search.setHintTextColor(C_MUTED);
        search.setSingleLine(true);
        header.addView(search, matchHeight(dp(50)));

        Button scan = button("ESCANEAR MÚSICA DEL TELÉFONO");
        scan.setOnClickListener(v -> {
            if (hasAudioPermission()) scanLibrary();
            else requestAudioPermission();
        });
        header.addView(scan, matchHeight(dp(48)));
        root.addView(header, cardParams());

        ListView list = new ListView(this);
        libraryAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1,
                new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView title = view.findViewById(android.R.id.text1);
                TextView detail = view.findViewById(android.R.id.text2);
                LibraryTrack track = visibleLibrary.get(position);
                title.setText(track.title);
                title.setTextColor(C_TEXT);
                detail.setText(track.artist + (track.album.isEmpty() ? "" : "  ·  " + track.album));
                detail.setTextColor(C_MUTED);
                view.setBackgroundColor(C_BG);
                return view;
            }
        };
        list.setAdapter(libraryAdapter);
        list.setDividerHeight(1);
        list.setOnItemClickListener((parent, view, position, id) ->
                showLibraryTrackActions(visibleLibrary.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(android.text.Editable editable) {
                filterLibrary(editable.toString());
            }
        });
        return root;
    }

    private View buildPlaylistsPage() {
        LinearLayout root = pageColumn();

        LinearLayout top = card();
        LinearLayout titleRow = horizontal();
        titleRow.addView(sectionTitle("LISTAS DE REPRODUCCIÓN"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        trackCount = text("0 pistas", 12, C_CYAN, true);
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

        LinearLayout actions1 = horizontal();
        Button newList = smallButton("NUEVA");
        Button deleteList = smallButton("ELIMINAR");
        newList.setOnClickListener(v -> createPlaylistDialog());
        deleteList.setOnClickListener(v -> deleteCurrentPlaylist());
        actions1.addView(newList, weighted(46));
        actions1.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        actions1.addView(deleteList, weighted(46));
        top.addView(actions1, matchWrap());

        LinearLayout actions2 = horizontal();
        Button addMusic = smallButton("AGREGAR MÚSICA");
        Button importList = smallButton("IMPORTAR");
        Button exportList = smallButton("EXPORTAR");
        addMusic.setOnClickListener(v -> openAudioPicker());
        importList.setOnClickListener(v -> showImportOptions());
        exportList.setOnClickListener(v -> exportCurrentPlaylist());
        actions2.addView(addMusic, weighted(48));
        actions2.addView(space(dp(6)), new LinearLayout.LayoutParams(dp(6), 1));
        actions2.addView(importList, weighted(42));
        actions2.addView(space(dp(6)), new LinearLayout.LayoutParams(dp(6), 1));
        actions2.addView(exportList, weighted(42));
        top.addView(actions2, matchWrap());
        root.addView(top, cardParams());

        ListView list = new ListView(this);
        playlistTrackAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, trackNames) {
            @NonNull
            @Override public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(C_TEXT);
                view.setTextSize(15);
                view.setPadding(dp(12), dp(12), dp(12), dp(12));
                view.setBackgroundColor(C_BG);
                return view;
            }
        };
        list.setAdapter(playlistTrackAdapter);
        list.setDividerHeight(1);
        list.setOnItemClickListener((parent, view, position, id) ->
                sendPlayerCommand(PlaybackService.ACTION_PLAY_INDEX, position));
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

        LinearLayout buttons = horizontal();
        Button add = smallButton("AGREGAR AURICULARES");
        Button remove = smallButton("ELIMINAR PERFIL");
        add.setOnClickListener(v -> showAddHeadphonesDialog());
        remove.setOnClickListener(v -> deleteSelectedProfile());
        buttons.addView(add, weighted(54));
        buttons.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(remove, weighted(46));
        selected.addView(buttons, matchWrap());
        root.addView(selected, cardParams());

        LinearLayout safety = card();
        safety.addView(sectionTitle("SEGURIDAD Y REPRODUCCIÓN"), matchWrap());
        lockNightMaximum = new CheckBox(this);
        styleCheckBox(lockNightMaximum, "Protección nocturna: máximo −40 dB");
        lockNightMaximum.setChecked(prefs.getBoolean("lock_night_maximum", true));
        lockNightMaximum.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("lock_night_maximum", checked).apply();
            enforceNightLimit();
        });
        safety.addView(lockNightMaximum, matchWrap());

        shuffle = new CheckBox(this);
        styleCheckBox(shuffle, "Orden aleatorio");
        shuffle.setChecked(prefs.getBoolean("shuffle", false));
        shuffle.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean("shuffle", checked).apply();
            sendSettings();
        });
        safety.addView(shuffle, matchWrap());

        TextView routeSafety = text(
                "Si se desconectan auriculares Bluetooth, cableados o USB, la reproducción se pausa. "
                        + "La aplicación no continúa por el parlante del teléfono.",
                13, C_WARNING, false);
        routeSafety.setPadding(0, dp(10), 0, 0);
        safety.addView(routeSafety, matchWrap());
        root.addView(safety, cardParams());

        LinearLayout standards = card();
        standards.addView(sectionTitle("PERFILES PREDEFINIDOS"), matchWrap());
        standards.addView(text(
                "Sin selección manual, la aplicación usa un perfil estándar para auriculares cuando detecta "
                        + "una salida de auriculares y un perfil estándar para los parlantes del teléfono.",
                13, C_MUTED, false), matchWrap());
        TextView phone = text("Teléfono detectado: " + Build.MANUFACTURER + " " + Build.MODEL,
                13, C_CYAN, true);
        phone.setPadding(0, dp(10), 0, 0);
        standards.addView(phone, matchWrap());
        root.addView(standards, cardParams());
        return scroll;
    }

    private void setMode(boolean night) {
        if (nightMode == night) return;
        prefs.edit().putInt(dbKey(), volumeBar.getProgress() - 60).apply();
        nightMode = night;
        prefs.edit().putBoolean("night_mode", nightMode).apply();
        fmEnabled = prefs.getBoolean(fmKey(), false);
        volumeBar.setProgress(prefs.getInt(dbKey(), nightMode ? -40 : -8) + 60);
        enforceNightLimit();
        applyModeVisuals();
        sendSettings();
    }

    private void applyModeVisuals() {
        styleModeButton(normalModeButton, !nightMode);
        styleModeButton(nightModeButton, nightMode);
        if (lockNightMaximum != null) lockNightMaximum.setVisibility(nightMode ? View.VISIBLE : View.GONE);
        int db = prefs.getInt(dbKey(), nightMode ? -40 : -8);
        volumeBar.setProgress(db + 60);
        volumeLabel.setText(formatDb(volumeBar.getProgress() - 60));
        updateFmButton();
        refreshProfileSummary();
    }

    private void updateFmButton() {
        fmButton.setText(fmEnabled ? "SONIDO FM  ·  ACTIVO" : "SONIDO FM  ·  APAGADO");
        fmButton.setTextColor(fmEnabled ? Color.rgb(3, 22, 18) : C_TEXT);
        fmButton.setBackground(roundRect(fmEnabled ? C_GREEN : C_PANEL_2,
                dp(14), fmEnabled ? 0 : C_BLUE, fmEnabled ? 0 : 1));
    }

    private void showAddHeadphonesDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), 0);
        EditText brand = new EditText(this);
        brand.setHint("Marca, por ejemplo Edifier");
        EditText model = new EditText(this);
        model.setHint("Modelo exacto, por ejemplo W600BT");
        form.addView(brand, matchHeight(dp(54)));
        form.addView(model, matchHeight(dp(54)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Agregar auriculares")
                .setMessage("Escribe manualmente la marca y el modelo. No se utilizará el nombre Bluetooth.")
                .setView(form)
                .setPositiveButton("Buscar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String brandText = brand.getText().toString().trim();
            String modelText = model.getText().toString().trim();
            if (brandText.isEmpty() || modelText.isEmpty()) {
                Toast.makeText(this, "Completa marca y modelo", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            searchHeadphoneProfile(brandText, modelText);
        }));
        dialog.show();
    }

    private void searchHeadphoneProfile(String brand, String model) {
        ProgressDialog progress = ProgressDialog.show(this, "Buscando perfil",
                "Consultando mediciones disponibles para " + brand + " " + model + "…", true, false);
        HeadphoneProfileRepository.search(this, brand, model, new HeadphoneProfileRepository.Callback() {
            @Override public void onFound(HeadphoneProfileRepository.Result result) {
                progress.dismiss();
                String id = "hp_" + System.currentTimeMillis();
                SoundProfile profile = new SoundProfile(id, brand + " " + model,
                        "headphones", "Perfil medido: " + result.source,
                        result.gainsDb, result.preampDb, false);
                profiles.add(profile);
                activeProfileId = id;
                saveProfiles();
                refreshProfiles();
                Toast.makeText(MainActivity.this,
                        "Modelo encontrado. Perfil de sonido creado.", Toast.LENGTH_LONG).show();
            }

            @Override public void onNotFound() {
                progress.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Modelo no encontrado")
                        .setMessage("No se encontraron mediciones suficientes. Se utilizará la ecualización estándar para auriculares.")
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
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(activeProfileId)) selected = i;
        }
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
        SoundProfile profile = resolvedProfile();
        if (profileInfo != null) {
            profileInfo.setText(profile.description + "\n"
                    + "Modo " + (nightMode ? "Nocturno" : "Normal")
                    + (fmEnabled ? "  ·  Sonido FM activo" : "  ·  Sonido FM apagado"));
        }
        if (outputSummary != null) {
            outputSummary.setText(shortProfileName(profile.name).toUpperCase(Locale.ROOT));
        }
    }

    private SoundProfile selectedProfile() {
        for (SoundProfile profile : profiles) if (profile.id.equals(activeProfileId)) return profile;
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private SoundProfile resolvedProfile() {
        SoundProfile selected = selectedProfile();
        if (selected == null || !"auto".equals(selected.id)) return selected;
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

    private void scanLibrary() {
        if (!hasAudioPermission()) return;
        library.clear();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = value(cursor, titleColumn, "Pista");
                    String artist = value(cursor, artistColumn, "Intérprete desconocido");
                    String album = value(cursor, albumColumn, "");
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    library.add(new LibraryTrack(uri.toString(), title, artist, album));
                }
            }
        } catch (Exception error) {
            Toast.makeText(this, "No se pudo leer la biblioteca", Toast.LENGTH_LONG).show();
        }
        filterLibrary("");
        libraryCount.setText(library.size() + " canciones encontradas");
    }

    private String value(Cursor cursor, int column, String fallback) {
        String value = cursor.getString(column);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void filterLibrary(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        visibleLibrary = new ArrayList<>();
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

    private void showLibraryTrackActions(LibraryTrack track) {
        new AlertDialog.Builder(this)
                .setTitle(track.title)
                .setItems(new String[]{"Agregar y reproducir", "Agregar a la lista", "Reproducir siguiente"},
                        (dialog, which) -> {
                            int index = addTrack(track.uri, track.title);
                            saveTracks();
                            refreshTrackList();
                            if (which == 0) sendPlayerCommand(PlaybackService.ACTION_PLAY_INDEX, index);
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
        if (position < 0 || position >= trackUris.size()) return;
        Intent intent = basePlayerIntent(PlaybackService.ACTION_PLAY_NEXT);
        intent.putExtra(PlaybackService.EXTRA_NEXT_URI, trackUris.get(position));
        intent.putExtra(PlaybackService.EXTRA_NEXT_NAME, trackNames.get(position));
        startPlaybackService(intent);
        Toast.makeText(this, "Se reproducirá a continuación", Toast.LENGTH_SHORT).show();
    }

    private int addTrack(String uri, String name) {
        int existing = trackUris.indexOf(uri);
        if (existing >= 0) return existing;
        trackUris.add(uri);
        trackNames.add(name);
        return trackUris.size() - 1;
    }

    private void markMissingTrack(String uri) {
        int index = trackUris.indexOf(uri);
        if (index >= 0 && !trackNames.get(index).startsWith("⚠ ")) {
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

    private void showImportOptions() {
        new AlertDialog.Builder(this)
                .setTitle("Importar lista")
                .setItems(new String[]{"Archivo M3U, M3U8 o PLS", "Listas públicas de Android"},
                        (dialog, which) -> {
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
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

    private void importSystemPlaylists() {
        if (!hasAudioPermission()) {
            requestAudioPermission();
            return;
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
                .setItems(names.toArray(new String[0]), (dialog, which) ->
                        importSystemPlaylist(ids.get(which), names.get(which)))
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
                    Uri mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(audioId));
                    addUri(mediaUri, cursor.getString(title));
                }
            }
        } catch (Exception ignored) { }
        savePlaylists();
        saveTracks();
        refreshPlaylistAdapter();
        playlistSpinner.setSelection(playlists.size() - 1);
        refreshTrackList();
    }

    private void addUri(Uri uri, String preferredName) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
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

    private void sendPlayerCommand(String action, int index) {
        if (trackUris.isEmpty()) {
            Toast.makeText(this, "Primero agrega música a la lista", Toast.LENGTH_SHORT).show();
            return;
        }
        SoundProfile profile = resolvedProfile();
        boolean expectHeadphones = "headphones".equals(profile.type);
        if (expectHeadphones && !hasHeadphoneOutput()) {
            Toast.makeText(this, "Conecta los auriculares antes de reproducir", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = basePlayerIntent(action);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_URIS, new ArrayList<>(trackUris));
        intent.putStringArrayListExtra(PlaybackService.EXTRA_NAMES, new ArrayList<>(trackNames));
        intent.putExtra(PlaybackService.EXTRA_INDEX, index);
        startPlaybackService(intent);
    }

    private Intent basePlayerIntent(String action) {
        SoundProfile profile = resolvedProfile();
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        intent.putExtra(PlaybackService.EXTRA_ATTENUATION_DB, volumeBar.getProgress() - 60);
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
        if (volumeBar == null || profiles.isEmpty()) return;
        startPlaybackService(basePlayerIntent(PlaybackService.ACTION_SETTINGS));
    }

    private void startPlaybackService(Intent intent) {
        try { startService(intent); }
        catch (Exception error) {
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
        if (playlistTrackAdapter != null) playlistTrackAdapter.notifyDataSetChanged();
        if (trackCount != null) trackCount.setText(trackUris.size() + (trackUris.size() == 1 ? " pista" : " pistas"));
    }

    private void loadProfiles() {
        profiles.clear();
        profiles.add(new SoundProfile("auto", "Automático · Perfil estándar", "auto",
                "Selecciona automáticamente entre el perfil estándar de auriculares y el de parlantes.",
                new float[]{0, 0, 0, 0, 0}, 0f, true));
        profiles.add(new SoundProfile("speaker_default",
                "Parlantes del teléfono · " + Build.MANUFACTURER + " " + Build.MODEL,
                "speaker", "Perfil estándar conservador para parlantes móviles.",
                new float[]{-3.5f, -1.5f, 0f, 0.5f, -1.5f}, -0.5f, true));
        profiles.add(new SoundProfile("headphones_default", "Auriculares estándar", "headphones",
                "Perfil neutro estándar para auriculares sin medición disponible.",
                new float[]{0f, 0f, 0f, -0.5f, -1f}, 0f, true));
        profiles.add(new SoundProfile("sunvito_s20", "Sunvito S20", "headphones",
                "Perfil estimado y suave para Sunvito S20.",
                new float[]{-1f, -1.5f, 0f, -1.8f, -2.5f}, 0f, true));

        String raw = prefs.getString("sound_profiles", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONArray gainsArray = item.getJSONArray("gains");
                float[] gains = new float[5];
                for (int j = 0; j < 5; j++) gains[j] = (float) gainsArray.optDouble(j, 0);
                profiles.add(new SoundProfile(item.getString("id"), item.getString("name"),
                        "headphones", item.optString("description", "Perfil encontrado"),
                        gains, (float) item.optDouble("preamp", 0), false));
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) scanLibrary();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 78);
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

    private void enforceNightLimit() {
        if (!nightMode || lockNightMaximum == null || !lockNightMaximum.isChecked()) return;
        int db = volumeBar.getProgress() - 60;
        if (db > LOCKED_NIGHT_MAX_DB) volumeBar.setProgress(LOCKED_NIGHT_MAX_DB + 60);
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

    private String safeFileName(String value) {
        return (value == null ? "Lista" : value).replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String shortProfileName(String value) {
        if (value == null) return "Estándar";
        return value.length() > 18 ? value.substring(0, 18) : value;
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
        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(MainActivity.this);
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(frame);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            View page = pages.get(position);
            if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
            holder.frame.removeAllViews();
            holder.frame.addView(page, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        @Override public int getItemCount() { return pages.size(); }

        final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout frame;
            Holder(FrameLayout frame) {
                super(frame);
                this.frame = frame;
            }
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

    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
}
