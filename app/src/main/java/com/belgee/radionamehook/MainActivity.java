package com.belgee.radionamehook;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
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
    private TextView cityLabel, currentFreqLabel;
    private Button btnFM, btnAM;
    private boolean showingFM = true;
    private int currentCityId = 1;
    private String currentCityName = "Санкт-Петербург";
    private int currentDisplayStyle = 0;

    private static final String[] STYLES = {
        "Радио Маяк",
        "102.0 Радио Маяк",
        "Радио Маяк 102.0",
        "102.0 | Радио Маяк",
        "Радио Маяк | 102.0",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }, 1);
            }
            File dir = new File(DIR_PATH);
            if (!dir.exists()) dir.mkdirs();

            initDatabase();
            loadSettings();
            loadStyle();
            buildUI();
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("Ошибка: " + t.getMessage() + "\n\n" + android.util.Log.getStackTraceString(t));
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
        if (db != null) db.close();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == 1) { initDatabase(); loadSettings(); if (stationListLayout != null) refreshList(); }
    }

    // === DATABASE ===

    private void initDatabase() {
        try {
            File dbFile = new File(DB_PATH);
            if (!dbFile.exists()) {
                // Копируем из assets
                File dir = new File(DIR_PATH);
                if (!dir.exists()) dir.mkdirs();
                InputStream is = getAssets().open("radio.db");
                OutputStream os = new FileOutputStream(dbFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                os.close();
                is.close();
            }
            db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Exception e) {
            Toast.makeText(this, "DB error: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        } catch (Exception e) { }
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
        } catch (Exception e) { }
    }

    private void saveStyle() {
        try {
            try (FileWriter w = new FileWriter(STYLE_PATH)) { w.write(String.valueOf(currentDisplayStyle)); }
        } catch (Exception e) { }
    }

    /** Экспортирует станции текущего города в JSON для Xposed модуля */
    private void exportStationsToCache() {
        if (db == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject fm = new JSONObject();
            JSONObject am = new JSONObject();
            Cursor c = db.rawQuery(
                "SELECT freq, band, COALESCE(user_name, name) as display_name FROM stations WHERE city_id=?",
                new String[]{String.valueOf(currentCityId)});
            while (c.moveToNext()) {
                String freq = c.getString(0);
                String band = c.getString(1);
                String name = c.getString(2);
                if ("FM".equals(band)) fm.put(freq, name);
                else am.put(freq, name);
            }
            c.close();
            root.put("fm", fm);
            root.put("am", am);
            try (FileWriter w = new FileWriter(CACHE_PATH)) { w.write(root.toString(2)); }
        } catch (Exception e) { }
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
        title.setTextSize(26);
        title.setTextColor(0xFFE0E0FF);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // === Текущая частота + кнопка редактирования ===
        LinearLayout freqRow = new LinearLayout(this);
        freqRow.setOrientation(LinearLayout.HORIZONTAL);
        freqRow.setGravity(Gravity.CENTER_VERTICAL);
        freqRow.setPadding(0, 16, 0, 16);

        currentFreqLabel = new TextView(this);
        currentFreqLabel.setTextSize(16);
        currentFreqLabel.setTextColor(0xFFFFCC00);
        currentFreqLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        updateCurrentFreqLabel();
        freqRow.addView(currentFreqLabel);

        Button btnEditCurrent = new Button(this);
        btnEditCurrent.setText("✏ Переименовать");
        btnEditCurrent.setTextColor(Color.WHITE);
        btnEditCurrent.setOnClickListener(v -> editCurrentStation());
        freqRow.addView(btnEditCurrent);
        root.addView(freqRow);

        // === Город ===
        LinearLayout cityRow = new LinearLayout(this);
        cityRow.setOrientation(LinearLayout.HORIZONTAL);
        cityRow.setGravity(Gravity.CENTER_VERTICAL);
        cityRow.setPadding(0, 0, 0, 8);

        TextView cityText = new TextView(this);
        cityText.setText("Город: ");
        cityText.setTextSize(18);
        cityText.setTextColor(0xFFCCCCCC);
        cityRow.addView(cityText);

        cityLabel = new TextView(this);
        cityLabel.setText(currentCityName);
        cityLabel.setTextSize(18);
        cityLabel.setTextColor(0xFF64B5F6);
        cityLabel.setTypeface(null, Typeface.BOLD);
        cityLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cityRow.addView(cityLabel);

        Button btnCity = new Button(this);
        btnCity.setText("🏙 Сменить");
        btnCity.setTextColor(Color.WHITE);
        btnCity.setOnClickListener(v -> showCityDialog());
        cityRow.addView(btnCity);
        root.addView(cityRow);

        // === Стиль ===
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setGravity(Gravity.CENTER_VERTICAL);
        styleRow.setPadding(0, 0, 0, 8);

        TextView styleText = new TextView(this);
        styleText.setText("Виджет: ");
        styleText.setTextSize(18);
        styleText.setTextColor(0xFFCCCCCC);
        styleRow.addView(styleText);

        final TextView styleLabel = new TextView(this);
        styleLabel.setText(STYLES[currentDisplayStyle < STYLES.length ? currentDisplayStyle : 0]);
        styleLabel.setTextSize(16);
        styleLabel.setTextColor(0xFF64B5F6);
        styleLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        styleRow.addView(styleLabel);

        Button btnStyle = new Button(this);
        btnStyle.setText("🎨");
        btnStyle.setTextColor(Color.WHITE);
        btnStyle.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Стиль виджета")
            .setItems(STYLES, (d, w) -> {
                currentDisplayStyle = w;
                saveStyle();
                styleLabel.setText(STYLES[w]);
            }).show());
        styleRow.addView(btnStyle);
        root.addView(styleRow);

        // === FM / AM / Добавить ===
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);

        btnFM = new Button(this); btnFM.setTextColor(Color.WHITE);
        btnFM.setOnClickListener(v -> { showingFM = true; refreshList(); });
        btnAM = new Button(this); btnAM.setTextColor(Color.WHITE);
        btnAM.setOnClickListener(v -> { showingFM = false; refreshList(); });
        Button btnAdd = new Button(this);
        btnAdd.setText("+ Добавить"); btnAdd.setTextColor(Color.WHITE);
        btnAdd.setOnClickListener(v -> showAddDialog());

        tabRow.addView(btnFM); tabRow.addView(btnAM); tabRow.addView(btnAdd);
        root.addView(tabRow);

        // === Список станций ===
        stationListLayout = new LinearLayout(this);
        stationListLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(stationListLayout);

        // === Сохранить ===
        Button btnSave = new Button(this);
        btnSave.setText("💾 Применить изменения");
        btnSave.setTextSize(18); btnSave.setTextColor(Color.WHITE);
        btnSave.setOnClickListener(v -> {
            exportStationsToCache();
            Toast.makeText(this, "✓ Обновится через ~30 сек", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);

        scroll.addView(root);
        setContentView(scroll);
        refreshList();
    }

    // === ТЕКУЩАЯ ЧАСТОТА ===

    private void updateCurrentFreqLabel() {
        try {
            File f = new File(FREQ_PATH);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String freq = r.readLine();
                r.close();
                if (freq != null && !freq.isEmpty()) {
                    String name = findStationName(freq);
                    if (name != null) {
                        currentFreqLabel.setText("▶ FM " + freq + " — " + name);
                    } else {
                        currentFreqLabel.setText("▶ FM " + freq + " (нет в базе)");
                    }
                    return;
                }
            }
        } catch (Exception e) { }
        currentFreqLabel.setText("▶ Включи радио для определения частоты");
    }

    private String findStationName(String freq) {
        if (db == null) return null;
        try {
            Cursor c = db.rawQuery(
                "SELECT COALESCE(user_name, name) FROM stations WHERE city_id=? AND freq=?",
                new String[]{String.valueOf(currentCityId), freq});
            if (c.moveToFirst()) { String n = c.getString(0); c.close(); return n; }
            c.close();
        } catch (Exception e) { }
        return null;
    }

    private void editCurrentStation() {
        try {
            File f = new File(FREQ_PATH);
            if (!f.exists()) {
                Toast.makeText(this, "Включи радио — частота определится автоматически", Toast.LENGTH_SHORT).show();
                return;
            }
            BufferedReader r = new BufferedReader(new FileReader(f));
            String freq = r.readLine();
            r.close();
            if (freq == null || freq.isEmpty()) return;

            String currentName = findStationName(freq);
            EditText input = new EditText(this);
            input.setText(currentName != null ? currentName : "");
            input.setHint("Название станции");
            input.setPadding(48, 24, 48, 24);

            new AlertDialog.Builder(this)
                .setTitle("FM " + freq)
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    saveStationName(freq, name);
                    exportStationsToCache();
                    updateCurrentFreqLabel();
                    refreshList();
                    Toast.makeText(this, "✓ " + freq + " → " + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null).show();
        } catch (Exception e) { }
    }

    private void saveStationName(String freq, String name) {
        if (db == null) return;
        try {
            // Проверяем есть ли станция
            Cursor c = db.rawQuery("SELECT id FROM stations WHERE city_id=? AND freq=?",
                new String[]{String.valueOf(currentCityId), freq});
            if (c.moveToFirst()) {
                int id = c.getInt(0);
                c.close();
                db.execSQL("UPDATE stations SET user_name=? WHERE id=?", new Object[]{name, id});
            } else {
                c.close();
                db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                    new Object[]{currentCityId, freq, "FM", name, name});
            }
        } catch (Exception e) { }
    }

    // === СПИСОК СТАНЦИЙ ===

    private void refreshList() {
        if (db == null || stationListLayout == null) return;
        stationListLayout.removeAllViews();
        String band = showingFM ? "FM" : "AM";

        int fmCount = 0, amCount = 0;
        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM stations WHERE city_id=? AND band='FM'", new String[]{String.valueOf(currentCityId)});
            if (c.moveToFirst()) fmCount = c.getInt(0); c.close();
            c = db.rawQuery("SELECT COUNT(*) FROM stations WHERE city_id=? AND band='AM'", new String[]{String.valueOf(currentCityId)});
            if (c.moveToFirst()) amCount = c.getInt(0); c.close();
        } catch (Exception e) { }
        btnFM.setText("FM (" + fmCount + ")");
        btnAM.setText("AM (" + amCount + ")");

        try {
            Cursor c = db.rawQuery(
                "SELECT id, freq, COALESCE(user_name, name) as display_name, user_name FROM stations WHERE city_id=? AND band=? ORDER BY CAST(freq AS REAL)",
                new String[]{String.valueOf(currentCityId), band});
            while (c.moveToNext()) {
                int stId = c.getInt(0);
                String freq = c.getString(1);
                String displayName = c.getString(2);
                boolean isCustom = !c.isNull(3);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(8, 10, 8, 10);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView freqView = new TextView(this);
                freqView.setText(freq);
                freqView.setTextSize(15);
                freqView.setTextColor(0xFF64B5F6);
                freqView.setTypeface(null, Typeface.BOLD);
                freqView.setMinWidth(160);
                row.addView(freqView);

                TextView nameView = new TextView(this);
                nameView.setText(displayName + (isCustom ? " ✎" : ""));
                nameView.setTextSize(15);
                nameView.setTextColor(isCustom ? 0xFFFFCC00 : 0xFFE0E0E0);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                nameView.setPadding(12, 0, 12, 0);
                row.addView(nameView);

                Button btnEdit = new Button(this);
                btnEdit.setText("✏"); btnEdit.setTextSize(13);
                final String editFreq = freq;
                final String editName = displayName;
                btnEdit.setOnClickListener(v -> showEditDialog(editFreq, editName));
                row.addView(btnEdit);

                View divider = new View(this);
                divider.setBackgroundColor(0xFF333355);
                divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

                stationListLayout.addView(row);
                stationListLayout.addView(divider);
            }
            c.close();
        } catch (Exception e) { }
    }

    // === ДИАЛОГИ ===

    private void showCityDialog() {
        if (db == null) return;
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        try {
            Cursor c = db.rawQuery("SELECT id, name, region FROM cities ORDER BY region, name", null);
            while (c.moveToNext()) {
                ids.add(c.getInt(0));
                String region = c.getString(2);
                names.add(c.getString(1) + (region.isEmpty() ? "" : " (" + region + ")"));
            }
            c.close();
        } catch (Exception e) { return; }

        new AlertDialog.Builder(this)
            .setTitle("Выбери город")
            .setItems(names.toArray(new String[0]), (d, w) -> {
                currentCityId = ids.get(w);
                db.execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES('current_city_id',?)",
                    new Object[]{String.valueOf(currentCityId)});
                Cursor c2 = db.rawQuery("SELECT name FROM cities WHERE id=?", new String[]{String.valueOf(currentCityId)});
                if (c2.moveToFirst()) currentCityName = c2.getString(0);
                c2.close();
                cityLabel.setText(currentCityName);
                exportStationsToCache();
                refreshList();
                Toast.makeText(this, "✓ " + currentCityName, Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void showAddDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        EditText freqInput = new EditText(this);
        freqInput.setHint(showingFM ? "Частота (103.4)" : "Частота (675)");
        freqInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(freqInput);
        EditText nameInput = new EditText(this);
        nameInput.setHint("Название станции");
        layout.addView(nameInput);

        new AlertDialog.Builder(this)
            .setTitle("Добавить " + (showingFM ? "FM" : "AM"))
            .setView(layout)
            .setPositiveButton("Добавить", (d, w) -> {
                String freq = freqInput.getText().toString().trim();
                String name = nameInput.getText().toString().trim();
                if (freq.isEmpty() || name.isEmpty()) return;
                try {
                    db.execSQL("INSERT INTO stations(city_id,freq,band,name,user_name) VALUES(?,?,?,?,?)",
                        new Object[]{currentCityId, freq, showingFM ? "FM" : "AM", name, name});
                    exportStationsToCache();
                    refreshList();
                } catch (Exception e) { }
            }).setNegativeButton("Отмена", null).show();
    }

    private void showEditDialog(String freq, String oldName) {
        EditText input = new EditText(this);
        input.setText(oldName); input.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this)
            .setTitle("Изменить: " + freq)
            .setView(input)
            .setPositiveButton("OK", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                saveStationName(freq, name);
                exportStationsToCache();
                refreshList();
            }).setNegativeButton("Отмена", null).show();
    }
}
