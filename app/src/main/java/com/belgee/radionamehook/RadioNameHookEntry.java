package com.belgee.radionamehook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RadioNameHook — LSPosed модуль для отображения названий радиостанций на Belgee X50.
 * 
 * Финальная чистая версия. Подменяет имена в:
 * 1. Полноэкранном радио (RadioInfo.getName)
 * 2. Списке станций (hide_radio_name flag)
 * 3. Избранном (EventBus/RadioCollectListEvent)
 * 4. MediaCenter AIDL (getRadioStationName)
 * 5. DIM API (getTitle)
 * 6. Виджете главного экрана (RadioRulerView.pointerValueStr)
 */
public class RadioNameHookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "RadioNameHook";
    private static final Pattern FREQ_PATTERN = Pattern.compile("(\\d{2,3}\\.\\d)");

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean isNSMedia = "com.ecarx.multimedia".equals(lpparam.packageName);
        boolean isWidget = "ecarx.xsf.widget".equals(lpparam.packageName);
        boolean isMediaCenter = "ecarx.xsf.mediacenter".equals(lpparam.packageName);
        boolean isLauncher = "ecarx.launcher3".equals(lpparam.packageName);

        if (!isNSMedia && !isWidget && !isMediaCenter && !isLauncher) return;

        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName);
        StationsDatabase.init();

        if (isNSMedia) {
            hookRadioInfoGetName(lpparam);
            hookHideRadioNameFlag(lpparam);
            scanAndHookMediaClasses(lpparam);
            hookRadioCollectListEvent(lpparam);
            hookLongPressToEdit(lpparam);
        }

        if (isWidget) {
            hookWidgetHolder(lpparam);
            hookUpdateContentTitle(lpparam);
        }

        if (isMediaCenter) {
            hookMediaCenterRadioStationName(lpparam);
            hookDimApiTitle(lpparam);
        }

        if (isLauncher) {
            hookLauncherWidgetUpdate(lpparam);
        }
    }

    // ==================== EXTRACT FREQ HELPER ====================

    private static String extractFreq(String text) {
        if (text == null) return null;
        Matcher m = FREQ_PATTERN.matcher(text);
        if (m.find()) return m.group(1);
        // Попробуем целое число (AM)
        String cleaned = text.replaceAll("[^0-9.]", "");
        if (!cleaned.isEmpty()) return cleaned;
        return null;
    }

    // ==================== 1. RADIO INFO GET NAME (полноэкранное радио) ====================

    private void hookRadioInfoGetName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = lpparam.classLoader.loadClass("com.ecarx.common.bean.radio.RadioInfo");
            XposedHelpers.findAndHookMethod(cls, "getName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String name = (String) param.getResult();
                        if (name != null && !name.isEmpty()) return;
                        int freq = (Integer) XposedHelpers.getObjectField(param.thisObject, "frequency");
                        String found = StationsDatabase.findName(String.valueOf(freq));
                        if (found != null) {
                            param.setResult(found);
                        }
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.log(TAG + ": ✓ hooked RadioInfo.getName");
        } catch (Throwable t) { }
    }

    // ==================== 2. HIDE RADIO NAME FLAG (списки станций) ====================

    private void hookHideRadioNameFlag(XC_LoadPackage.LoadPackageParam lpparam) {
        final int HIDE_RADIO_NAME_RES_ID = 0x7f050008;
        try {
            Class<?> cls = lpparam.classLoader.loadClass("com.ecarx.multimedia.utils.ResourceUtils");
            XposedHelpers.findAndHookMethod(cls, "getBoolean", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int resId = (Integer) param.args[0];
                    if (resId == HIDE_RADIO_NAME_RES_ID && Boolean.TRUE.equals(param.getResult())) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.log(TAG + ": ✓ hooked hide_radio_name flag");
        } catch (Throwable t) { }
    }

    // ==================== 3. MEDIA CENTER AIDL (getRadioStationName) ====================

    private void hookMediaCenterRadioStationName(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] classes = {
            "ecarx.xsf.mediacenter.IMedia",
            "ecarx.xsf.mediacenter.media.holder.MusicPlaybackInfoHolder",
            "com.neusoft.sdk.mediacenter.MusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.PlaybackInfoWrapper",
            "com.neusoft.sdk.mediacenter.MediaInfo",
            "com.neusoft.sdk.mediacenter.MediaInfoWrapper",
            "com.neusoft.sdk.mediacenter.IMediaInfo",
            "com.neusoft.sdk.mediacenter.IMediaInfoWrapper",
            "com.neusoft.sdk.mediacenter.IMusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.AbstractMediaInfo",
            "com.neusoft.sdk.mediacenter.AbstractMusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.MediaCenterAPI$1",
            "com.neusoft.sdk.mediacenter.MediaCenterAPIImpl$1",
            "com.neusoft.sdk.mediacenter.control.bean.Media",
            "com.neusoft.sdk.mediacenter.control.bean.MusicPlaybackInfo",
            "ecarx.xsf.mediacenter.IMusicPlaybackInfo$Stub$Proxy",
            "ecarx.xsf.mediacenter.IMusicPlaybackInfo",
        };
        int hooked = 0;
        for (String cn : classes) {
            try {
                Class<?> cls = lpparam.classLoader.loadClass(cn);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("getRadioStationName".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    String name = (String) param.getResult();
                                    if (name != null && !name.isEmpty() && !"null".equals(name)) return;
                                    String freq = null;
                                    try { freq = (String) XposedHelpers.callMethod(param.thisObject, "getRadioFrequency"); } catch (Throwable t) { return; }
                                    if (freq == null || freq.isEmpty()) return;
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null) param.setResult(found);
                                } catch (Throwable t) { }
                            }
                        });
                        hooked++;
                    }
                }
            } catch (ClassNotFoundException e) { }
        }
        XposedBridge.log(TAG + ": ✓ hooked " + hooked + " getRadioStationName methods");
    }

    // ==================== 4. DIM API TITLE ====================

    private void hookDimApiTitle(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] candidates = {
            "ecarx.xsf.mediacenter.media.holder.MusicPlaybackInfoHolder",
            "ecarx.xsf.mediacenter.dim.DimPlaybackInfo",
            "ecarx.xsf.mediacenter.vr.MusicPlaybackInfo",
        };
        for (String cn : candidates) {
            try {
                Class<?> cls = lpparam.classLoader.loadClass(cn);
                XposedHelpers.findAndHookMethod(cls, "getTitle", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String title = (String) param.getResult();
                            if (title != null && !title.isEmpty()) return;
                            String freq = null;
                            try { freq = (String) XposedHelpers.callMethod(param.thisObject, "getRadioFrequency"); } catch (Throwable t) { return; }
                            if (freq == null || freq.isEmpty()) return;
                            String found = StationsDatabase.findName(freq);
                            if (found != null) param.setResult(found);
                        } catch (Throwable t) { }
                    }
                });
            } catch (Throwable t) { }
        }
    }

    // ==================== 5. SCAN NSMedia CLASSES ====================

    private void scanAndHookMediaClasses(XC_LoadPackage.LoadPackageParam lpparam) {
        // Хукаем все getRadioStationName в NSMedia process
        String[] classes = {
            "com.ecarx.common.bean.radio.RadioInfo",
            "com.neusoft.sdk.mediacenter.MusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.PlaybackInfoWrapper",
            "com.neusoft.sdk.mediacenter.MediaInfo",
            "com.neusoft.sdk.mediacenter.MediaInfoWrapper",
            "com.neusoft.sdk.mediacenter.IMediaInfo",
            "com.neusoft.sdk.mediacenter.IMediaInfoWrapper",
            "com.neusoft.sdk.mediacenter.IMusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.AbstractMediaInfo",
            "com.neusoft.sdk.mediacenter.AbstractMusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.MediaCenterAPI$1",
            "com.neusoft.sdk.mediacenter.MediaCenterAPIImpl$1",
            "com.neusoft.sdk.mediacenter.control.bean.Media",
            "com.neusoft.sdk.mediacenter.control.bean.MusicPlaybackInfo",
            "ecarx.xsf.mediacenter.IMedia",
            "ecarx.xsf.mediacenter.IMusicPlaybackInfo$Stub$Proxy",
            "ecarx.xsf.mediacenter.IMusicPlaybackInfo",
        };
        int hooked = 0;
        for (String cn : classes) {
            try {
                Class<?> cls = lpparam.classLoader.loadClass(cn);
                for (Method m : cls.getDeclaredMethods()) {
                    if ("getRadioStationName".equals(m.getName()) && m.getParameterTypes().length == 0) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    String name = (String) param.getResult();
                                    if (name != null && !name.isEmpty() && !"null".equals(name)) return;
                                    String freq = null;
                                    try { freq = (String) XposedHelpers.callMethod(param.thisObject, "getRadioFrequency"); } catch (Throwable t) { return; }
                                    if (freq == null || freq.isEmpty()) return;
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null) param.setResult(found);
                                } catch (Throwable t) { }
                            }
                        });
                        hooked++;
                    }
                }
            } catch (ClassNotFoundException e) { }
        }
        XposedBridge.log(TAG + ": ✓ scanned NSMedia, hooked " + hooked + " methods");
    }

    // ==================== 6. RADIO COLLECT LIST (избранное) ====================

    private void hookRadioCollectListEvent(XC_LoadPackage.LoadPackageParam lpparam) {
        // EventBus.post → fix station names in event
        try {
            Class<?> eventBusClass = lpparam.classLoader.loadClass("org.greenrobot.eventbus.EventBus");
            XposedHelpers.findAndHookMethod(eventBusClass, "post", Object.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object event = param.args[0];
                        String eventClass = event.getClass().getSimpleName();
                        if (eventClass.contains("RadioScanResult") || eventClass.contains("RadioCollectList")) {
                            fixAllStationsInEvent(event, eventClass);
                        }
                    } catch (Throwable t) { }
                }
            });
        } catch (Throwable t) { }

        // RadioCollectListEvent constructor
        try {
            Class<?> cls = lpparam.classLoader.loadClass("com.ecarx.multimedia.eventbus.radio.RadioCollectListEvent");
            for (java.lang.reflect.Constructor<?> ctor : cls.getConstructors()) {
                if (ctor.getParameterTypes().length == 2) {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try { fixAllStationsInEvent(param.thisObject, "RadioCollectListEvent-ctor"); } catch (Throwable t) { }
                        }
                    });
                }
            }
        } catch (Throwable t) { }
    }

    @SuppressWarnings("unchecked")
    private void fixAllStationsInEvent(Object event, String eventName) {
        try {
            // Ищем поле типа List в event
            for (java.lang.reflect.Field f : event.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    List<?> list = (List<?>) f.get(event);
                    if (list == null) continue;
                    int fixed = 0;
                    for (Object item : list) {
                        try {
                            String name = (String) XposedHelpers.callMethod(item, "getName");
                            if (name != null && !name.isEmpty()) continue;
                            int freq = (Integer) XposedHelpers.getObjectField(item, "frequency");
                            String found = StationsDatabase.findName(String.valueOf(freq));
                            if (found != null) {
                                XposedHelpers.setObjectField(item, "name", found);
                                fixed++;
                            }
                        } catch (Throwable t) { }
                    }
                    if (fixed > 0) {
                        XposedBridge.log(TAG + ": ✓ fixed " + fixed + " names in " + eventName);
                    }
                }
            }
        } catch (Throwable t) { }
    }

    // ==================== 7. WIDGET HOLDER ====================

    private void hookWidgetHolder(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> holderClass = lpparam.classLoader.loadClass("ecarx.xsf.widget.holder.MusicPlayInfoHolder");
            // Hook setMusicPlayInfoAndNotify — устанавливает mLastUiRadioName
            for (Method m : holderClass.getDeclaredMethods()) {
                if ("setMusicPlayInfoAndNotify".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object holder = XposedHelpers.callStaticMethod(
                                    param.thisObject.getClass(), "getInstance");
                                String freq = (String) XposedHelpers.getObjectField(holder, "mLastRadioFreq");
                                String uiName = (String) XposedHelpers.getObjectField(holder, "mLastUiRadioName");
                                if (uiName != null && !uiName.isEmpty()) return;
                                if (freq == null || freq.isEmpty()) return;
                                String found = StationsDatabase.findName(freq);
                                if (found != null) {
                                    XposedHelpers.setObjectField(holder, "mLastUiRadioName", found);
                                }
                            } catch (Throwable t) { }
                        }
                    });
                }
            }
            XposedBridge.log(TAG + ": ✓ hooked widget holder");
        } catch (Throwable t) { }
    }

    // ==================== 8. UPDATE CONTENT TITLE (widget RemoteViews) ====================

    private void hookUpdateContentTitle(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] rvClasses = {
            "ecarx.xsf.widget.remoteviews.UnfoldRadioWidgetRemoteViews",
            "ecarx.xsf.widget.remoteviews.FoldWidgetRemoteViews",
        };
        for (String cn : rvClasses) {
            try {
                Class<?> cls = lpparam.classLoader.loadClass(cn);
                XposedHelpers.findAndHookMethod(cls, "updateContentTitle", new XC_MethodHook() {
                    final ThreadLocal<String[]> saved = new ThreadLocal<>();
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object holder = XposedHelpers.callStaticMethod(
                                lpparam.classLoader.loadClass("ecarx.xsf.widget.holder.MusicPlayInfoHolder"),
                                "getInstance");
                            String freq = (String) XposedHelpers.getObjectField(holder, "mLastRadioFreq");
                            if (freq == null || freq.isEmpty()) return;
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                saved.set(new String[]{freq});
                                XposedHelpers.setObjectField(holder, "mLastRadioFreq", found + " " + freq);
                            }
                        } catch (Throwable t) { }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String[] s = saved.get();
                            if (s != null) {
                                saved.remove();
                                Object holder = XposedHelpers.callStaticMethod(
                                    lpparam.classLoader.loadClass("ecarx.xsf.widget.holder.MusicPlayInfoHolder"),
                                    "getInstance");
                                XposedHelpers.setObjectField(holder, "mLastRadioFreq", s[0]);
                            }
                        } catch (Throwable t) { }
                    }
                });
            } catch (Throwable t) { }
        }
    }

    // ==================== 9. LAUNCHER — ВИДЖЕТ ГЛАВНОГО ЭКРАНА ====================

    private void hookLauncherWidgetUpdate(XC_LoadPackage.LoadPackageParam lpparam) {
        // Хукаем AppWidgetHostView.updateAppWidget AFTER — находим RadioRulerView
        // и подменяем pointerValueStr на имя станции
        try {
            XposedHelpers.findAndHookMethod(android.appwidget.AppWidgetHostView.class,
                "updateAppWidget", android.widget.RemoteViews.class,
                new XC_MethodHook() {
                    private volatile boolean radioRulerHooked = false;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.widget.RemoteViews rv = (android.widget.RemoteViews) param.args[0];
                            if (rv == null) return;
                            if (!"ecarx.xsf.widget".equals(rv.getPackage())) return;

                            android.view.ViewGroup hostView = (android.view.ViewGroup) param.thisObject;
                            android.view.View radioRuler = hostView.findViewById(0x7f08014a);
                            if (radioRuler == null) return;

                            // Хукаем RadioRulerView.setRadioFreq один раз
                            if (!radioRulerHooked) {
                                radioRulerHooked = true;
                                Class<?> rrClass = radioRuler.getClass();
                                XposedBridge.log(TAG + ": ✓ found RadioRulerView: " + rrClass.getName());

                                try {
                                    XposedHelpers.findAndHookMethod(rrClass, "setRadioFreq", String.class,
                                        new XC_MethodHook() {
                                            @Override
                                            protected void afterHookedMethod(MethodHookParam p) {
                                                try {
                                                    String typeAndFreq = (String) p.args[0];
                                                    if (typeAndFreq == null) return;
                                                    int comma = typeAndFreq.indexOf(',');
                                                    if (comma < 0) return;
                                                    String freq = typeAndFreq.substring(comma + 1);
                                                    String name = StationsDatabase.findName(freq);
                                                    String display = StationsDatabase.formatDisplay(freq, name);
                                                    if (display != null) {
                                                        XposedHelpers.setObjectField(p.thisObject, "pointerValueStr", display);
                                                        ((android.view.View) p.thisObject).invalidate();
                                                    }
                                                } catch (Throwable t) { }
                                            }
                                        });
                                    XposedBridge.log(TAG + ": ✓ hooked RadioRulerView.setRadioFreq");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": setRadioFreq hook failed: " + t.getMessage());
                                }
                            }

                            // Подменяем pointerValueStr прямо сейчас
                            try {
                                String pvs = (String) XposedHelpers.getObjectField(radioRuler, "pointerValueStr");
                                if (pvs != null && pvs.matches("\\d+\\.?\\d*")) {
                                    String name = StationsDatabase.findName(pvs);
                                    String display = StationsDatabase.formatDisplay(pvs, name);
                                    if (display != null) {
                                        XposedHelpers.setObjectField(radioRuler, "pointerValueStr", display);
                                        radioRuler.invalidate();
                                    }
                                }
                            } catch (Throwable t) { }

                        } catch (Throwable t) { }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked AppWidgetHostView.updateAppWidget");
        } catch (Throwable t) { }
    }

    // ==================== 10. LONG PRESS TO EDIT (Фаза 3) ====================

    /**
     * Хукаем RadioInfo.getName() — когда пользователь видит имя станции в полноэкранном радио,
     * запоминаем текущую частоту. Затем хукаем TextView.setOnLongClickListener через
     * RadioFragment чтобы при долгом нажатии показать диалог редактирования.
     *
     * Альтернативный подход: хукаем Activity.onResume в NSMedia и ищем TextView с именем станции,
     * навешиваем LongClickListener.
     */
    private void hookLongPressToEdit(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.app.Activity activity = (android.app.Activity) param.thisObject;
                            if (activity.isFinishing()) return;
                            String actName = activity.getClass().getName();
                            if (!actName.contains("ecarx") && !actName.contains("multimedia")) return;

                            // Задержка — View должны быть готовы
                            android.view.View rootView = activity.getWindow().getDecorView();
                            rootView.postDelayed(() -> {
                                try {
                                    if (!activity.isFinishing()) {
                                        attachLongPressListeners(activity, rootView);
                                    }
                                } catch (Throwable t) { }
                            }, 1000);
                        } catch (Throwable t) { }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked Activity.onResume for long-press edit");
        } catch (Throwable t) { }

        try {
            Class<?> cls = lpparam.classLoader.loadClass("com.ecarx.common.bean.radio.RadioInfo");
            XposedHelpers.findAndHookMethod(cls, "getName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int freq = (Integer) XposedHelpers.getObjectField(param.thisObject, "frequency");
                        lastEditFreq = freq;
                    } catch (Throwable t) { }
                }
            });
        } catch (Throwable t) { }
    }

    private volatile int lastEditFreq = 0;

    private void attachLongPressListeners(android.app.Activity activity, android.view.View view) {
        try {
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    attachLongPressListeners(activity, vg.getChildAt(i));
                }
            }
            if (view instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) view;
                CharSequence text = tv.getText();
                if (text == null || text.length() < 3) return;
                String textStr = text.toString();
                if (textStr.matches("^[\\d.]+$")) return;
                if (textStr.equals("FM") || textStr.equals("AM")) return;
                
                // Логируем ВСЕ подходящие TextView для диагностики
                String reverse = StationsDatabase.findNameReverse(textStr);
                XposedBridge.log(TAG + ": >>> TV candidate: '" + textStr + "' reverse=" + reverse 
                    + " id=0x" + Integer.toHexString(tv.getId())
                    + " vis=" + tv.getVisibility());

                // Два варианта: точное совпадение ИЛИ текст содержит имя из базы
                if (reverse != null) {
                    tv.setOnLongClickListener(v -> {
                        showInlineEditDialog(activity, textStr);
                        return true;
                    });
                    XposedBridge.log(TAG + ": ✓ attached longClick to '" + textStr + "'");
                } else {
                    // Попробуем найти частоту из lastEditFreq
                    if (lastEditFreq > 0) {
                        String name = StationsDatabase.findName(String.valueOf(lastEditFreq));
                        if (name != null && textStr.contains(name)) {
                            tv.setOnLongClickListener(v -> {
                                showInlineEditDialog(activity, name);
                                return true;
                            });
                            XposedBridge.log(TAG + ": ✓ attached longClick (contains) to '" + textStr + "'");
                        }
                    }
                }
            }
        } catch (Throwable t) { }
    }

    private void showInlineEditDialog(android.app.Activity activity, String currentName) {
        try {
            String freq = StationsDatabase.findNameReverse(currentName);
            if (freq == null && lastEditFreq > 0) {
                freq = StationsDatabase.normalizeFreqPublic(String.valueOf(lastEditFreq));
            }
            if (freq == null) return;

            final String finalFreq = freq;

            // Запускаем на UI потоке Activity
            activity.runOnUiThread(() -> {
                try {
                    android.widget.EditText input = new android.widget.EditText(activity);
                    input.setText(currentName);
                    input.setPadding(48, 24, 48, 24);
                    input.setSelectAllOnFocus(true);

                    new android.app.AlertDialog.Builder(activity)
                        .setTitle("Переименовать: FM " + finalFreq)
                        .setView(input)
                        .setPositiveButton("Сохранить", (d, w) -> {
                            String newName = input.getText().toString().trim();
                            if (!newName.isEmpty()) {
                                StationsDatabase.updateStation(finalFreq, newName);
                                android.widget.Toast.makeText(activity,
                                    "✓ " + finalFreq + " → " + newName,
                                    android.widget.Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": dialog error: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": showInlineEditDialog error: " + t.getMessage());
        }
    }
}
