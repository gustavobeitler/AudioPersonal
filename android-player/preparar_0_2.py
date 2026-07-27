from pathlib import Path

root = Path("ReproductorClasico_0.2")


def replace_required(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"No se encontró el bloque esperado en {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


main = root / "app/src/main/java/com/gustavobeitler/reproductorclasico/MainActivity.java"
replace_required(main, "import androidx.media3.common.MediaItem;\n", "import androidx.media3.common.MediaItem;\nimport androidx.media3.common.PlaybackException;\n")
replace_required(main, "public final class MainActivity extends AppCompatActivity {\n", "public final class MainActivity extends AppCompatActivity {\n    private static final int MAX_QUEUE_ITEMS = 201;\n")
replace_required(
    main,
    """        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateMiniPlayer();
        }
""",
    """        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateMiniPlayer();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Toast.makeText(MainActivity.this,
                    \"No se pudo reproducir este archivo\", Toast.LENGTH_LONG).show();
            updateMiniPlayer();
        }
""",
)
replace_required(main, '"1 canción en el teléfono · Versión 0.1"', '"1 canción en el teléfono · Versión 0.2"')
replace_required(main, 'localCount + " canciones en el teléfono · Versión 0.1"', 'localCount + " canciones en el teléfono · Versión 0.2"')
replace_required(
    main,
    """    private void playSong(Song selected) {
        if (controller == null) {
            Toast.makeText(this, \"El reproductor todavía se está iniciando\", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Song> queueSongs = songAdapter.getVisibleSongs();
        if (queueSongs.isEmpty()) queueSongs = new ArrayList<>(songs);
        List<MediaItem> mediaItems = new ArrayList<>();
        int selectedIndex = 0;
        for (int index = 0; index < queueSongs.size(); index++) {
            Song song = queueSongs.get(index);
            if (song.uri.equals(selected.uri)) selectedIndex = index;
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.folder)
                    .build();
            mediaItems.add(new MediaItem.Builder()
                    .setMediaId(song.uri.toString())
                    .setUri(song.uri)
                    .setMediaMetadata(metadata)
                    .build());
        }
        controller.setMediaItems(mediaItems, selectedIndex, 0L);
        controller.prepare();
        controller.play();
        updateMiniPlayer();
    }
""",
    """    private void playSong(Song selected) {
        if (controller == null) {
            Toast.makeText(this, \"El reproductor todavía se está iniciando\", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Song> sourceQueue = songAdapter.getVisibleSongs();
        if (sourceQueue.isEmpty()) sourceQueue = new ArrayList<>(songs);

        int absoluteSelectedIndex = 0;
        for (int index = 0; index < sourceQueue.size(); index++) {
            if (sourceQueue.get(index).uri.equals(selected.uri)) {
                absoluteSelectedIndex = index;
                break;
            }
        }

        // Android no permite enviar una biblioteca de miles de canciones completa
        // mediante Binder. Se usa una ventana alrededor de la selección para que
        // las bibliotecas grandes permanezcan rápidas y estables.
        int halfWindow = MAX_QUEUE_ITEMS / 2;
        int start = Math.max(0, absoluteSelectedIndex - halfWindow);
        int end = Math.min(sourceQueue.size(), start + MAX_QUEUE_ITEMS);
        start = Math.max(0, end - MAX_QUEUE_ITEMS);
        int selectedIndex = absoluteSelectedIndex - start;

        List<MediaItem> mediaItems = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            Song song = sourceQueue.get(index);
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.folder)
                    .build();
            mediaItems.add(new MediaItem.Builder()
                    .setMediaId(song.uri.toString())
                    .setUri(song.uri)
                    .setMediaMetadata(metadata)
                    .build());
        }

        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(selected.title);
        miniArtist.setText(selected.artist);
        miniPlayPause.setImageResource(R.drawable.ic_pause);

        controller.setMediaItems(mediaItems, selectedIndex, 0L);
        controller.prepare();
        controller.play();
        startActivity(new Intent(this, PlayerActivity.class));
    }
""",
)

player = root / "app/src/main/java/com/gustavobeitler/reproductorclasico/PlayerActivity.java"
replace_required(player, "import androidx.media3.common.MediaMetadata;\n", "import androidx.media3.common.MediaMetadata;\nimport androidx.media3.common.PlaybackException;\n")
replace_required(
    player,
    "        @Override public void onRepeatModeChanged(int repeatMode) { updateButtons(); }\n",
    """        @Override public void onRepeatModeChanged(int repeatMode) { updateButtons(); }
        @Override public void onPlayerError(@NonNull PlaybackException error) {
            Toast.makeText(PlayerActivity.this,
                    \"No se pudo reproducir este archivo\", Toast.LENGTH_LONG).show();
            updateButtons();
        }
""",
)

build_gradle = root / "app/build.gradle"
replace_required(build_gradle, "versionCode 1", "versionCode 2")
replace_required(build_gradle, "versionName '0.1'", "versionName '0.2'")

strings = root / "app/src/main/res/values/strings.xml"
replace_required(strings, '<string name="version_text">Versión 0.1</string>', '<string name="version_text">Versión 0.2</string>')

layout = root / "app/src/main/res/layout/activity_main.xml"
replace_required(layout, 'android:text="Versión 0.1"', 'android:text="Versión 0.2"')
replace_required(
    layout,
    'android:text="Versión 0.1\\n\\nPrimera compilación funcional. Incluye biblioteca local, búsqueda, carpetas, reproducción en segundo plano, controles de notificación y una pista de prueba.\\n\\nSin anuncios y sin conexión a internet."',
    'android:text="Versión 0.2\\n\\nCorrección para bibliotecas grandes. Al tocar una canción se abre la pantalla de reproducción y se inicia una cola estable sin intentar enviar las 12.000 canciones al mismo tiempo.\\n\\nIncluye reproducción en segundo plano, controles de notificación y pista de prueba. Sin anuncios y sin conexión a internet."',
)

readme = root / "README.md"
text = readme.read_text(encoding="utf-8").replace("0.1", "0.2")
text += "\n\n## Cambio principal de la 0.2\n\nLa cola utiliza una ventana de 201 canciones alrededor de la selección para funcionar con bibliotecas de más de 12.000 archivos. Al tocar una canción se abre la pantalla de reproducción.\n"
readme.write_text(text, encoding="utf-8")
