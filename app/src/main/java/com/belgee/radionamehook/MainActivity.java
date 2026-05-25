package com.belgee.radionamehook;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final String JSON_PATH = DIR_PATH + "/stations.json";
    private static final String CITY_PREF = DIR_PATH + "/current_city.txt";

    private LinearLayout stationListLayout;
    private TextView cityLabel;
    private Button btnFM, btnAM;
    private TreeMap<String, String> fmStations = new TreeMap<>();
    private TreeMap<String, String> amStations = new TreeMap<>();
    private boolean showingFM = true;
    private String currentCity = "Мои станции";
    private int currentDisplayStyle = 0;
    private static final String STYLE_PATH = DIR_PATH + "/style.txt";

    // Список встроенных городов (filename без .json → отображаемое имя)
    private final String[][] CITIES = {
        {"spb", "Санкт-Петербург"},
        {"moscow", "Москва"},
        {"minsk", "Минск"},
        {"kazan", "Казань"},
        {"ekaterinburg", "Екатеринбург"},
        {"custom", "Мои станции (пустая)"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            loadCurrentCity();
            loadJson();
            loadStyle();
            buildUI();
        } catch (Throwable t) {
            // Fallback — показать ошибку
            TextView tv = new TextView(this);
            tv.setText("Ошибка: " + t.getMessage() + "\n\n" + android.util.Log.getStackTraceString(t));
            tv.setTextColor(0xFFFF0000);
            tv.setPadding(32, 32, 32, 32);
            setContentView(tv);
        }
    }

    private void buildUI() {

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF1A1A2E);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        // Заголовок
        TextView title = new TextView(this);
        title.setText("📻 RadioNameHook");
        title.setTextSize(28);
        title.setTextColor(0xFFE0E0FF);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 16, 0, 8);
        root.addView(title);

        // Статус модуля (метод isModuleActive подменяется Xposed когда модуль активен)
        TextView status = new TextView(this);
        status.setText("📻 Редактор станций");
        status.setTextColor(0xFF4CAF50);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, 16);
        root.addView(status);

        // === Выбор города ===
        LinearLayout cityRow = new LinearLayout(this);
        cityRow.setOrientation(LinearLayout.HORIZONTAL);
        cityRow.setGravity(Gravity.CENTER_VERTICAL);
        cityRow.setPadding(0, 0, 0, 16);

        TextView cityText = new TextView(this);
        cityText.setText("Город: ");
        cityText.setTextSize(18);
        cityText.setTextColor(0xFFCCCCCC);
        cityRow.addView(cityText);

        cityLabel = new TextView(this);
        cityLabel.setText(currentCity);
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

        // === Стиль отображения на виджете ===
        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        styleRow.setGravity(Gravity.CENTER_VERTICAL);
        styleRow.setPadding(0, 0, 0, 16);

        TextView styleText = new TextView(this);
        styleText.setText("Виджет: ");
        styleText.setTextSize(18);
        styleText.setTextColor(0xFFCCCCCC);
        styleRow.addView(styleText);

        final String[] STYLES = {
            "Радио Маяк",
            "102.0 Радио Маяк",
            "Радио Маяк 102.0",
            "102.0 | Радио Маяк",
            "Радио Маяк | 102.0",
        };

        final TextView styleLabel = new TextView(this);
        int currentStyle = currentDisplayStyle;
        styleLabel.setText(STYLES[currentStyle < STYLES.length ? currentStyle : 0]);
        styleLabel.setTextSize(16);
        styleLabel.setTextColor(0xFF64B5F6);
        styleLabel.setTypeface(null, Typeface.BOLD);
        styleLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        styleLabel.setPadding(8, 0, 8, 0);
        styleRow.addView(styleLabel);

        Button btnStyle = new Button(this);
        btnStyle.setText("🎨 Стиль");
        btnStyle.setTextColor(Color.WHITE);
        btnStyle.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Стиль виджета")
                .setItems(STYLES, (d, which) -> {
                    currentDisplayStyle = which;
                    saveStyle();
                    styleLabel.setText(STYLES[which]);
                    Toast.makeText(this, "✓ Стиль: " + STYLES[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
        });
        styleRow.addView(btnStyle);

        root.addView(styleRow);

        // === Кнопки FM / AM / Добавить ===
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);

        btnFM = new Button(this);
        btnFM.setTextColor(Color.WHITE);
        btnFM.setOnClickListener(v -> { showingFM = true; refreshList(); });

        btnAM = new Button(this);
        btnAM.setTextColor(Color.WHITE);
        btnAM.setOnClickListener(v -> { showingFM = false; refreshList(); });

        Button btnAdd = new Button(this);
        btnAdd.setText("+ Добавить");
        btnAdd.setTextColor(Color.WHITE);
        btnAdd.setOnClickListener(v -> showAddDialog());

        tabRow.addView(btnFM);
        tabRow.addView(btnAM);
        tabRow.addView(btnAdd);
        root.addView(tabRow);

        // Список станций
        stationListLayout = new LinearLayout(this);
        stationListLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(stationListLayout);

        // Кнопка сохранения
        Button btnSave = new Button(this);
        btnSave.setText("💾 Сохранить");
        btnSave.setTextSize(18);
        btnSave.setTextColor(Color.WHITE);
        btnSave.setPadding(0, 24, 0, 24);
        btnSave.setOnClickListener(v -> {
            saveJson();
            Toast.makeText(this, "✓ Сохранено! Обновится через ~30 сек.", Toast.LENGTH_LONG).show();
        });
        root.addView(btnSave);

        scroll.addView(root);
        setContentView(scroll);
        refreshList();
    }

    // === ВЫБОР ГОРОДА ===

    private void showCityDialog() {
        String[] names = new String[CITIES.length];
        for (int i = 0; i < CITIES.length; i++) names[i] = CITIES[i][1];

        new AlertDialog.Builder(this)
            .setTitle("Выбери город")
            .setItems(names, (d, which) -> {
                String filename = CITIES[which][0];
                String cityName = CITIES[which][1];
                loadCityFromAssets(filename);
                currentCity = cityName;
                cityLabel.setText(currentCity);
                saveCurrentCity();
                saveJson();
                refreshList();
                Toast.makeText(this, "✓ Загружен: " + cityName + " (" + fmStations.size() + " FM)", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void loadCityFromAssets(String filename) {
        try {
            InputStream is = getAssets().open("cities/" + filename + ".json");
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONObject root = new JSONObject(sb.toString());
            fmStations.clear();
            amStations.clear();

            if (root.has("city")) currentCity = root.getString("city");
            if (root.has("fm")) {
                JSONObject fm = root.getJSONObject("fm");
                Iterator<String> keys = fm.keys();
                while (keys.hasNext()) { String k = keys.next(); fmStations.put(k, fm.getString(k)); }
            }
            if (root.has("am")) {
                JSONObject am = root.getJSONObject("am");
                Iterator<String> keys = am.keys();
                while (keys.hasNext()) { String k = keys.next(); amStations.put(k, am.getString(k)); }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка загрузки: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveCurrentCity() {
        try {
            File dir = new File(DIR_PATH);
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(CITY_PREF)) { w.write(currentCity); }
        } catch (Exception e) { }
    }

    private void loadCurrentCity() {
        try {
            File f = new File(CITY_PREF);
            if (!f.exists()) return;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                currentCity = r.readLine();
            }
        } catch (Exception e) { }
    }

    // === СПИСОК СТАНЦИЙ ===

    private void refreshList() {
        stationListLayout.removeAllViews();
        Map<String, String> stations = showingFM ? fmStations : amStations;
        String unit = showingFM ? " МГц" : " кГц";

        btnFM.setText("FM (" + fmStations.size() + ")");
        btnAM.setText("AM (" + amStations.size() + ")");

        if (stations.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(showingFM ? "Нет FM станций" : "Нет AM станций");
            empty.setTextSize(16);
            empty.setTextColor(0xFF888888);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 48, 0, 48);
            stationListLayout.addView(empty);
            return;
        }

        for (Map.Entry<String, String> entry : stations.entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(8, 12, 8, 12);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView freqView = new TextView(this);
            freqView.setText(entry.getKey() + unit);
            freqView.setTextSize(16);
            freqView.setTextColor(0xFF64B5F6);
            freqView.setTypeface(null, Typeface.BOLD);
            freqView.setMinWidth(200);
            row.addView(freqView);

            TextView nameView = new TextView(this);
            nameView.setText(entry.getValue());
            nameView.setTextSize(16);
            nameView.setTextColor(0xFFE0E0E0);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            nameView.setPadding(16, 0, 16, 0);
            row.addView(nameView);

            Button btnEdit = new Button(this);
            btnEdit.setText("✏");
            btnEdit.setTextSize(14);
            btnEdit.setOnClickListener(v -> showEditDialog(entry.getKey(), entry.getValue()));
            row.addView(btnEdit);

            Button btnDel = new Button(this);
            btnDel.setText("✗");
            btnDel.setTextSize(14);
            btnDel.setTextColor(0xFFFF5252);
            final String delKey = entry.getKey();
            final String delName = entry.getValue();
            btnDel.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Удалить?")
                    .setMessage(delKey + " — " + delName)
                    .setPositiveButton("Да", (d, w) -> {
                        if (showingFM) fmStations.remove(delKey);
                        else amStations.remove(delKey);
                        refreshList();
                    })
                    .setNegativeButton("Нет", null).show();
            });
            row.addView(btnDel);

            View divider = new View(this);
            divider.setBackgroundColor(0xFF333355);
            divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

            stationListLayout.addView(row);
            stationListLayout.addView(divider);
        }
    }

    // === ДИАЛОГИ ===

    private void showAddDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        EditText freqInput = new EditText(this);
        freqInput.setHint(showingFM ? "Частота (напр. 103.4)" : "Частота (напр. 675)");
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
                if (!freq.isEmpty() && !name.isEmpty()) {
                    if (showingFM) fmStations.put(freq, name);
                    else amStations.put(freq, name);
                    refreshList();
                }
            })
            .setNegativeButton("Отмена", null).show();
    }

    private void showEditDialog(String freq, String oldName) {
        EditText nameInput = new EditText(this);
        nameInput.setText(oldName);
        nameInput.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
            .setTitle("Изменить: " + freq + " МГц")
            .setView(nameInput)
            .setPositiveButton("OK", (d, w) -> {
                String name = nameInput.getText().toString().trim();
                if (!name.isEmpty()) {
                    if (showingFM) fmStations.put(freq, name);
                    else amStations.put(freq, name);
                    refreshList();
                }
            })
            .setNegativeButton("Отмена", null).show();
    }

    // === JSON I/O ===

    private void loadJson() {
        try {
            File file = new File(JSON_PATH);
            if (!file.exists()) {
                // Первый запуск — загружаем СПб из assets
                loadCityFromAssets("spb");
                saveJson();
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            fmStations.clear();
            amStations.clear();
            if (root.has("fm")) {
                JSONObject fm = root.getJSONObject("fm");
                Iterator<String> keys = fm.keys();
                while (keys.hasNext()) { String k = keys.next(); fmStations.put(k, fm.getString(k)); }
            }
            if (root.has("am")) {
                JSONObject am = root.getJSONObject("am");
                Iterator<String> keys = am.keys();
                while (keys.hasNext()) { String k = keys.next(); amStations.put(k, am.getString(k)); }
            }
        } catch (Exception e) { }
    }

    private void saveJson() {
        try {
            JSONObject root = new JSONObject();
            JSONObject fm = new JSONObject();
            for (Map.Entry<String, String> e : fmStations.entrySet()) fm.put(e.getKey(), e.getValue());
            JSONObject am = new JSONObject();
            for (Map.Entry<String, String> e : amStations.entrySet()) am.put(e.getKey(), e.getValue());
            root.put("fm", fm);
            root.put("am", am);

            File dir = new File(DIR_PATH);
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(JSON_PATH)) {
                w.write(root.toString(2));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
            File dir = new File(DIR_PATH);
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(STYLE_PATH)) {
                w.write(String.valueOf(currentDisplayStyle));
            }
        } catch (Exception e) { }
    }
}
