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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) try { db.close(); } catch (Exception e) {}
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
        } catch (Exception e) {}
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
            // Проверяем есть ли колонка latitude
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

            // Проверяем есть ли координаты
            c = db.rawQuery("SELECT COUNT(*) FROM cities WHERE latitude != 0", null);
            int withCoords = c.moveToFirst() ? c.getInt(0) : 0;
            c.close();

            if (withCoords == 0) {
                // Заполняем координаты
                double[][] coords = {
                    {1, 59.9343, 30.3351},    // СПб
                    {2, 55.7558, 37.6173},    // Москва
                    {3, 53.9045, 27.5615},    // Минск
                    {4, 55.7887, 49.1221},    // Казань
                    {5, 56.8389, 60.6057},    // Екатеринбург
                    {6, 56.2965, 43.9361},    // Нижний Новгород
                    {7, 55.0084, 82.9357},    // Новосибирск
                    {8, 45.0355, 38.9753},    // Краснодар
                    {9, 47.2357, 39.7015},    // Ростов-на-Дону
                    {10, 53.1959, 50.1001},   // Самара
                    {11, 51.6615, 39.2003},   // Воронеж
                    {12, 56.0153, 92.8932},   // Красноярск
                };
                for (double[] row : coords) {
                    db.execSQL("UPDATE cities SET latitude=?, longitude=? WHERE id=?",
                        new Object[]{row[1], row[2], (int) row[0]});
                }
            }
        } catch (Exception e) {}
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
        } catch (Exception e) {}
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
        } catch (Exception e) {}
    }

    private void saveStyle() {
        try { new FileWriter(STYLE_PATH).append(String.valueOf(currentDisplayStyle)).close(); }
        catch (Exception e) {}
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
            new FileWriter(CACHE_PATH).append(root.toString(2)).close();
        } catch (Exception e) {}
    }

    // === UI ===

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF1A1A2E);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        // Заголовок
        TextView title = new TextView(this);
        title.setText("📻 RadioNameHook");
        title.setTextSize(26); title.setTextColor(0xFFE0E0FF);
        title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER);
        root.addView(title);

        // Текущая частота + редактирование
        LinearLayout freqRow = new LinearLayout(this);
        freqRow.setOrientation(LinearLayout.HORIZONTAL);
        freqRow.setGravity(Gravity.CENTER_VERTICAL);
        freqRow.setPadding(0, 16, 0, 12);

        currentFreqLabel = new TextView(this);
        currentFreqLabel.setTextSize(16); currentFreqLabel.setTextColor(0xFFFFCC00);
        currentFreqLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        updateCurrentFreqLabel();
        freqRow.addView(currentFreqLabel);

        Button btnEdit = new Button(this);
        btnEdit.setText("✏ Переименовать"); btnEdit.setTextColor(Color.WHITE);
        btnEdit.setOnClickListener(v -> editCurrentStation());
        freqRow.addView(btnEdit);
        root.addView(freqRow);

        // Город
        LinearLayout cityRow = hRow(root);
        addLabel(cityRow, "Город: ");
        cityLabel = addValue(cityRow, currentCityName);
        addBtn(cityRow, "🏙 Сменить", v -> showCityDialog());

        // Стиль
        LinearLayout styleRow = hRow(root);
        addLabel(styleRow, "Виджет: ");
        final TextView styleLabel = addValue(styleRow, STYLES[Math.min(currentDisplayStyle, STYLES.length - 1)]);
        addBtn(styleRow, "🎨", v -> new AlertDialog.Builder(this)
            .setTitle("Стиль виджета").setItems(STYLES, (d, w) -> {
                currentDisplayStyle = w; saveStyle();
                styleLabel.setText(STYLES[w]);
            }).show());

        // GPS
        LinearLayout gpsRow = hRow(root);
        addLabel(gpsRow, "GPS: ");
        gpsStatusLabel = addValue(gpsRow, autoGpsEnabled ? "Вкл" : "Выкл");
        addBtn(gpsRow, "📍 GPS авто", v -> toggleGps());

        // FM / AM / Добавить
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);
        tabRow.setPadding(0, 8, 0, 0);
        btnFM = new Button(this); btnFM.setTextColor(Color.WHITE);
        btnFM.setOnClickListener(v -> { showingFM = true; refreshList(); });
        btnAM = new Button(this); btnAM.setTextColor(Color.WHITE);
        btnAM.setOnClickListener(v -> { showingFM = false; refreshList(); });
        Button btnAdd = new Button(this); btnAdd.setText("+ Добавить"); btnAdd.setTextColor(Color.WHITE);
        btnAdd.setOnClickListener(v -> showAddDialog());
        tabRow.addView(btnFM); tabRow.addView(btnAM); tabRow.addView(btnAdd);
        root.addView(tabRow);

        // Список станций
        stationListLayout = new LinearLayout(this);
        stationListLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(stationListLayout);

        // Сохранить
        Button btnSave = new Button(this);
        btnSave.setText("💾 Применить"); btnSave.setTextSize(18); btnSave.setTextColor(Color.WHITE);
        btnSave.setOnClickListener(v -> { exportStationsToCache();
            Toast.makeText(this, "✓ Обновится ~30 сек", Toast.LENGTH_SHORT).show(); });
        root.addView(btnSave);

        scroll.addView(root);
        setContentView(scroll);
        refreshList();
    }

    // UI helpers
    private LinearLayout hRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 8);
        parent.addView(row);
        return row;
    }
    private void addLabel(LinearLayout row, String text) {
        TextView tv = new TextView(this); tv.setText(text);
        tv.setTextSize(18); tv.setTextColor(0xFFCCCCCC);
        row.addView(tv);
    }
    private TextView addValue(LinearLayout row, String text) {
        TextView tv = new TextView(this); tv.setText(text);
        tv.setTextSize(16); tv.setTextColor(0xFF64B5F6);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);
        return tv;
    }
    private void addBtn(LinearLayout row, String text, View.OnClickListener l) {
        Button btn = new Button(this); btn.setText(text); btn.setTextColor(Color.WHITE);
        btn.setOnClickListener(l); row.addView(btn);
    }

    // === GPS ===

    private void toggleGps() {
        autoGpsEnabled = !autoGpsEnabled;
        if (autoGpsEnabled) {
            gpsStatusLabel.setText("Ищу спутники...");
            gpsStatusLabel.setTextColor(0xFFFFCC00);
            requestGpsUpdate();
        } else {
            gpsStatusLabel.setText("Выкл");
            gpsStatusLabel.setTextColor(0xFF64B5F6);
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
                gpsStatusLabel.setTextColor(0xFF4CAF50);
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
            gpsStatusLabel.setTextColor(0xFFFF5252);
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
                    gpsStatusLabel.setTextColor(0xFFFF5252);
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
                    gpsStatusLabel.setTextColor(0xFF4CAF50);
                    Toast.makeText(this, "📍 Переключено: " + fNearestName, Toast.LENGTH_SHORT).show();
                } else {
                    gpsStatusLabel.setText("✓ " + fNearestName + " (" + (int) fMinDist + " км)");
                    gpsStatusLabel.setTextColor(0xFF4CAF50);
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                gpsStatusLabel.setText("Ошибка: " + e.getMessage());
                gpsStatusLabel.setTextColor(0xFFFF5252);
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
        try {
            File f = new File(FREQ_PATH);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String freq = r.readLine(); r.close();
                if (freq != null && !freq.isEmpty()) {
                    String name = findStationName(freq);
                    currentFreqLabel.setText(name != null ?
                        "▶ FM " + freq + " — " + name :
                        "▶ FM " + freq + " (нет в базе)");
                    return;
                }
            }
        } catch (Exception e) {}
        currentFreqLabel.setText("▶ Включи радио");
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
        } catch (Exception e) {}
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
        } catch (Exception e) {}
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
                row.setPadding(8, 10, 8, 10);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView fv = new TextView(this); fv.setText(freq);
                fv.setTextSize(15); fv.setTextColor(0xFF64B5F6);
                fv.setTypeface(null, Typeface.BOLD); fv.setMinWidth(160);
                row.addView(fv);

                TextView nv = new TextView(this);
                nv.setText(dname + (custom ? " ✎" : ""));
                nv.setTextSize(15); nv.setTextColor(custom ? 0xFFFFCC00 : 0xFFE0E0E0);
                nv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                nv.setPadding(12, 0, 12, 0);
                row.addView(nv);

                Button be = new Button(this); be.setText("✏"); be.setTextSize(13);
                final String ef = freq, en = dname;
                be.setOnClickListener(v -> showEditDialog(ef, en));
                row.addView(be);

                stationListLayout.addView(row);
                View div = new View(this); div.setBackgroundColor(0xFF333355);
                div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                stationListLayout.addView(div);
            }
            c.close();
        } catch (Exception e) {}
    }

    // === ДИАЛОГИ ===

    private void showCityDialog() {
        if (db == null) return;
        List<String> names = new ArrayList<>(); List<Integer> ids = new ArrayList<>();
        try {
            Cursor c = db.rawQuery("SELECT id,name,region FROM cities ORDER BY region,name", null);
            while (c.moveToNext()) {
                ids.add(c.getInt(0));
                String r = c.getString(2);
                names.add(c.getString(1) + (r.isEmpty() ? "" : " (" + r + ")"));
            }
            c.close();
        } catch (Exception e) { return; }

        new AlertDialog.Builder(this).setTitle("Город")
            .setItems(names.toArray(new String[0]), (d, w) -> {
                currentCityId = ids.get(w);
                db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('current_city_id',?)",
                    new Object[]{String.valueOf(currentCityId)});
                try {
                    Cursor c2 = db.rawQuery("SELECT name FROM cities WHERE id=?",
                        new String[]{String.valueOf(currentCityId)});
                    if (c2.moveToFirst()) currentCityName = c2.getString(0); c2.close();
                } catch (Exception e) {}
                cityLabel.setText(currentCityName);
                exportStationsToCache(); refreshList();
            }).show();
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
                    new Object[]{currentCityId, f, showingFM?"FM":"AM", n, n}); } catch (Exception e) {}
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
