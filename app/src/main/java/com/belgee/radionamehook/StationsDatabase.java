package com.belgee.radionamehook;

import de.robv.android.xposed.XposedBridge;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * База станций на SQLite. Читает radio.db из /sdcard/RadioNames/.
 * При первом запуске база копируется из assets приложением.
 * 
 * Для Xposed-модуля: работает через прямое чтение файла БД.
 * SQLite не используется напрямую (нет android.database в Xposed),
 * вместо этого используем кэш в HashMap, перезагружаемый из JSON-экспорта.
 * 
 * Архитектура:
 * - MainActivity (UI процесс) работает с SQLite через android.database.sqlite
 * - StationsDatabase (Xposed процесс) читает /sdcard/RadioNames/current_stations.json
 *   который генерируется MainActivity при смене города
 */
public class StationsDatabase {
    private static final String TAG = "RadioNameHook";
    private static final String DIR_PATH = "/sdcard/RadioNames";
    private static final String CACHE_PATH = DIR_PATH + "/current_stations.json";
    private static final String STYLE_PATH = DIR_PATH + "/style.txt";
    private static final String FREQ_PATH = DIR_PATH + "/current_freq.txt";

    private static Map<String, String> fmStations = new HashMap<>();
    private static Map<String, String> amStations = new HashMap<>();
    private static long lastLoadTime = 0;
    private static final long RELOAD_INTERVAL_MS = 30000;

    private static int displayStyle = 0;
    private static long lastStyleLoad = 0;

    public static void init() {
        loadFromCache();
        loadStyle();
    }

    private static void loadStyle() {
        try {
            File f = new File(STYLE_PATH);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null) displayStyle = Integer.parseInt(line.trim());
            }
            lastStyleLoad = System.currentTimeMillis();
        } catch (Throwable t) { }
    }

    public static String formatDisplay(String freq, String name) {
        if (System.currentTimeMillis() - lastStyleLoad > RELOAD_INTERVAL_MS) loadStyle();
        if (name == null || name.isEmpty()) return null;
        switch (displayStyle) {
            case 0: return name;
            case 1: return freq + " " + name;
            case 2: return name + " " + freq;
            case 3: return freq + "|" + name;
            case 4: return name + "|" + freq;
            default: return name;
        }
    }

    /** Загрузка из JSON-кэша (генерируется MainActivity) */
    private static long lastFileModified = 0;
    private static synchronized void loadFromCache() {
        try {
            File file = new File(CACHE_PATH);
            if (!file.exists()) {
                file = new File(DIR_PATH + "/stations.json");
                if (!file.exists()) return;
            }
            // Пропускаем если файл не изменился
            long modified = file.lastModified();
            if (modified == lastFileModified && !fmStations.isEmpty()) {
                lastLoadTime = System.currentTimeMillis();
                return;
            }
            lastFileModified = modified;
            
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            org.json.JSONObject root = new org.json.JSONObject(sb.toString());
            fmStations.clear();
            amStations.clear();
            if (root.has("fm")) {
                org.json.JSONObject fm = root.getJSONObject("fm");
                java.util.Iterator<String> keys = fm.keys();
                while (keys.hasNext()) { String k = keys.next(); fmStations.put(k, fm.getString(k)); }
            }
            if (root.has("am")) {
                org.json.JSONObject am = root.getJSONObject("am");
                java.util.Iterator<String> keys = am.keys();
                while (keys.hasNext()) { String k = keys.next(); amStations.put(k, am.getString(k)); }
            }
            lastLoadTime = System.currentTimeMillis();
            XposedBridge.log(TAG + ": loaded " + fmStations.size() + " FM + " + amStations.size() + " AM from cache");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cache load error: " + t.getMessage());
        }
    }

    public static String findName(String freq) {
        if (freq == null || freq.isEmpty()) return null;
        if (System.currentTimeMillis() - lastLoadTime > RELOAD_INTERVAL_MS) loadFromCache();
        String normalized = normalizeFreq(freq);
        if (normalized == null) return null;
        String result = fmStations.get(normalized);
        if (result != null) return result;
        result = amStations.get(normalized);
        return result;
    }

    public static String normalizeFreq(String freq) {
        try {
            String cleaned = freq.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return null;
            if (cleaned.contains(".")) {
                double d = Double.parseDouble(cleaned);
                return formatFm(d);
            }
            int num = Integer.parseInt(cleaned);
            if (num > 10000) return formatFm(num / 1000.0);
            return cleaned;
        } catch (Throwable t) { return null; }
    }

    private static String formatFm(double d) {
        double rounded = Math.round(d * 10.0) / 10.0;
        if (rounded == Math.floor(rounded)) return ((int) rounded) + ".0";
        return String.format(java.util.Locale.US, "%.1f", rounded);
    }

    public static String findNameReverse(String name) {
        if (name == null || name.isEmpty()) return null;
        if (System.currentTimeMillis() - lastLoadTime > RELOAD_INTERVAL_MS) loadFromCache();
        for (Map.Entry<String, String> e : fmStations.entrySet())
            if (name.equals(e.getValue())) return e.getKey();
        for (Map.Entry<String, String> e : amStations.entrySet())
            if (name.equals(e.getValue())) return e.getKey();
        return null;
    }

    /** Записать текущую частоту (throttle: макс раз в 2 сек) */
    private static long lastFreqWriteTime = 0;
    private static String lastFreqWritten = "";
    public static void writeCurrentFreq(String freq) {
        if (freq == null || freq.equals(lastFreqWritten)) return;
        long now = System.currentTimeMillis();
        if (now - lastFreqWriteTime < 2000) return;
        lastFreqWriteTime = now;
        lastFreqWritten = freq;
        try {
            File dir = new File(DIR_PATH);
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(FREQ_PATH)) { w.write(freq); }
        } catch (Throwable t) { }
    }

    /** Обновить станцию и сохранить в JSON-кэш */
    public static void updateStation(String freq, String name) {
        if (freq == null || name == null) return;
        String normalized = normalizeFreq(freq);
        if (normalized == null) return;
        // Обновляем в памяти
        if (normalized.contains(".")) {
            fmStations.put(normalized, name);
        } else {
            amStations.put(normalized, name);
        }
        // Сохраняем JSON-кэш
        saveCache();
    }

    private static void saveCache() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONObject fm = new org.json.JSONObject();
            org.json.JSONObject am = new org.json.JSONObject();
            for (Map.Entry<String, String> e : fmStations.entrySet()) fm.put(e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : amStations.entrySet()) am.put(e.getKey(), e.getValue());
            root.put("fm", fm); root.put("am", am);
            File dir = new File(DIR_PATH);
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(CACHE_PATH)) { w.write(root.toString(2)); }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": saveCache error: " + t.getMessage());
        }
    }
}
