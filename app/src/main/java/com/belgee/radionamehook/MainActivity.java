package com.belgee.radionamehook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String DIR_PATH = Environment.getExternalStorageDirectory() + "/RadioNames";
    private static final String DB_PATH = DIR_PATH + "/radio.db";
    private static final String CACHE_PATH = DIR_PATH + "/current_stations.json";
    private static final String STYLE_PATH = DIR_PATH + "/style.txt";
    private static final String FREQ_PATH = DIR_PATH + "/current_freq.txt";

    private SQLiteDatabase db;
    private LinearLayout stationListLayout;
    private TextView cityLabel, currentFreqLabel, gpsStatusLabel;
    private Button btnFM, btnAM;
    private boolean showingFM = true;
    private int currentCityId = 1;
    private String currentCityName = "Санкт-Петербург";
    private int currentDisplayStyle = 0;
    private boolean autoGpsEnabled = false;
    private Handler handler = new Handler();

    private static final String[] STYLES = {
        "Только имя", "Частота + имя", "Имя + частота",
        "Частота | имя", "Имя | частота"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, 1);
            }
            new File(DIR_PATH).mkdirs();
            initDatabase();
            loadSettings();
            loadStyle();
            buildUI();
            autoCheckUpdate();
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("Ошибка: " + t + "\n" + android.util.Log.getStackTraceString(t));
            tv.setTextColor(0xFFFF0000);
            tv.setPadding(32, 32, 32, 32);
            setContentView(tv);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentFreqLabel != null) updateCurrentFreqLabel();
        // Автообновление "Сейчас играет" каждые 3 секунды
        startFreqRefresh();
        // Восстанавливаем GPS если был включён
        if (autoGpsEnabled && gpsStatusLabel != null) {
            requestGpsUpdate();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopFreqRefresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFreqRefresh();
        if (db != null) try { db.close(); } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    private Runnable freqRefreshRunnable;
    private void startFreqRefresh() {
        freqRefreshRunnable = new Runnable() {
            @Override public void run() {
                if (currentFreqLabel != null) updateCurrentFreqLabel();
                handler.postDelayed(this, 3000);
            }
        };
        handler.postDelayed(freqRefreshRunnable, 3000);
    }
    private void stopFreqRefresh() {
        if (freqRefreshRunnable != null) handler.removeCallbacks(freqRefreshRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == 1) {
            new File(DIR_PATH).mkdirs();
            initDatabase();
            loadSettings();
            if (stationListLayout != null) refreshList();
        }
    }

    // === DATABASE ===

    private void initDatabase() {
        try {
            File dbFile = new File(DB_PATH);
            if (!dbFile.exists()) {
                copyDbFromAssets(dbFile);
            }
            db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READWRITE);
            upgradeDatabase();
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    private void copyDbFromAssets(File dest) throws IOException {
        File dir = dest.getParentFile();
        if (!dir.exists()) dir.mkdirs();
        InputStream is = getAssets().open("radio.db");
        OutputStream os = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
        os.close(); is.close();
    }

    /** Обновляет БД если нет колонок latitude/longitude */
    private void upgradeDatabase() {
        if (db == null) return;
        try {
            Cursor c = db.rawQuery("PRAGMA table_info(cities)", null);
            boolean hasLatitude = false;
            while (c.moveToNext()) {
                if ("latitude".equals(c.getString(1))) hasLatitude = true;
            }
            c.close();
            if (!hasLatitude) {
                db.execSQL("ALTER TABLE cities ADD COLUMN latitude REAL DEFAULT 0");
                db.execSQL("ALTER TABLE cities ADD COLUMN longitude REAL DEFAULT 0");
            }
            c = db.rawQuery("SELECT COUNT(*) FROM cities WHERE latitude != 0", null);
            int withCoords = c.moveToFirst() ? c.getInt(0) : 0; c.close();
            if (withCoords == 0) {
                double[][] coords = {
                    {1,59.9343,30.3351},{2,55.7558,37.6173},{3,53.9045,27.5615},
                    {4,55.7887,49.1221},{5,56.8389,60.6057},{6,56.2965,43.9361},
                    {7,55.0084,82.9357},{8,45.0355,38.9753},{9,47.2357,39.7015},
                    {10,53.1959,50.1001},{11,51.6615,39.2003},{12,56.0153,92.8932},
                };
                for (double[] row : coords)
                    db.execSQL("UPDATE cities SET latitude=?,longitude=? WHERE id=?",
                        new Object[]{row[1], row[2], (int)row[0]});
            }
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    /** Обновление базы с сохранением пользовательских имён и кастомных городов */
    private void resetDatabase() {
        try {
            // 1. Сохраняем всё нужное перед заменой БД
            Map<String, String> userNames = new HashMap<>();
            List<String[]> allCities = new ArrayList<>(); // name, region
            Map<String, List<String[]>> allCityStations = new HashMap<>();

            if (db != null) {
                // Сохраняем user_name
                try {
                    Cursor c = db.rawQuery(
                        "SELECT c.name, s.freq, s.band, s.user_name FROM stations s JOIN cities c ON c.id=s.city_id WHERE s.user_name IS NOT NULL",
                        null);
                    while (c.moveToNext()) {
                        userNames.put(c.getString(0) + "|" + c.getString(1) + "|" + c.getString(2), c.getString(3));
                    }
                    c.close();
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }

                // Сохраняем ВСЕ города и их станции (потом сравним с новой базой)
                try {
                    Cursor c = db.rawQuery("SELECT id, name, region FROM cities", null);
                    while (c.moveToNext()) {
                        int cid = c.getInt(0);
                        String cname = c.getString(1);
                        allCities.add(new String[]{cname, c.getString(2)});
                        List<String[]> sts = new ArrayList<>();
                        Cursor cs = db.rawQuery("SELECT freq, band, COALESCE(user_name, name) FROM stations WHERE city_id=?",
                            new String[]{String.valueOf(cid)});
                        while (cs.moveToNext())
                            sts.add(new String[]{cs.getString(0), cs.getString(1), cs.getString(2)});
                        cs.close();
                        allCityStations.put(cname, sts);
                    }
                    c.close();
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }

                db.close();
            }

            // 2. Заменяем базу
            File dbFile = new File(DB_PATH);
            dbFile.delete();
            copyDbFromAssets(dbFile);
            db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READWRITE);
            upgradeDatabase();

            // 3. Собираем имена городов из новой базы
            Set<String> newBaseCities = new HashSet<>();
            try {
                Cursor c = db.rawQuery("SELECT name FROM cities", null);
                while (c.moveToNext()) newBaseCities.add(c.getString(0));
                c.close();
            } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }

            // 4. Восстанавливаем user_name для существующих станций
            int restoredNames = 0;
            for (Map.Entry<String, String> entry : userNames.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 3);
                if (parts.length != 3) continue;
                String cityName = parts[0], freq = parts[1], band = parts[2];
                try {
                    db.execSQL(
                        "UPDATE stations SET user_name=? WHERE freq=? AND band=? AND city_id=(SELECT id FROM cities WHERE name=?)",
                        new Object[]{entry.getValue(), freq, band, cityName});
                    restoredNames++;
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
            }

            // 5. Восстанавливаем кастомные города (те что БЫЛИ но НЕ в новой базе)
            int restoredCities = 0;
            for (String[] city : allCities) {
                if (newBaseCities.contains(city[0])) continue; // уже есть в новой базе
                try {
                    db.execSQL("INSERT INTO cities(name,region,latitude,longitude) VALUES(?,?,0,0)",
                        new Object[]{city[0], city[1]});
                    Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
                    int newId = c.moveToFirst() ? c.getInt(0) : -1; c.close();
                    if (newId > 0 && allCityStations.containsKey(city[0])) {
                        for (String[] st : allCityStations.get(city[0])) {
                            db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                                new Object[]{newId, st[0], st[1], st[2], st[2]});
                        }
                    }
                    restoredCities++;
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
            }

            loadSettings();
            refreshList();
            Toast.makeText(this,
                "✓ База обновлена. Восстановлено " + restoredNames + " имён, " + restoredCities + " городов",
                Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadSettings() {
        if (db == null) return;
        try {
            Cursor c = db.rawQuery("SELECT value FROM settings WHERE key='current_city_id'", null);
            if (c.moveToFirst()) currentCityId = Integer.parseInt(c.getString(0));
            c.close();
            c = db.rawQuery("SELECT name FROM cities WHERE id=?", new String[]{String.valueOf(currentCityId)});
            if (c.moveToFirst()) currentCityName = c.getString(0);
            c.close();
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
        exportStationsToCache();
    }

    private void loadStyle() {
        try {
            File f = new File(STYLE_PATH);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line = r.readLine();
                if (line != null) currentDisplayStyle = Integer.parseInt(line.trim());
            }
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
        // Загружаем состояние GPS
        try {
            File f = new File(DIR_PATH + "/gps_auto.txt");
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    autoGpsEnabled = "1".equals(r.readLine());
                }
            }
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    private void saveStyle() {
        try (FileWriter w = new FileWriter(STYLE_PATH)) { w.write(String.valueOf(currentDisplayStyle)); }
        catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    private void exportStationsToCache() {
        if (db == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject fm = new JSONObject(), am = new JSONObject();
            Cursor c = db.rawQuery("SELECT freq,band,COALESCE(user_name,name) FROM stations WHERE city_id=?",
                new String[]{String.valueOf(currentCityId)});
            while (c.moveToNext()) {
                if ("FM".equals(c.getString(1))) fm.put(c.getString(0), c.getString(2));
                else am.put(c.getString(0), c.getString(2));
            }
            c.close();
            root.put("fm", fm); root.put("am", am);
            try (FileWriter w = new FileWriter(CACHE_PATH)) { w.write(root.toString(2)); }
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    // === UI ===

    // Цвета темы
    private static final int BG = 0xFF0D1117;
    private static final int CARD_BG = 0xFF161B22;
    private static final int CARD_BORDER = 0xFF30363D;
    private static final int ACCENT = 0xFF58A6FF;
    private static final int ACCENT2 = 0xFF3FB950;
    private static final int TEXT_PRIMARY = 0xFFE6EDF3;
    private static final int TEXT_SECONDARY = 0xFF8B949E;
    private static final int TEXT_WARN = 0xFFD29922;
    private static final int BTN_BG = 0xFF21262D;
    private static final int BTN_ACCENT = 0xFF238636;
    private static final int DIVIDER = 0xFF21262D;

    private android.graphics.drawable.GradientDrawable cardBg() {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(CARD_BG);
        gd.setCornerRadius(24);
        gd.setStroke(2, CARD_BORDER);
        return gd;
    }

    private android.graphics.drawable.GradientDrawable btnBg(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(16);
        return gd;
    }

    private Button styledBtn(String text, int bgColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(TEXT_PRIMARY);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setBackground(btnBg(bgColor));
        btn.setPadding(28, 12, 28, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(6, 4, 6, 4);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(28, 20, 28, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 10, 0, 10);
        card.setLayoutParams(lp);
        parent.addView(card);
        return card;
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        // === Заголовок ===
        TextView title = new TextView(this);
        title.setText("📻 RadioNameHook");
        title.setTextSize(24); title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER);
        title.setPadding(0, 8, 0, 16);
        root.addView(title);

        // === Карточка: Сейчас играет ===
        LinearLayout nowCard = card(root);
        TextView nowTitle = new TextView(this);
        nowTitle.setText("СЕЙЧАС ИГРАЕТ");
        nowTitle.setTextSize(11); nowTitle.setTextColor(TEXT_SECONDARY);
        nowTitle.setTypeface(null, Typeface.BOLD);
        nowTitle.setLetterSpacing(0.1f);
        nowCard.addView(nowTitle);

        currentFreqLabel = new TextView(this);
        currentFreqLabel.setTextSize(17); currentFreqLabel.setTextColor(TEXT_WARN);
        currentFreqLabel.setPadding(0, 8, 0, 12);
        updateCurrentFreqLabel();
        nowCard.addView(currentFreqLabel);

        nowCard.addView(styledBtn("✏ Переименовать текущую", BTN_BG, v -> editCurrentStation()));

        // === Карточка: Настройки ===
        LinearLayout settingsCard = card(root);
        TextView setTitle = new TextView(this);
        setTitle.setText("НАСТРОЙКИ");
        setTitle.setTextSize(11); setTitle.setTextColor(TEXT_SECONDARY);
        setTitle.setTypeface(null, Typeface.BOLD);
        setTitle.setLetterSpacing(0.1f);
        settingsCard.addView(setTitle);

        // Город
        LinearLayout cityRow = new LinearLayout(this);
        cityRow.setOrientation(LinearLayout.HORIZONTAL);
        cityRow.setGravity(Gravity.CENTER_VERTICAL);
        cityRow.setPadding(0, 12, 0, 8);
        TextView cityText = new TextView(this); cityText.setText("🏙  Город:  ");
        cityText.setTextSize(16); cityText.setTextColor(TEXT_SECONDARY);
        cityRow.addView(cityText);
        cityLabel = new TextView(this); cityLabel.setText(currentCityName);
        cityLabel.setTextSize(16); cityLabel.setTextColor(ACCENT);
        cityLabel.setTypeface(null, Typeface.BOLD);
        cityLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cityRow.addView(cityLabel);
        cityRow.addView(styledBtn("Сменить", BTN_BG, v -> showCityDialog()));
        settingsCard.addView(cityRow);

        // Стиль
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setGravity(Gravity.CENTER_VERTICAL);
        styleRow.setPadding(0, 4, 0, 8);
        TextView stText = new TextView(this); stText.setText("🎨  Виджет:  ");
        stText.setTextSize(16); stText.setTextColor(TEXT_SECONDARY);
        styleRow.addView(stText);
        final TextView styleLabel = new TextView(this);
        styleLabel.setText(STYLES[Math.min(currentDisplayStyle, STYLES.length - 1)]);
        styleLabel.setTextSize(14); styleLabel.setTextColor(ACCENT);
        styleLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        styleRow.addView(styleLabel);
        styleRow.addView(styledBtn("Стиль", BTN_BG, v -> new AlertDialog.Builder(this)
            .setTitle("Стиль виджета").setItems(STYLES, (d, w) -> {
                currentDisplayStyle = w; saveStyle(); styleLabel.setText(STYLES[w]);
            }).show()));
        settingsCard.addView(styleRow);

        // GPS
        LinearLayout gpsRow = new LinearLayout(this);
        gpsRow.setOrientation(LinearLayout.HORIZONTAL);
        gpsRow.setGravity(Gravity.CENTER_VERTICAL);
        gpsRow.setPadding(0, 4, 0, 4);
        TextView gpText = new TextView(this); gpText.setText("📍  GPS авто:  ");
        gpText.setTextSize(16); gpText.setTextColor(TEXT_SECONDARY);
        gpsRow.addView(gpText);
        gpsStatusLabel = new TextView(this);
        gpsStatusLabel.setTextSize(14);
        gpsStatusLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        updateGpsLabel();
        gpsRow.addView(gpsStatusLabel);
        gpsRow.addView(styledBtn(autoGpsEnabled ? "Выключить" : "Включить",
            autoGpsEnabled ? 0xFF8B0000 : BTN_ACCENT, v -> toggleGps()));
        settingsCard.addView(gpsRow);

        // === Карточка: Станции ===
        LinearLayout stationsCard = card(root);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);
        btnFM = styledBtn("FM", BTN_BG, v -> { showingFM = true; refreshList(); });
        btnAM = styledBtn("AM", BTN_BG, v -> { showingFM = false; refreshList(); });
        Button btnAdd = styledBtn("+ Добавить", BTN_ACCENT, v -> showAddDialog());
        tabRow.addView(btnFM); tabRow.addView(btnAM); tabRow.addView(btnAdd);
        stationsCard.addView(tabRow);

        stationListLayout = new LinearLayout(this);
        stationListLayout.setOrientation(LinearLayout.VERTICAL);
        stationListLayout.setPadding(0, 8, 0, 0);
        stationsCard.addView(stationListLayout);

        // === Кнопки внизу ===
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER);
        bottomRow.setPadding(0, 16, 0, 8);
        bottomRow.addView(styledBtn("🌐 Обновить онлайн", BTN_ACCENT, v -> checkForDatabaseUpdate(true)));
        bottomRow.addView(styledBtn("⬇ Сбросить базу", BTN_BG, v -> new AlertDialog.Builder(this)
            .setTitle("Сбросить базу?")
            .setMessage("Загрузит встроенную базу городов.\nПользовательские названия сохранятся ✓")
            .setPositiveButton("Сбросить", (d, w) -> resetDatabase())
            .setNegativeButton("Отмена", null).show()));
        bottomRow.addView(styledBtn("📤 Экспорт", BTN_BG, v -> exportUserNames()));
        bottomRow.addView(styledBtn("📥 Импорт", BTN_BG, v -> importUserNames()));
        root.addView(bottomRow);

        scroll.addView(root);
        setContentView(scroll);
        refreshList();
    }

    // === СПИСОК СТАНЦИЙ ===

    private void refreshList() {
        if (db == null || stationListLayout == null) return;
        stationListLayout.removeAllViews();
        String band = showingFM ? "FM" : "AM";

        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM stations WHERE city_id=? AND band='FM'",
                new String[]{String.valueOf(currentCityId)});
            int fmCount = c.moveToFirst() ? c.getInt(0) : 0; c.close();
            c = db.rawQuery("SELECT COUNT(*) FROM stations WHERE city_id=? AND band='AM'",
                new String[]{String.valueOf(currentCityId)});
            int amCount = c.moveToFirst() ? c.getInt(0) : 0; c.close();
            btnFM.setText("FM (" + fmCount + ")"); btnAM.setText("AM (" + amCount + ")");

            c = db.rawQuery("SELECT freq,COALESCE(user_name,name),user_name FROM stations WHERE city_id=? AND band=? ORDER BY CAST(freq AS REAL)",
                new String[]{String.valueOf(currentCityId), band});
            while (c.moveToNext()) {
                String freq = c.getString(0), dname = c.getString(1);
                boolean custom = !c.isNull(2);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(4, 14, 4, 14);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView fv = new TextView(this); fv.setText(freq);
                fv.setTextSize(15); fv.setTextColor(ACCENT);
                fv.setTypeface(null, Typeface.BOLD); fv.setMinWidth(140);
                row.addView(fv);

                TextView nv = new TextView(this);
                nv.setText(dname + (custom ? " ✎" : ""));
                nv.setTextSize(14); nv.setTextColor(custom ? TEXT_WARN : TEXT_PRIMARY);
                nv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                nv.setPadding(16, 0, 8, 0);
                row.addView(nv);

                row.addView(styledBtn("✏", BTN_BG, v2 -> showEditDialog(freq, dname)));

                stationListLayout.addView(row);

                View div = new View(this); div.setBackgroundColor(DIVIDER);
                div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                stationListLayout.addView(div);
            }
            c.close();
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    // === GPS ===

    private void updateGpsLabel() {
        if (gpsStatusLabel == null) return;
        if (autoGpsEnabled) {
            gpsStatusLabel.setText("● Активен");
            gpsStatusLabel.setTextColor(ACCENT2);
        } else {
            gpsStatusLabel.setText("○ Выключен");
            gpsStatusLabel.setTextColor(TEXT_SECONDARY);
        }
    }

    private void toggleGps() {
        autoGpsEnabled = !autoGpsEnabled;
        try (FileWriter w = new FileWriter(DIR_PATH + "/gps_auto.txt")) { w.write(autoGpsEnabled ? "1" : "0"); }
        catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
        updateGpsLabel();
        if (autoGpsEnabled) {
            gpsStatusLabel.setText("● Ищу спутники...");
            gpsStatusLabel.setTextColor(TEXT_WARN);
            requestGpsUpdate();
        }
    }

    private void requestGpsUpdate() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) { gpsStatusLabel.setText("GPS недоступен"); return; }

            // Последнее известное местоположение — пробуем ВСЕ провайдеры
            Location last = null;
            String[] providers = {"gps", "passive", "network", "fused"};
            for (String prov : providers) {
                try {
                    Location loc = lm.getLastKnownLocation(prov);
                    if (loc != null && (last == null || loc.getTime() > last.getTime())) {
                        last = loc;
                    }
                } catch (Throwable e) {}
            }

            if (last != null) {
                gpsStatusLabel.setText("Координаты получены...");
                gpsStatusLabel.setTextColor(ACCENT2);
                checkNearestCity(last.getLatitude(), last.getLongitude());
            } else {
                gpsStatusLabel.setText("Жду координаты...");
            }

            // Слушатель обновлений
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    if (autoGpsEnabled) checkNearestCity(loc.getLatitude(), loc.getLongitude());
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };

            // Подписываемся на gps и passive
            for (String prov : new String[]{"gps", "passive"}) {
                try {
                    if (lm.isProviderEnabled(prov)) {
                        lm.requestLocationUpdates(prov, 60000, 1000, listener);
                    }
                } catch (Throwable e) {}
            }
        } catch (Exception e) {
            gpsStatusLabel.setText("Ошибка: " + e.getMessage());
            gpsStatusLabel.setTextColor(0xFFF85149);
        }
    }

    private void checkNearestCity(double lat, double lon) {
        if (db == null) return;
        try {
            double minDist = Double.MAX_VALUE;
            int nearestId = -1;
            String nearestName = "";

            Cursor c = db.rawQuery("SELECT id, name, latitude, longitude FROM cities WHERE latitude != 0", null);
            while (c.moveToNext()) {
                double clat = c.getDouble(2), clon = c.getDouble(3);
                double dist = haversine(lat, lon, clat, clon);
                if (dist < minDist) {
                    minDist = dist; nearestId = c.getInt(0); nearestName = c.getString(1);
                }
            }
            c.close();

            final int fNearestId = nearestId;
            final String fNearestName = nearestName;
            final double fMinDist = minDist;

            runOnUiThread(() -> {
                if (fNearestId <= 0) {
                    gpsStatusLabel.setText("Нет городов с координатами в БД");
                    gpsStatusLabel.setTextColor(0xFFF85149);
                    return;
                }

                if (fNearestId != currentCityId && fMinDist < 200) {
                    // Переключаем город
                    currentCityId = fNearestId;
                    currentCityName = fNearestName;
                    db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('current_city_id',?)",
                        new Object[]{String.valueOf(fNearestId)});
                    exportStationsToCache();
                    if (cityLabel != null) cityLabel.setText(fNearestName);
                    refreshList();
                    gpsStatusLabel.setText("📍 → " + fNearestName + " (" + (int) fMinDist + " км)");
                    gpsStatusLabel.setTextColor(ACCENT2);
                    Toast.makeText(this, "📍 Переключено: " + fNearestName, Toast.LENGTH_SHORT).show();
                } else {
                    gpsStatusLabel.setText("✓ " + fNearestName + " (" + (int) fMinDist + " км)");
                    gpsStatusLabel.setTextColor(ACCENT2);
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                gpsStatusLabel.setText("Ошибка: " + e.getMessage());
                gpsStatusLabel.setTextColor(0xFFF85149);
            });
        }
    }

    /** Расстояние в км между двумя точками (формула Haversine) */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
            Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon/2)*Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    // === ТЕКУЩАЯ ЧАСТОТА ===

    private void updateCurrentFreqLabel() {
        // Проверяем что кэш существует - без него хуки не сработают
        File cache = new File(CACHE_PATH);
        if (!cache.exists()) {
            currentFreqLabel.setText("⚠ Кэш не создан — нажми любую станцию в списке");
            return;
        }
        try {
            File f = new File(FREQ_PATH);
            if (f.exists()) {
                long age = System.currentTimeMillis() - f.lastModified();
                BufferedReader r = new BufferedReader(new FileReader(f));
                String freq = r.readLine(); r.close();
                if (freq != null && !freq.isEmpty()) {
                    String name = findStationName(freq);
                    String prefix = age > 5 * 60 * 1000 ? "◌ FM " : "▶ FM ";
                    currentFreqLabel.setText(name != null ?
                        prefix + freq + " — " + name :
                        prefix + freq + " (нет в базе)");
                    return;
                }
            }
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
        currentFreqLabel.setText("◌ Радио не играет");
    }

    private String findStationName(String freq) {
        if (db == null) return null;
        try {
            Cursor c = db.rawQuery("SELECT COALESCE(user_name,name) FROM stations WHERE city_id=? AND freq=?",
                new String[]{String.valueOf(currentCityId), freq});
            String n = c.moveToFirst() ? c.getString(0) : null; c.close();
            return n;
        } catch (Exception e) { return null; }
    }

    private void editCurrentStation() {
        try {
            File f = new File(FREQ_PATH);
            if (!f.exists()) { Toast.makeText(this, "Включи радио", Toast.LENGTH_SHORT).show(); return; }
            BufferedReader r = new BufferedReader(new FileReader(f));
            String freq = r.readLine(); r.close();
            if (freq == null || freq.isEmpty()) return;

            String cur = findStationName(freq);
            EditText input = new EditText(this);
            input.setText(cur != null ? cur : ""); input.setHint("Название");
            input.setPadding(48, 24, 48, 24);

            new AlertDialog.Builder(this).setTitle("FM " + freq).setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    saveStation(freq, name);
                    exportStationsToCache(); updateCurrentFreqLabel(); refreshList();
                }).setNegativeButton("Отмена", null).show();
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    private void saveStation(String freq, String name) {
        if (db == null) return;
        try {
            Cursor c = db.rawQuery("SELECT id FROM stations WHERE city_id=? AND freq=?",
                new String[]{String.valueOf(currentCityId), freq});
            if (c.moveToFirst()) {
                db.execSQL("UPDATE stations SET user_name=? WHERE id=?", new Object[]{name, c.getInt(0)});
            } else {
                db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                    new Object[]{currentCityId, freq, "FM", name, name});
            }
            c.close();
        } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
    }

    // === СЕТЕВОЕ ОБНОВЛЕНИЕ БАЗЫ ===

    // URL базы на GitHub. Формат: raw.githubusercontent.com/<user>/<repo>/main/<file>
    private static final String REMOTE_DB_URL =
        "https://raw.githubusercontent.com/ivanmeister1337/RadioNameHook-DB/main/radio.db";
    private static final String REMOTE_VERSION_URL =
        "https://raw.githubusercontent.com/ivanmeister1337/RadioNameHook-DB/main/radio.db.version";
    private static final String REMOTE_META_PATH = DIR_PATH + "/remote_meta.txt";
    private static final long AUTO_CHECK_INTERVAL = 24 * 60 * 60 * 1000L; // 24 часа

    /** Автопроверка при запуске (не чаще раза в сутки, без диалогов) */
    private void autoCheckUpdate() {
        try {
            File meta = new File(REMOTE_META_PATH);
            if (meta.exists()) {
                long lastCheck = meta.lastModified();
                if (System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL) return;
            }
        } catch (Exception e) {}
        // Запускаем тихую проверку в фоне
        new Thread(() -> checkForDatabaseUpdate(false)).start();
    }

    /**
     * Проверить и скачать обновление базы.
     * @param interactive если true — показывать диалоги/тосты. Если false — тихая фоновая проверка.
     */
    private void checkForDatabaseUpdate(boolean interactive) {
        if (interactive) Toast.makeText(this, "Проверяю обновления...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // 1. Проверяем версию — файл radio.db.version (просто число или ETag)
                java.net.HttpURLConnection versionConn = (java.net.HttpURLConnection)
                    new java.net.URL(REMOTE_VERSION_URL).openConnection();
                versionConn.setConnectTimeout(10000);
                versionConn.setReadTimeout(10000);
                versionConn.setRequestProperty("User-Agent", "RadioNameHook");

                String remoteVersion = "";
                if (versionConn.getResponseCode() == 200) {
                    java.io.InputStream is = versionConn.getInputStream();
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    is.close();
                    remoteVersion = baos.toString("UTF-8").trim();
                }
                versionConn.disconnect();

                // 2. Читаем локальную версию
                String localVersion = "";
                File meta = new File(REMOTE_META_PATH);
                if (meta.exists()) {
                    try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
                        localVersion = r.readLine();
                        if (localVersion == null) localVersion = "";
                    }
                }

                // 3. Если версия совпадает — обновление не нужно
                if (!remoteVersion.isEmpty() && remoteVersion.equals(localVersion)) {
                    final String lvForToast = localVersion;
                    runOnUiThread(() -> {
                        if (interactive)
                            Toast.makeText(this, "✓ База актуальна (v" + lvForToast + ")",
                                Toast.LENGTH_SHORT).show();
                    });
                    // Обновим mtime чтобы автопроверка не бегала
                    try { meta.setLastModified(System.currentTimeMillis()); } catch (Exception e) {}
                    return;
                }

                final String rvFinal = remoteVersion;
                final String lvFinal = localVersion;

                // 4. Показываем диалог с подтверждением (только если interactive)
                if (interactive) {
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Доступно обновление")
                        .setMessage("Текущая версия: " + (lvFinal.isEmpty() ? "не установлена" : lvFinal) +
                                    "\nНовая версия: " + rvFinal +
                                    "\n\nПользовательские названия сохранятся ✓")
                        .setPositiveButton("Скачать", (d, w) -> downloadDatabase(rvFinal, true))
                        .setNegativeButton("Отмена", null).show());
                } else {
                    // Тихое скачивание, только уведомление о завершении
                    downloadDatabase(rvFinal, false);
                }
            } catch (Exception e) {
                Log.w("RadioName", "update check failed: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    if (interactive)
                        Toast.makeText(this, "Ошибка проверки: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Скачать и применить БД */
    private void downloadDatabase(String newVersion, boolean interactive) {
        new Thread(() -> {
            try {
                // Скачиваем во временный файл
                File tmpFile = new File(DIR_PATH + "/radio_download.db");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(REMOTE_DB_URL).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "RadioNameHook");

                if (conn.getResponseCode() != 200) {
                    throw new IOException("HTTP " + conn.getResponseCode());
                }

                int total = conn.getContentLength();
                try (java.io.InputStream is = conn.getInputStream();
                     java.io.FileOutputStream os = new java.io.FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[8192];
                    int n; long read = 0;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        read += n;
                    }
                }
                conn.disconnect();

                // Проверяем что файл валидный (SQLite header)
                try (java.io.FileInputStream fis = new java.io.FileInputStream(tmpFile)) {
                    byte[] header = new byte[16];
                    int hread = fis.read(header);
                    String sig = new String(header, 0, Math.min(hread, 15));
                    if (!sig.startsWith("SQLite format")) {
                        tmpFile.delete();
                        throw new IOException("Не валидный SQLite файл");
                    }
                }

                // Применяем — используем ту же логику что resetDatabase, но берём из tmpFile
                runOnUiThread(() -> applyDownloadedDatabase(tmpFile, newVersion, interactive));
            } catch (Exception e) {
                Log.w("RadioName", "download failed: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    if (interactive)
                        Toast.makeText(this, "Ошибка скачивания: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void applyDownloadedDatabase(File tmpFile, String newVersion, boolean interactive) {
        try {
            // 1. Сохраняем пользовательские данные (как в resetDatabase)
            Map<String, String> userNames = new HashMap<>();
            List<String[]> allCities = new ArrayList<>();
            Map<String, List<String[]>> allCityStations = new HashMap<>();

            if (db != null) {
                try {
                    Cursor c = db.rawQuery(
                        "SELECT c.name, s.freq, s.band, s.user_name FROM stations s JOIN cities c ON c.id=s.city_id WHERE s.user_name IS NOT NULL",
                        null);
                    while (c.moveToNext())
                        userNames.put(c.getString(0)+"|"+c.getString(1)+"|"+c.getString(2), c.getString(3));
                    c.close();
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }

                try {
                    Cursor c = db.rawQuery("SELECT id, name, region FROM cities", null);
                    while (c.moveToNext()) {
                        int cid = c.getInt(0);
                        String cname = c.getString(1);
                        allCities.add(new String[]{cname, c.getString(2)});
                        List<String[]> sts = new ArrayList<>();
                        Cursor cs = db.rawQuery("SELECT freq, band, COALESCE(user_name, name) FROM stations WHERE city_id=?",
                            new String[]{String.valueOf(cid)});
                        while (cs.moveToNext())
                            sts.add(new String[]{cs.getString(0), cs.getString(1), cs.getString(2)});
                        cs.close();
                        allCityStations.put(cname, sts);
                    }
                    c.close();
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }

                db.close();
            }

            // 2. Заменяем БД скачанным файлом
            File dbFile = new File(DB_PATH);
            dbFile.delete();
            java.nio.file.Files.move(tmpFile.toPath(), dbFile.toPath());
            db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READWRITE);
            upgradeDatabase();

            // 3. Собираем города из новой базы
            Set<String> newBaseCities = new HashSet<>();
            try {
                Cursor c = db.rawQuery("SELECT name FROM cities", null);
                while (c.moveToNext()) newBaseCities.add(c.getString(0));
                c.close();
            } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }

            // 4. Восстанавливаем user_name
            int restoredNames = 0;
            for (Map.Entry<String, String> entry : userNames.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 3);
                if (parts.length != 3) continue;
                try {
                    db.execSQL(
                        "UPDATE stations SET user_name=? WHERE freq=? AND band=? AND city_id=(SELECT id FROM cities WHERE name=?)",
                        new Object[]{entry.getValue(), parts[1], parts[2], parts[0]});
                    restoredNames++;
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
            }

            // 5. Восстанавливаем кастомные города
            int restoredCities = 0;
            for (String[] city : allCities) {
                if (newBaseCities.contains(city[0])) continue;
                try {
                    db.execSQL("INSERT INTO cities(name,region,latitude,longitude) VALUES(?,?,0,0)",
                        new Object[]{city[0], city[1]});
                    Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
                    int newId = c.moveToFirst() ? c.getInt(0) : -1; c.close();
                    if (newId > 0 && allCityStations.containsKey(city[0])) {
                        for (String[] st : allCityStations.get(city[0])) {
                            db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                                new Object[]{newId, st[0], st[1], st[2], st[2]});
                        }
                    }
                    restoredCities++;
                } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
            }

            // 6. Сохраняем версию
            try (FileWriter w = new FileWriter(REMOTE_META_PATH)) { w.write(newVersion); }

            loadSettings();
            refreshList();
            Toast.makeText(this,
                "✓ База обновлена (v" + newVersion + ")\nИмён: " + restoredNames + ", городов: " + restoredCities,
                Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка применения: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // === ЭКСПОРТ / ИМПОРТ ПОЛЬЗОВАТЕЛЬСКИХ ИМЁН ===

    private static final String EXPORT_PATH = DIR_PATH + "/user_names_backup.json";

    private void exportUserNames() {
        if (db == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject names = new JSONObject();
            int count = 0;

            Cursor c = db.rawQuery(
                "SELECT c.name, s.freq, s.band, s.user_name FROM stations s " +
                "JOIN cities c ON c.id=s.city_id WHERE s.user_name IS NOT NULL", null);
            while (c.moveToNext()) {
                String key = c.getString(0) + "|" + c.getString(1) + "|" + c.getString(2);
                names.put(key, c.getString(3));
                count++;
            }
            c.close();

            // Также сохраним кастомные города (id > 99)
            JSONObject customCities = new JSONObject();
            c = db.rawQuery("SELECT id, name, region FROM cities WHERE id > 99", null);
            while (c.moveToNext()) {
                JSONObject city = new JSONObject();
                city.put("name", c.getString(1));
                city.put("region", c.getString(2));
                JSONObject stations = new JSONObject();
                Cursor cs = db.rawQuery("SELECT freq, COALESCE(user_name, name), band FROM stations WHERE city_id=?",
                    new String[]{String.valueOf(c.getInt(0))});
                while (cs.moveToNext()) {
                    stations.put(cs.getString(0) + "|" + cs.getString(2), cs.getString(1));
                }
                cs.close();
                city.put("stations", stations);
                customCities.put(c.getString(1), city);
            }
            c.close();

            root.put("user_names", names);
            root.put("custom_cities", customCities);
            root.put("exported_at", System.currentTimeMillis());

            try (FileWriter w = new FileWriter(EXPORT_PATH)) { w.write(root.toString(2)); }

            Toast.makeText(this, "✓ Сохранено " + count + " имён в " + EXPORT_PATH, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importUserNames() {
        File f = new File(EXPORT_PATH);
        if (!f.exists()) {
            Toast.makeText(this, "Файл " + EXPORT_PATH + " не найден", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Импорт пользовательских имён")
            .setMessage("Загрузить имена из " + EXPORT_PATH + "?\nТекущие пользовательские имена будут перезаписаны.")
            .setPositiveButton("Импорт", (d, w) -> doImportUserNames())
            .setNegativeButton("Отмена", null).show();
    }

    private void doImportUserNames() {
        if (db == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(EXPORT_PATH))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());

            int restored = 0;
            // 1. Восстанавливаем user_name
            if (root.has("user_names")) {
                JSONObject names = root.getJSONObject("user_names");
                java.util.Iterator<String> keys = names.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String[] parts = key.split("\\|", 3);
                    if (parts.length != 3) continue;
                    String cityName = parts[0], freq = parts[1], band = parts[2];
                    String userName = names.getString(key);
                    try {
                        db.execSQL(
                            "UPDATE stations SET user_name=? WHERE freq=? AND band=? AND city_id=(SELECT id FROM cities WHERE name=?)",
                            new Object[]{userName, freq, band, cityName});
                        restored++;
                    } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
                }
            }

            // 2. Восстанавливаем кастомные города
            int citiesRestored = 0;
            if (root.has("custom_cities")) {
                JSONObject customCities = root.getJSONObject("custom_cities");
                java.util.Iterator<String> cityKeys = customCities.keys();
                while (cityKeys.hasNext()) {
                    String cityKey = cityKeys.next();
                    JSONObject city = customCities.getJSONObject(cityKey);
                    String cname = city.getString("name");
                    String cregion = city.getString("region");

                    // Проверяем что города нет
                    Cursor c = db.rawQuery("SELECT id FROM cities WHERE name=? AND region=?",
                        new String[]{cname, cregion});
                    int existingId = c.moveToFirst() ? c.getInt(0) : -1;
                    c.close();

                    if (existingId < 0) {
                        db.execSQL("INSERT INTO cities(name,region,latitude,longitude) VALUES(?,?,0,0)",
                            new Object[]{cname, cregion});
                        c = db.rawQuery("SELECT last_insert_rowid()", null);
                        existingId = c.moveToFirst() ? c.getInt(0) : -1;
                        c.close();
                        citiesRestored++;
                    }

                    if (existingId > 0 && city.has("stations")) {
                        JSONObject stations = city.getJSONObject("stations");
                        java.util.Iterator<String> sk = stations.keys();
                        while (sk.hasNext()) {
                            String stKey = sk.next();
                            String[] parts = stKey.split("\\|", 2);
                            if (parts.length != 2) continue;
                            try {
                                db.execSQL("INSERT OR IGNORE INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                                    new Object[]{existingId, parts[0], parts[1], stations.getString(stKey), stations.getString(stKey)});
                            } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
                        }
                    }
                }
            }

            exportStationsToCache();
            refreshList();
            Toast.makeText(this, "✓ Восстановлено " + restored + " имён, " + citiesRestored + " городов",
                Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка импорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // === ДИАЛОГИ ===

    private void showCityDialog() {
        if (db == null) return;

        // Собираем регионы с количеством городов
        List<String[]> regions = new ArrayList<>();
        try {
            Cursor c = db.rawQuery(
                "SELECT region, COUNT(*) FROM cities WHERE region != '' GROUP BY region ORDER BY region", null);
            while (c.moveToNext()) {
                regions.add(new String[]{c.getString(0), String.valueOf(c.getInt(1))});
            }
            c.close();
        } catch (Exception e) { return; }

        // Кастомный layout для диалога
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CARD_BG);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);

        final android.app.AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Выбери регион")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .create();

        // Кнопка создания нового города (вверху)
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(32, 24, 32, 24);
        addRow.setBackground(btnBg(BTN_ACCENT));
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(8, 8, 8, 12);
        addRow.setLayoutParams(addLp);
        TextView addTv = new TextView(this);
        addTv.setText("➕  Создать новый город");
        addTv.setTextSize(15); addTv.setTextColor(TEXT_PRIMARY);
        addTv.setTypeface(null, Typeface.BOLD);
        addRow.addView(addTv);
        addRow.setOnClickListener(v -> { dialog.dismiss(); showAddCityDialog(); });
        container.addView(addRow);

        // Регионы как карточки
        for (String[] reg : regions) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(32, 22, 32, 22);
            row.setBackground(btnBg(BTN_BG));
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(8, 4, 8, 4);
            row.setLayoutParams(lp);

            TextView nameTv = new TextView(this);
            nameTv.setText(reg[0]);
            nameTv.setTextSize(15); nameTv.setTextColor(TEXT_PRIMARY);
            nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nameTv);

            TextView countTv = new TextView(this);
            countTv.setText(reg[1] + " городов");
            countTv.setTextSize(13); countTv.setTextColor(TEXT_SECONDARY);
            row.addView(countTv);

            TextView arrowTv = new TextView(this);
            arrowTv.setText("  ›");
            arrowTv.setTextSize(18); arrowTv.setTextColor(ACCENT);
            row.addView(arrowTv);

            final String regionName = reg[0];
            row.setOnClickListener(v -> {
                dialog.dismiss();
                showCitiesInRegion(regionName);
            });
            container.addView(row);
        }

        scroll.addView(container);
        dialog.show();
    }

    private void showCitiesInRegion(String region) {
        List<String[]> cities = new ArrayList<>();
        try {
            Cursor c = db.rawQuery(
                "SELECT c.id, c.name, COUNT(s.id) FROM cities c LEFT JOIN stations s ON s.city_id=c.id WHERE c.region=? GROUP BY c.id ORDER BY c.name",
                new String[]{region});
            while (c.moveToNext()) {
                cities.add(new String[]{
                    String.valueOf(c.getInt(0)),
                    c.getString(1),
                    String.valueOf(c.getInt(2))
                });
            }
            c.close();
        } catch (Exception e) { return; }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CARD_BG);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);

        final android.app.AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(region)
            .setView(scroll)
            .setNegativeButton("Назад", (d, w) -> showCityDialog())
            .create();

        // Кнопка добавления города в этом регионе
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(32, 22, 32, 22);
        addRow.setBackground(btnBg(BTN_ACCENT));
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(8, 8, 8, 12);
        addRow.setLayoutParams(addLp);
        TextView addTv = new TextView(this);
        addTv.setText("➕  Добавить город в " + region);
        addTv.setTextSize(14); addTv.setTextColor(TEXT_PRIMARY);
        addRow.addView(addTv);
        addRow.setOnClickListener(v -> { dialog.dismiss(); showAddCityDialogWithRegion(region); });
        container.addView(addRow);

        // Города как карточки
        for (String[] city : cities) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(32, 20, 32, 20);
            row.setBackground(btnBg(BTN_BG));
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(8, 3, 8, 3);
            row.setLayoutParams(lp);

            boolean isCurrent = Integer.parseInt(city[0]) == currentCityId;
            TextView nameTv = new TextView(this);
            nameTv.setText((isCurrent ? "✓ " : "") + city[1]);
            nameTv.setTextSize(15);
            nameTv.setTextColor(isCurrent ? ACCENT2 : TEXT_PRIMARY);
            nameTv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nameTv);

            TextView countTv = new TextView(this);
            countTv.setText(city[2] + " FM");
            countTv.setTextSize(13); countTv.setTextColor(TEXT_SECONDARY);
            row.addView(countTv);

            final int cityId = Integer.parseInt(city[0]);
            final String cityName = city[1];
            row.setOnClickListener(v -> {
                dialog.dismiss();
                currentCityId = cityId;
                currentCityName = cityName;
                db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('current_city_id',?)",
                    new Object[]{String.valueOf(cityId)});
                cityLabel.setText(cityName);
                exportStationsToCache();
                refreshList();
                Toast.makeText(this, "✓ " + cityName, Toast.LENGTH_SHORT).show();
            });
            container.addView(row);
        }

        scroll.addView(container);
        dialog.show();
    }

    private void showAddCityDialog() {
        showAddCityDialogWithRegion("");
    }

    private void showAddCityDialogWithRegion(String defaultRegion) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        EditText nameInput = new EditText(this);
        nameInput.setHint("Название города");
        layout.addView(nameInput);

        EditText regionInput = new EditText(this);
        regionInput.setHint("Регион");
        regionInput.setText(defaultRegion);
        layout.addView(regionInput);

        new AlertDialog.Builder(this)
            .setTitle("Новый город")
            .setView(layout)
            .setPositiveButton("Создать", (d, w) -> {
                String name = nameInput.getText().toString().trim();
                String region = regionInput.getText().toString().trim();
                if (name.isEmpty()) return;
                try {
                    db.execSQL("INSERT INTO cities(name,region,latitude,longitude) VALUES(?,?,0,0)",
                        new Object[]{name, region});
                    Cursor c = db.rawQuery("SELECT last_insert_rowid()", null);
                    if (c.moveToFirst()) {
                        currentCityId = c.getInt(0);
                        currentCityName = name;
                        db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('current_city_id',?)",
                            new Object[]{String.valueOf(currentCityId)});
                        cityLabel.setText(name);
                        exportStationsToCache();
                        refreshList();
                        Toast.makeText(this, "✓ " + name + " создан. Добавь станции через +", Toast.LENGTH_LONG).show();
                    }
                    c.close();
                } catch (Exception e) {
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Отмена", null).show();
    }

    private void showAddDialog() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48,24,48,24);
        EditText fi = new EditText(this); fi.setHint(showingFM ? "Частота (103.4)" : "Частота (675)");
        fi.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); l.addView(fi);
        EditText ni = new EditText(this); ni.setHint("Название"); l.addView(ni);
        new AlertDialog.Builder(this).setTitle("Добавить " + (showingFM ? "FM" : "AM")).setView(l)
            .setPositiveButton("OK", (d, w) -> {
                String f = fi.getText().toString().trim(), n = ni.getText().toString().trim();
                if (f.isEmpty() || n.isEmpty()) return;
                try { db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                    new Object[]{currentCityId, f, showingFM?"FM":"AM", n, n}); } catch (Exception e) { Log.w("RadioName", e.getMessage(), e); }
                exportStationsToCache(); refreshList();
            }).setNegativeButton("Отмена", null).show();
    }

    private void showEditDialog(String freq, String old) {
        EditText i = new EditText(this); i.setText(old); i.setPadding(48,24,48,24);
        new AlertDialog.Builder(this).setTitle(freq).setView(i)
            .setPositiveButton("OK", (d, w) -> {
                String n = i.getText().toString().trim();
                if (!n.isEmpty()) { saveStation(freq, n); exportStationsToCache(); refreshList(); }
            }).setNegativeButton("Отмена", null).show();
    }
}
