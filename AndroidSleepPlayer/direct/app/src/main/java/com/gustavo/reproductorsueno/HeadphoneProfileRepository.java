package com.gustavo.reproductorsueno;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HeadphoneProfileRepository {
    private static final String INDEX_URL =
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md";
    private static final long CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int[] TARGET_BANDS = new int[]{60, 230, 910, 3600, 14000};

    public interface Callback {
        void onFound(Result result);
        void onNotFound();
        void onError(String message);
    }

    public static final class Result {
        public final String displayName;
        public final String source;
        public final float[] gainsDb;
        public final float preampDb;

        Result(String displayName, String source, float[] gainsDb, float preampDb) {
            this.displayName = displayName;
            this.source = source;
            this.gainsDb = gainsDb;
            this.preampDb = preampDb;
        }
    }

    private HeadphoneProfileRepository() { }

    public static void search(Context context, String brand, String model, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String query = normalize((brand + " " + model).trim());
                if (query.length() < 3) {
                    main.post(callback::onNotFound);
                    return;
                }

                String index = loadIndex(context);
                Match match = findBestMatch(index, brand, model);
                if (match == null) {
                    main.post(callback::onNotFound);
                    return;
                }

                String directory = match.link;
                if (directory.startsWith("./")) directory = directory.substring(2);
                if (directory.startsWith("results/")) directory = directory.substring("results/".length());
                int slash = directory.lastIndexOf('/');
                if (slash >= 0) directory = directory.substring(0, slash + 1);
                else directory = "";

                String fileName = match.name + " GraphicEQ.txt";
                String rawUrl = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
                        + encodePath(directory + fileName);

                String graphic;
                try {
                    graphic = downloadText(rawUrl);
                } catch (Exception first) {
                    String readmeUrl = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
                            + encodePath(directory + "README.md");
                    graphic = downloadText(readmeUrl);
                }

                ArrayList<Point> points = parseGraphicEq(graphic);
                if (points.size() < 3) {
                    main.post(callback::onNotFound);
                    return;
                }

                float[] gains = new float[TARGET_BANDS.length];
                float maxBoost = 0f;
                for (int i = 0; i < TARGET_BANDS.length; i++) {
                    float value = interpolate(points, TARGET_BANDS[i]);
                    value = Math.max(-8f, Math.min(6f, value));
                    gains[i] = value;
                    if (value > maxBoost) maxBoost = value;
                }
                float preamp = -Math.max(0f, maxBoost);
                Result result = new Result(match.name, "AutoEq", gains, preamp);
                main.post(() -> callback.onFound(result));
            } catch (Exception error) {
                String message = error.getMessage() == null ? "No se pudo consultar la base de perfiles"
                        : error.getMessage();
                main.post(() -> callback.onError(message));
            }
        }, "profile-search").start();
    }

    private static String loadIndex(Context context) throws Exception {
        File cache = new File(context.getCacheDir(), "autoeq_index.md");
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS) {
            try (FileInputStream input = new FileInputStream(cache)) {
                return readAll(input);
            }
        }
        String text = downloadText(INDEX_URL);
        try (FileOutputStream output = new FileOutputStream(cache)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        return text;
    }

    private static Match findBestMatch(String index, String brand, String model) {
        String normalizedBrand = normalize(brand);
        String normalizedModel = normalize(model);
        String[] required = normalize((brand + " " + model).trim()).split("\\s+");
        Pattern linkPattern = Pattern.compile("\\[([^\\]]+)]\\(([^)]+README\\.md)\\)");
        Match best = null;
        int bestScore = Integer.MIN_VALUE;

        for (String line : index.split("\\r?\\n")) {
            String normalizedLine = normalize(line);
            boolean all = true;
            for (String token : required) {
                if (token.length() > 1 && !normalizedLine.contains(token)) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;

            Matcher matcher = linkPattern.matcher(line);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                String link = matcher.group(2).trim();
                String n = normalize(name);
                int score = 0;
                if (n.equals(normalize((brand + " " + model).trim()))) score += 1000;
                if (!normalizedBrand.isEmpty() && n.contains(normalizedBrand)) score += 100;
                if (!normalizedModel.isEmpty() && n.contains(normalizedModel)) score += 200;
                score -= Math.abs(n.length() - (normalizedBrand.length() + normalizedModel.length() + 1));
                if (score > bestScore) {
                    bestScore = score;
                    best = new Match(name, link);
                }
            }
        }
        return best;
    }

    private static ArrayList<Point> parseGraphicEq(String text) {
        ArrayList<Point> points = new ArrayList<>();
        int marker = text.indexOf("GraphicEQ:");
        if (marker < 0) return points;
        String payload = text.substring(marker + "GraphicEQ:".length());
        int newline = payload.indexOf('\n');
        if (newline >= 0) payload = payload.substring(0, newline);
        for (String pair : payload.split(";")) {
            String[] parts = pair.trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                points.add(new Point(Float.parseFloat(parts[0]), Float.parseFloat(parts[1])));
            } catch (NumberFormatException ignored) { }
        }
        return points;
    }

    private static float interpolate(ArrayList<Point> points, float frequency) {
        if (points.isEmpty()) return 0f;
        if (frequency <= points.get(0).frequency) return points.get(0).gain;
        for (int i = 1; i < points.size(); i++) {
            Point left = points.get(i - 1);
            Point right = points.get(i);
            if (frequency <= right.frequency) {
                double logF = Math.log(frequency);
                double logL = Math.log(left.frequency);
                double logR = Math.log(right.frequency);
                float t = (float) ((logF - logL) / Math.max(0.0001, logR - logL));
                return left.gain + (right.gain - left.gain) * t;
            }
        }
        return points.get(points.size() - 1).gain;
    }

    private static String downloadText(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "RadioEnlaceAudio/0.3");
        connection.setInstanceFollowRedirects(true);
        int response = connection.getResponseCode();
        if (response < 200 || response >= 300) {
            connection.disconnect();
            throw new Exception("Respuesta web " + response);
        }
        try (InputStream input = connection.getInputStream()) {
            return readAll(input);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        StringBuilder builder = new StringBuilder();
        for (String segment : value.split("/", -1)) {
            if (builder.length() > 0) builder.append('/');
            builder.append(java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return text.replaceAll("\\s+", " ");
    }

    private static final class Match {
        final String name;
        final String link;
        Match(String name, String link) {
            this.name = name;
            this.link = link;
        }
    }

    private static final class Point {
        final float frequency;
        final float gain;
        Point(float frequency, float gain) {
            this.frequency = frequency;
            this.gain = gain;
        }
    }
}
