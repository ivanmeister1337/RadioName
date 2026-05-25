package com.belgee.radionamehook;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * База станций. Читает JSON-файл с тем же форматом что и старое RadioNameApp.
 *
 * Формат файла /sdcard/RadioNames/stations.json:
 * {
 *   "fm": {
 *     "103.4": "Русское Радио",
 *     "104.2": "Авторадио",
 *     ...
 *   },
 *   "am": {
 *     "675": "Маяк",
 *     ...
 *   }
 * }
 */
public class StationsDatabase {
    private static final String TAG = "RadioNameHook";
    private static final String JSON_PATH = "/sdcard/RadioNames/stations.json";
    private static final String STYLE_PATH = "/sdcard/RadioNames/style.txt";

    private static Map<String, String> fmStations = new HashMap<>();
    private static Map<String, String> amStations = new HashMap<>();
    private static long lastLoadTime = 0;
    private static final long RELOAD_INTERVAL_MS = 30000;

    // Стили отображения на виджете
    // 0: "Радио Маяк"              (только имя, частота скрыта)
    // 1: "102.0 Радио Маяк"        (частота + имя)
    // 2: "Радио Маяк 102.0"        (имя + частота)
    // 3: "102.0 | Радио Маяк"      (частота | имя)
    // 4: "Радио Маяк | 102.0"      (имя | частота)
    // Если имя не найдено — всегда показываем только частоту
    private static int displayStyle = 0;
    private static long lastStyleLoad = 0;

    public static void init() {
        loadFromFile();
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

    /**
     * Форматирует текст для отображения на виджете согласно выбранному стилю.
     * @return отформатированный текст или null если ничего не менять
     */
    public static String formatDisplay(String freq, String name) {
        // Перезагружаем стиль если давно
        if (System.currentTimeMillis() - lastStyleLoad > RELOAD_INTERVAL_MS) loadStyle();

        if (name == null || name.isEmpty()) return null; // не найдена — оставить частоту как есть

        switch (displayStyle) {
            case 0: return name;                          // "Радио Маяк"
            case 1: return freq + " " + name;             // "102.0 Радио Маяк"
            case 2: return name + " " + freq;             // "Радио Маяк 102.0"
            case 3: return freq + " | " + name;           // "102.0 | Радио Маяк"
            case 4: return name + " | " + freq;           // "Радио Маяк | 102.0"
            default: return name;
        }
    }

    public static int getDisplayStyle() { return displayStyle; }

    public static void setDisplayStyle(int style) {
        displayStyle = style;
        try {
            File dir = new File(STYLE_PATH).getParentFile();
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(STYLE_PATH)) { w.write(String.valueOf(style)); }
        } catch (Throwable t) { }
    }

    private static synchronized void loadFromFile() {
        try {
            File file = new File(JSON_PATH);
            if (!file.exists()) {
                XposedBridge.log(TAG + ": stations.json not found at " + JSON_PATH);
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            fmStations.clear();
            amStations.clear();

            if (root.has("fm")) {
                JSONObject fm = root.getJSONObject("fm");
                fm.keys().forEachRemaining(key -> {
                    try {
                        fmStations.put(key, fm.getString(key));
                    } catch (Throwable t) { /* skip */ }
                });
            }
            if (root.has("am")) {
                JSONObject am = root.getJSONObject("am");
                am.keys().forEachRemaining(key -> {
                    try {
                        amStations.put(key, am.getString(key));
                    } catch (Throwable t) { /* skip */ }
                });
            }

            lastLoadTime = System.currentTimeMillis();
            XposedBridge.log(TAG + ": loaded " + fmStations.size() + " FM + "
                + amStations.size() + " AM stations from JSON");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": load JSON error: " + t.getMessage());
        }
    }

