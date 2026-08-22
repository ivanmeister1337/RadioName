package com.belgee.radionamehook;

import de.robv.android.xposed.XposedBridge;
import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * База станций. Кэш в памяти читается из /sdcard/RadioNames/current_stations.json
 * (генерируется MainActivity при смене города/обновлении базы).
 *
 * ВАЖНО: при переименовании станции (updateStation) пишем ОБА места:
 *  1) JSON-кэш — для мгновенного отображения без перезапуска
 *  2) саму SQLite radio.db — иначе следующий запуск MainActivity перезапишет
 *     JSON-кэш заново ИЗ SQLite и наше переименование бесследно исчезнет.
 * android.database.sqlite доступен в любом процессе Android (это часть
 * платформы, а не наш xposed-stub), поэтому прямая запись в БД тут возможна.
 */
public class StationsDatabase {
    private static final String TAG = "RadioNameHook";
    private static final String DIR_PATH = "/sdcard/RadioNames";
    private static final String CACHE_PATH = DIR_PATH + "/current_stations.json";
    private static final String STYLE_PATH = DIR_PATH + "/style.txt";
    private static final String FONT_SCALE_PATH = DIR_PATH + "/font_scale.txt";
    private static final String FREQ_PATH = DIR_PATH + "/current_freq.txt";
    private static final String DB_PATH = DIR_PATH + "/radio.db";

    private static Map<String, String> fmStations = new ConcurrentHashMap<>();
    private static Map<String, String> amStations = new ConcurrentHashMap<>();
    private static long lastLoadTime = 0;
    private static final long RELOAD_INTERVAL_MS = 30000;

    private static int displayStyle = 0;
    private static long lastStyleLoad = 0;

    // Масштаб шрифта в виджете — настраивается прямо в приложении (несколько
    // пресетов, пользователь тыкает и сразу видит результат на виджете, без
    // пересборки APK под каждое значение).
    private static float fontScale = 0.84f; // дефолт — то, что уже неплохо смотрелось
    private static long lastFontScaleLoad = 0;

    public static void init() {
        loadFromCache();
        loadStyle();
        loadFontScale();
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

    private static void loadFontScale() {
        try {
            File f = new File(FONT_SCALE_PATH);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null) fontScale = Float.parseFloat(line.trim());
            }
            lastFontScaleLoad = System.currentTimeMillis();
        } catch (Throwable t) { }
    }

    /** Текущий масштаб шрифта виджета — перечитывается часто (не как остальные
     *  настройки раз в 30 сек), чтобы при подборе размера в приложении
     *  результат на виджете был виден почти сразу, а не с задержкой. */
    private static final long FONT_SCALE_RELOAD_MS = 2000;
    public static float getFontScale() {
        if (System.currentTimeMillis() - lastFontScaleLoad > FONT_SCALE_RELOAD_MS) loadFontScale();
        return fontScale;
    }

    public static String formatDisplay(String freq, String name) {
        if (System.currentTimeMillis() - lastStyleLoad > RELOAD_INTERVAL_MS) loadStyle();
        if (name == null || name.isEmpty()) return null;
        // Стилей теперь только 2 (0 и 1). Если на диске лежит старое значение
        // 2-4 (от прошлых версий) — трактуем как "частота + имя" по умолчанию.
        return (displayStyle == 0) ? name : (freq + " " + name);
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

    /**
     * Единая обрезка текста для отображения — используется ВЕЗДЕ где имя
     * станции попадает на экран (их оказалось 5 разных мест: RadioInfo.getName,
     * виджет launcher'а, MediaCenter API, widget holder, updateContentTitle).
     * Раньше обрезку добавляли по одному месту за раз, каждый раз забывая
     * про остальные — отсюда и жалобы "имя всё равно большое" после починки
     * только одного конкретного места.
     */
    public static String truncateForDisplay(String name, int maxLen) {
        if (name == null) return null;
        if (name.length() <= maxLen) return name;
        return name.substring(0, Math.max(1, maxLen - 1)) + "…";
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

    /** Обновить станцию: пишем в память + JSON-кэш + саму SQLite базу (источник истины). */
    public static void updateStation(String freq, String name) {
        if (freq == null || name == null) return;
        String normalized = normalizeFreq(freq);
        if (normalized == null) return;
        String band = normalized.contains(".") ? "FM" : "AM";
        if ("FM".equals(band)) {
            fmStations.put(normalized, name);
        } else {
            amStations.put(normalized, name);
        }
        saveCache();
        persistToSqlite(normalized, band, name);
    }

    /**
     * Пишет переименование напрямую в radio.db, чтобы оно пережило
     * следующий запуск MainActivity (который иначе перезапишет JSON-кэш
     * старыми данными из SQLite, стирая изменение).
     */
    private static void persistToSqlite(String freq, String band, String name) {
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            File dbFile = new File(DB_PATH);
            if (!dbFile.exists()) return;

            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                DB_PATH, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            // MainActivity может держать своё соединение с этим же файлом открытым
            // всё время работы приложения — без busy_timeout запись отсюда может
            // мгновенно упасть с SQLITE_BUSY вместо того чтобы подождать и повторить.
            try { db.execSQL("PRAGMA busy_timeout = 3000"); } catch (Throwable ignored) { }

            String cityId = null;
            android.database.Cursor c = db.rawQuery(
                "SELECT value FROM settings WHERE key='current_city_id'", null);
            if (c.moveToFirst()) cityId = c.getString(0);
            c.close();
            if (cityId == null) return;

            android.database.Cursor c2 = db.rawQuery(
                "SELECT COUNT(*) FROM stations WHERE city_id=? AND freq=? AND band=?",
                new String[]{cityId, freq, band});
            int exists = c2.moveToFirst() ? c2.getInt(0) : 0;
            c2.close();

            if (exists > 0) {
                db.execSQL("UPDATE stations SET user_name=? WHERE city_id=? AND freq=? AND band=?",
                    new Object[]{name, cityId, freq, band});
            } else {
                db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                    new Object[]{Integer.parseInt(cityId), freq, band, name, name});
            }
            XposedBridge.log(TAG + ": ✓ persisted '" + name + "' for " + freq + " " + band + " to SQLite");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": persistToSqlite error: " + t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) { }
        }
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
