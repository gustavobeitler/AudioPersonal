using System;
using System.Collections;
using System.IO;
using System.Reflection;

static class Program
{
    static MethodInfo extract;

    static string Extract(string phrase, ref DateTime awake)
    {
        object[] arguments = { phrase, awake };
        string result = (string)extract.Invoke(null, arguments);
        awake = (DateTime)arguments[1];
        return result;
    }

    static void Expect(string phrase, string expected)
    {
        DateTime awake = DateTime.MinValue;
        string actual = Extract(phrase, ref awake);
        if (actual != expected) throw new Exception(phrase + " => " + actual + "; esperado: " + expected);
    }

    static void AssertClose(float actual, float expected, string message)
    {
        if (Math.Abs(actual - expected) > 0.0001f) throw new Exception(message);
    }

    static int Main(string[] args)
    {
        Assembly application = Assembly.LoadFrom(args[0]);
        Type request = application.GetType("AudioPersonal.MusicRequest", true);
        extract = request.GetMethod("Extract", BindingFlags.Public | BindingFlags.Static);

        Expect("computadora jose luis perales cancion de otono", "jose luis perales cancion de otono");
        Expect("computadora cancion de otono jose luis perales", "otono jose luis perales");
        Expect("computadora quiero escuchar cancion de otono de jose luis perales", "otono de jose luis perales");
        Expect("computadora pausa", null);

        DateTime awake = DateTime.MinValue;
        if (Extract("computadora", ref awake) != null || awake <= DateTime.Now) throw new Exception("No se abrió la ventana de escucha.");
        if (Extract("jose luis perales cancion de otono", ref awake) != "jose luis perales cancion de otono") throw new Exception("Falló el pedido en dos frases.");

        Type trackType = application.GetType("AudioPersonal.TrackInfo", true);
        object track = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Jose Luis Perales - Cancion de otono.mp3", "Jose Luis Perales", "Cancion de otono" }, null);
        Type catalogType = application.GetType("AudioPersonal.MusicCatalog", true);
        object catalog = Activator.CreateInstance(catalogType, true);
        IList tracks = (IList)catalogType.GetField("tracks", BindingFlags.Instance | BindingFlags.NonPublic).GetValue(catalog);
        tracks.Add(track);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(catalog, null);
        MethodInfo findBest = catalogType.GetMethod("FindBest", BindingFlags.Instance | BindingFlags.Public);
        object reverseMatch = findBest.Invoke(catalog, new object[] { "cancion de otono jose luis perales", "" });
        object forwardMatch = findBest.Invoke(catalog, new object[] { "jose luis perales cancion de otono", "" });
        if (reverseMatch == null || forwardMatch == null) throw new Exception("La búsqueda depende del orden de artista y título.");
        double reverseScore = (double)reverseMatch.GetType().GetField("Score").GetValue(reverseMatch);
        double forwardScore = (double)forwardMatch.GetType().GetField("Score").GetValue(forwardMatch);
        if (reverseScore < 0.99 || forwardScore < 0.99) throw new Exception("Puntaje insuficiente: " + reverseScore + " / " + forwardScore);

        Type textTools = application.GetType("AudioPersonal.TextTools", true);
        MethodInfo expandAliases = textTools.GetMethod("ExpandAliases", BindingFlags.Public | BindingFlags.Static);
        string unusual = (string)expandAliases.Invoke(null, new object[] { "los tontos que no tocida" });
        if (!unusual.Contains("gerontocida")) throw new Exception("No se corrigió la aproximación fonética de Gerontocida: " + unusual);
        string artistOnly = (string)expandAliases.Invoke(null, new object[] { "los tontos" });
        if (artistOnly.Contains("gerontocida")) throw new Exception("Pedir solo Los Tontos dejó de ser aleatorio.");

        object unusualTrack = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Los Tontos - El Gerontocida.mp3", "Los Tontos", "El Gerontocida" }, null);
        tracks.Add(unusualTrack);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(catalog, null);
        object unusualMatch = findBest.Invoke(catalog, new object[] { "los tontos que no tocida", "" });
        if (unusualMatch == null) throw new Exception("No hubo coincidencia para la variante fonética.");
        object matchedTrack = unusualMatch.GetType().GetField("Track").GetValue(unusualMatch);
        string matchedTitle = (string)trackType.GetField("Title").GetValue(matchedTrack);
        if (matchedTitle != "El Gerontocida") throw new Exception("Se eligió otro título: " + matchedTitle);

        object otherTontos = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Los Tontos - Ana la del quinto.mp3", "Los Tontos", "Ana la del quinto" }, null);
        object owner = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Yes - Owner of a Lonely Heart.mp3", "Yes", "Owner of a Lonely Heart" }, null);
        tracks.Add(otherTontos); tracks.Add(owner);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(catalog, null);
        object artistFallback = findBest.Invoke(catalog, new object[] { "los tontos titulo completamente incomprensible", "john lennon" });
        if (artistFallback != null) throw new Exception("Un título incomprensible produjo una canción al azar.");
        object artistRandom = findBest.Invoke(catalog, new object[] { "los tontos", "" });
        if (artistRandom == null) throw new Exception("Pedir solamente el artista dejó de funcionar.");
        object randomTrack = artistRandom.GetType().GetField("Track").GetValue(artistRandom);
        if ((string)trackType.GetField("Artist").GetValue(randomTrack) != "Los Tontos") throw new Exception("La selección aleatoria salió del artista.");

        object unsafeForeign = findBest.Invoke(catalog, new object[] { "voyage voyage", "owner of a lonely heart" });
        if (unsafeForeign != null) throw new Exception("Un resultado inglés discordante impuso una canción ajena.");
        object voyage = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Desireless - Voyage Voyage.mp3", "Desireless", "Voyage Voyage" }, null);
        tracks.Add(voyage);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(catalog, null);
        object voyageMatch = findBest.Invoke(catalog, new object[] { "voyage voyage", "owner of a lonely heart" });
        if (voyageMatch == null) throw new Exception("No se encontró un título exacto en español/francés.");
        object voyageTrack = voyageMatch.GetType().GetField("Track").GetValue(voyageMatch);
        if ((string)trackType.GetField("Title").GetValue(voyageTrack) != "Voyage Voyage") throw new Exception("Voyage Voyage eligió otro tema.");
        object yesterday = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\The Beatles - Yesterday.mp3", "The Beatles", "Yesterday" }, null);
        tracks.Add(yesterday);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(catalog, null);
        object yesterdayMatch = findBest.Invoke(catalog, new object[] { "yesterday", "yesterday" });
        if (yesterdayMatch == null) throw new Exception("No se encontró Yesterday.");
        object yesterdayTrack = yesterdayMatch.GetType().GetField("Track").GetValue(yesterdayMatch);
        if ((string)trackType.GetField("Title").GetValue(yesterdayTrack) != "Yesterday") throw new Exception("El artista corto Yes se activó dentro de Yesterday.");

        object mana = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Mana - Pobre Juan.mp3", "Mana", "Pobre Juan" }, null);
        object manana = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Juan Gabriel - Manana.mp3", "Juan Gabriel", "Manana" }, null);
        object nosVemosManana = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Juan Gabriel - Nos vemos manana.mp3", "Juan Gabriel", "Nos vemos manana" }, null);
        object mananaManana = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Juan Gabriel - Manana manana.mp3", "Juan Gabriel", "Manana manana" }, null);
        object dream = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\The Dream - I Love You Girl.mp3", "The Dream", "I Love You Girl" }, null);
        object lonelyNights = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Paul McCartney - No More Lonely Nights.mp3", "Paul McCartney", "No More Lonely Nights" }, null);
        tracks.Add(mana); tracks.Add(manana); tracks.Add(nosVemosManana); tracks.Add(mananaManana); tracks.Add(dream); tracks.Add(lonelyNights);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(catalog, null);
        object mananaMatch = findBest.Invoke(catalog, new object[] { "juan gabriel manana", "" });
        object mananaTrack = mananaMatch == null ? null : mananaMatch.GetType().GetField("Track").GetValue(mananaMatch);
        if (mananaTrack == null || (string)trackType.GetField("Artist").GetValue(mananaTrack) != "Juan Gabriel") throw new Exception("Maná volvió a activarse dentro de Mañana.");
        if ((string)trackType.GetField("Title").GetValue(mananaTrack) != "Manana") throw new Exception("Mañana no tuvo prioridad sobre títulos que solo contienen la palabra.");
        object compoundCatalog = Activator.CreateInstance(catalogType, true);
        IList compoundTracks = (IList)catalogType.GetField("tracks", BindingFlags.Instance | BindingFlags.NonPublic).GetValue(compoundCatalog);
        object compoundManana = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Juan Gabriel y Cristian Castro - Manana.mp3", "Juan Gabriel y Cristian Castro", "Manana" }, null);
        object porLasMananas = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Juan Gabriel - Por las mananas.mp3", "Juan Gabriel", "Por las mananas" }, null);
        compoundTracks.Add(compoundManana); compoundTracks.Add(porLasMananas);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(compoundCatalog, null);
        object compoundMatch = findBest.Invoke(compoundCatalog, new object[] { "juan gabriel manana", "" });
        object compoundTrack = compoundMatch == null ? null : compoundMatch.GetType().GetField("Track").GetValue(compoundMatch);
        if (compoundTrack == null || (string)trackType.GetField("Artist").GetValue(compoundTrack) != "Juan Gabriel y Cristian Castro" ||
            (string)trackType.GetField("Title").GetValue(compoundTrack) != "Manana") throw new Exception("No se priorizó Mañana del archivo con artista compuesto.");
        object lonelyMatch = findBest.Invoke(catalog, new object[] { "paul mccartney no more lonely nights", "" });
        object lonelyTrack = lonelyMatch == null ? null : lonelyMatch.GetType().GetField("Track").GetValue(lonelyMatch);
        if (lonelyTrack == null || (string)trackType.GetField("Artist").GetValue(lonelyTrack) != "Paul McCartney") throw new Exception("No se respetó Paul McCartney como artista exacto.");

        object hamilton = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Hamilton Bohannon - Bohannons Beat.mp3", "Hamilton Bohannon", "Bohannons Beat" }, null);
        object andando = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\Diego Torres - Andando.mp3", "Diego Torres", "Andando" }, null);
        object rosas = Activator.CreateInstance(trackType, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null,
            new object[] { @"G:\MP3\La Oreja de Van Gogh - Rosas.mp3", "La Oreja de Van Gogh", "Rosas" }, null);
        object hamiltonCatalog = Activator.CreateInstance(catalogType, true);
        IList hamiltonTracks = (IList)catalogType.GetField("tracks", BindingFlags.Instance | BindingFlags.NonPublic).GetValue(hamiltonCatalog);
        hamiltonTracks.Add(hamilton); hamiltonTracks.Add(andando); hamiltonTracks.Add(rosas);
        catalogType.GetMethod("BuildSearchIndex", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(hamiltonCatalog, null);
        string[] hamiltonQueries = { "hamilton bohanon", "bohanon beats", "boja non bis", "hamilton boja non bis" };
        foreach (string query in hamiltonQueries)
        {
            object hamiltonMatch = findBest.Invoke(hamiltonCatalog, new object[] { query, "" });
            object hamiltonTrack = hamiltonMatch == null ? null : hamiltonMatch.GetType().GetField("Track").GetValue(hamiltonMatch);
            if (hamiltonTrack == null || (string)trackType.GetField("Artist").GetValue(hamiltonTrack) != "Hamilton Bohannon" ||
                (string)trackType.GetField("Title").GetValue(hamiltonTrack) != "Bohannons Beat")
                throw new Exception("La variante '" + query + "' no encontró Hamilton Bohannon — Bohannons Beat.");
        }
        object weakHamilton = findBest.Invoke(hamiltonCatalog, new object[] { "bohanon titulo inexistente", "" });
        if (weakHamilton != null) throw new Exception("Una búsqueda débil de Bohanon reprodujo una canción sin relación.");

        Type engineType = application.GetType("AudioPersonal.InternalPlayerEngine", true);
        object playerEngine = Activator.CreateInstance(engineType, true);
        string playlistProbe = Path.Combine(Path.GetTempPath(), "AudioPersonal-playlist-probe.m3u8");
        try
        {
            File.WriteAllText(playlistProbe, "#EXTM3U\n");
            MethodInfo appendTrack = engineType.GetMethod("AppendTrackToPlaylist", BindingFlags.Instance | BindingFlags.Public);
            bool appended = (bool)appendTrack.Invoke(playerEngine, new object[] { playlistProbe, mananaTrack });
            if (!appended || !File.ReadAllText(playlistProbe).Contains((string)trackType.GetField("Path").GetValue(mananaTrack))) throw new Exception("No se pudo agregar la canción a una playlist existente.");
        }
        finally { try { if (File.Exists(playlistProbe)) File.Delete(playlistProbe); } catch { } }
        Type commandType = application.GetType("AudioPersonal.PlayerCommandProcessor", true);
        MethodInfo executePlayerCommand = commandType.GetMethod("TryExecute", BindingFlags.Public | BindingFlags.Static);
        bool opened = false, closed = false;
        object[] openArguments = { "computadora iniciar reproductor", DateTime.MinValue, playerEngine, new Action(() => opened = true), new Action(() => closed = true) };
        string openResult = (string)executePlayerCommand.Invoke(null, openArguments);
        if (!opened || closed || openResult != "Reproductor abierto.") throw new Exception("Falló la orden de abrir reproductor.");
        object[] closeArguments = { "computadora apagar reproductor", DateTime.MinValue, playerEngine, new Action(() => opened = true), new Action(() => closed = true) };
        string closeResult = (string)executePlayerCommand.Invoke(null, closeArguments);
        if (!closed || closeResult != "Reproductor apagado.") throw new Exception("Falló la orden de apagar reproductor.");
        object[] nextArguments = { "computadora cambiar de cancion", DateTime.MinValue, playerEngine, new Action(() => { }), new Action(() => { }) };
        string nextResult = (string)executePlayerCommand.Invoke(null, nextArguments);
        if (nextResult != "Reproduciendo la canción siguiente.") throw new Exception("Falló cambiar de canción.");

        Type volumeCommandType = application.GetType("AudioPersonal.CommandProcessor", true);
        MethodInfo executeVolume = volumeCommandType.GetMethod("TryExecute", BindingFlags.Public | BindingFlags.Static);
        int exactVolume = -1;
        int relativeVolume = 0;
        object[] volumeArguments = { "computadora bajar el volumen a un 20 por ciento", DateTime.MinValue, new Action<int>(value => exactVolume = value), new Action<int>(value => relativeVolume = value) };
        string volumeResult = (string)executeVolume.Invoke(null, volumeArguments);
        if (exactVolume != 20 || volumeResult != "Volumen establecido en 20%.") throw new Exception("El volumen absoluto no quedó en 20%.");
        exactVolume = -1; relativeVolume = 0;
        object[] bareExactArguments = { "computadora volumen al 25 por ciento", DateTime.MinValue, new Action<int>(value => exactVolume = value), new Action<int>(value => relativeVolume = value) };
        string bareExactResult = (string)executeVolume.Invoke(null, bareExactArguments);
        if (exactVolume != 25 || bareExactResult != "Volumen establecido en 25%.") throw new Exception("'Volumen al 25%' no se interpretó como valor absoluto.");
        exactVolume = -1; relativeVolume = 0;
        object[] relativeArguments = { "computadora bajar volumen", DateTime.MinValue, new Action<int>(value => exactVolume = value), new Action<int>(value => relativeVolume = value) };
        string relativeResult = (string)executeVolume.Invoke(null, relativeArguments);
        if (exactVolume != -1 || relativeVolume != -10 || relativeResult != "Orden ejecutada: volumen -10%.") throw new Exception("'Bajar volumen' no aplicó -10% sobre el volumen previo.");
        exactVolume = -1; relativeVolume = 0;
        object[] falseVolumeArguments = { "computadora hamilton bohanon", DateTime.MinValue, new Action<int>(value => exactVolume = value), new Action<int>(value => relativeVolume = value) };
        string falseVolumeResult = (string)executeVolume.Invoke(null, falseVolumeArguments);
        if (falseVolumeResult != null || exactVolume != -1 || relativeVolume != 0) throw new Exception("Hamilton Bohannon volvió a activar una orden de volumen.");

        Type audioDeviceType = application.GetType("AudioPersonal.AudioDevice", true);
        MethodInfo calculateBalance = audioDeviceType.GetMethod("CalculateBalanceLevels", BindingFlags.NonPublic | BindingFlags.Static);
        object[] centeredBalance = { 0.30f, 0, 0f, 0f };
        calculateBalance.Invoke(null, centeredBalance);
        AssertClose((float)centeredBalance[2], 0.30f, "El balance centrado alteró el canal izquierdo.");
        AssertClose((float)centeredBalance[3], 0.30f, "El balance centrado alteró el canal derecho.");
        object[] rightBalance = { 0.30f, 50, 0f, 0f };
        calculateBalance.Invoke(null, rightBalance);
        AssertClose((float)rightBalance[2], 0.15f, "El balance derecho no atenuó el canal izquierdo.");
        AssertClose((float)rightBalance[3], 0.30f, "El balance derecho elevó el volumen maestro.");
        ((IDisposable)playerEngine).Dispose();

        Console.WriteLine("VOICE_LOGIC_OK");
        return 0;
    }
}
