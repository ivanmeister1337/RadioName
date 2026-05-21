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
    private static final String JSON_PATH = Environment.getExternalStorageDirectory() + "/RadioNames/stations.json";
    private LinearLayout stationListLayout;
    private TreeMap<String, String> fmStations = new TreeMap<>((a, b) -> {
        try { return Double.compare(Double.parseDouble(a), Double.parseDouble(b)); } catch (Exception e) { return a.compareTo(b); }
    });
    private TreeMap<String, String> amStations = new TreeMap<>((a, b) -> {
        try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch (Exception e) { return a.compareTo(b); }
    });
    private boolean showingFM = true;

    private static boolean isModuleActive() { return false; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadJson();

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

        // Статус модуля
        TextView status = new TextView(this);
        if (isModuleActive()) {
            status.setText("✓ Модуль активен");
            status.setTextColor(0xFF4CAF50);
        } else {
            status.setText("✗ Модуль не активен — включи в LSPosed");
            status.setTextColor(0xFFFF5252);
        }
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, 24);
        root.addView(status);

        // Кнопки FM / AM
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);

        Button btnFM = new Button(this);
        btnFM.setText("FM (" + fmStations.size() + ")");
        btnFM.setTextColor(Color.WHITE);
        btnFM.setOnClickListener(v -> { showingFM = true; refreshList(); btnFM.setText("FM (" + fmStations.size() + ")"); });

        Button btnAM = new Button(this);
        btnAM.setText("AM (" + amStations.size() + ")");
        btnAM.setTextColor(Color.WHITE);
        btnAM.setOnClickListener(v -> { showingFM = false; refreshList(); btnAM.setText("AM (" + amStations.size() + ")"); });

        Button btnAdd = new Button(this);
        btnAdd.setText("+ Добавить");
        btnAdd.setTextColor(Color.WHITE);
        btnAdd.setOnClickListener(v -> showAddDialog());

        tabRow.addView(btnFM);
        tabRow.addView(btnAM);
        tabRow.addView(btnAdd);
        root.addView(tabRow);

        // Счётчик
        TextView countLabel = new TextView(this);
        countLabel.setTextSize(14);
        countLabel.setTextColor(0xFF888888);
        countLabel.setPadding(0, 16, 0, 8);
        countLabel.setText("Файл: " + JSON_PATH);
        root.addView(countLabel);

        // Список станций
        stationListLayout = new LinearLayout(this);
        stationListLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(stationListLayout);

        // Кнопка сохранения
        Button btnSave = new Button(this);
        btnSave.setText("💾 Сохранить и применить");
        btnSave.setTextSize(18);
        btnSave.setTextColor(Color.WHITE);
        btnSave.setPadding(0, 24, 0, 24);
        btnSave.setOnClickListener(v -> {
            saveJson();
            Toast.makeText(this, "✓ Сохранено! Имена обновятся в течение 30 сек.", Toast.LENGTH_LONG).show();
        });
        root.addView(btnSave);

        scroll.addView(root);
        setContentView(scroll);
        refreshList();
    }

    private void refreshList() {
        stationListLayout.removeAllViews();
        Map<String, String> stations = showingFM ? fmStations : amStations;
        String unit = showingFM ? " МГц" : " кГц";

        for (Map.Entry<String, String> entry : stations.entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);
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

            // Кнопка редактирования
            Button btnEdit = new Button(this);
            btnEdit.setText("✏");
            btnEdit.setTextSize(14);
            btnEdit.setOnClickListener(v -> showEditDialog(entry.getKey(), entry.getValue()));
            row.addView(btnEdit);

            // Кнопка удаления
            Button btnDel = new Button(this);
            btnDel.setText("✗");
            btnDel.setTextSize(14);
            btnDel.setTextColor(0xFFFF5252);
            btnDel.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Удалить?")
                    .setMessage(entry.getKey() + " — " + entry.getValue())
                    .setPositiveButton("Да", (d, w) -> {
                        if (showingFM) fmStations.remove(entry.getKey());
                        else amStations.remove(entry.getKey());
                        refreshList();
                    })
                    .setNegativeButton("Нет", null).show();
            });
            row.addView(btnDel);

            // Разделитель
            View divider = new View(this);
            divider.setBackgroundColor(0xFF333355);
            divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

            stationListLayout.addView(row);
            stationListLayout.addView(divider);
        }
    }

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
            .setTitle("Добавить станцию (" + (showingFM ? "FM" : "AM") + ")")
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
            .setTitle("Изменить: " + freq)
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

    private void loadJson() {
        try {
            File file = new File(JSON_PATH);
            if (!file.exists()) return;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
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
        } catch (Exception e) { e.printStackTrace(); }
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

            File dir = new File(JSON_PATH).getParentFile();
            if (!dir.exists()) dir.mkdirs();

            try (FileWriter w = new FileWriter(JSON_PATH)) {
                w.write(root.toString(2));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