    /**
     * Найти название станции по частоте.
     * @param freq частота - может быть в разных форматах: "103.4", "103400", "103.4 МГц", "675", "675 кГц"
     * @return название или null если не найдено
     */
    public static String findName(String freq) {
        if (freq == null || freq.isEmpty()) return null;

        // Перезагружаем JSON если давно не обновляли
        if (System.currentTimeMillis() - lastLoadTime > RELOAD_INTERVAL_MS) {
            loadFromFile();
        }

        // Нормализуем частоту
        String normalized = normalizeFreq(freq);
        if (normalized == null) return null;

        // Сначала пробуем FM
        String result = fmStations.get(normalized);
        if (result != null) return result;

        // Потом AM
        result = amStations.get(normalized);
        if (result != null) return result;

        return null;
    }

    /**
     * Привести любой формат частоты к ключу JSON.
     * "103400" -> "103.4"
     * "103.4 МГц" -> "103.4"
     * "103.4" -> "103.4"
     * "675 кГц" -> "675"
     * "675" -> "675"
     */
    private static String normalizeFreq(String freq) {
        try {
            // Убираем суффиксы (МГц, кГц, MHz, kHz)
            String cleaned = freq.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return null;

            // Если есть точка - это уже FM формат, нормализуем чтобы убрать лишние .0
            if (cleaned.contains(".")) {
                // "95.0" -> "95.0", "103.4" -> "103.4", "103.40" -> "103.4"
                try {
                    double d = Double.parseDouble(cleaned);
                    return formatFm(d);
                } catch (NumberFormatException e) {
                    return cleaned;
                }
            }

            // Если число большое (> 10000) - значит в килогерцах FM (103400 -> 103.4)
            int num = Integer.parseInt(cleaned);
            if (num > 10000) {
                // FM в килогерцах: 95000 -> 95.0, 103400 -> 103.4, 91200 -> 91.2
                return formatFm(num / 1000.0);
            }

            // Иначе - AM, возвращаем как есть
            return cleaned;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Форматирует double в строку FM-частоты как в нашей базе.
     * 95.0 -> "95.0", 103.4 -> "103.4", 91.2 -> "91.2", 100.5 -> "100.5"
     * Округляем до 1 знака после точки.
     */
    private static String formatFm(double d) {
        // Округление до 1 знака
        double rounded = Math.round(d * 10.0) / 10.0;
        // Если целое - добавляем ".0"
        if (rounded == Math.floor(rounded)) {
            return ((int) rounded) + ".0";
        }
        // Иначе - 1 знак после точки
        return String.format(java.util.Locale.US, "%.1f", rounded);
    }

    /**
     * Обратный поиск — по имени станции найти частоту.
     */
    public static String findNameReverse(String name) {
        if (name == null || name.isEmpty()) return null;
        if (System.currentTimeMillis() - lastLoadTime > RELOAD_INTERVAL_MS) loadFromFile();
        for (Map.Entry<String, String> e : fmStations.entrySet()) {
            if (name.equals(e.getValue())) return e.getKey();
        }
        for (Map.Entry<String, String> e : amStations.entrySet()) {
            if (name.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** Публичный доступ к normalizeFreq. */
    public static String normalizeFreqPublic(String freq) {
        return normalizeFreq(freq);
    }

    /** Обновить станцию и сохранить JSON. */
    public static void updateStation(String freq, String name) {
        if (freq == null || name == null) return;
        if (freq.contains(".")) {
            fmStations.put(freq, name);
        } else {
            try {
                int f = Integer.parseInt(freq);
                if (f > 1000) fmStations.put(formatFm(f / 1000.0), name);
                else amStations.put(freq, name);
            } catch (NumberFormatException e) { fmStations.put(freq, name); }
        }
        saveToFile();
    }

    private static void saveToFile() {
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONObject fm = new org.json.JSONObject();
            for (Map.Entry<String, String> e : fmStations.entrySet()) fm.put(e.getKey(), e.getValue());
            org.json.JSONObject am = new org.json.JSONObject();
            for (Map.Entry<String, String> e : amStations.entrySet()) am.put(e.getKey(), e.getValue());
            root.put("fm", fm);
            root.put("am", am);
            java.io.File dir = new java.io.File(JSON_PATH).getParentFile();
            if (!dir.exists()) dir.mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(JSON_PATH)) { w.write(root.toString(2)); }
            XposedBridge.log(TAG + ": ✓ saved stations.json");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": save error: " + t.getMessage());
        }
    }
}
