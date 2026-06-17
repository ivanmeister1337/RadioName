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
            try { hookRadioInfoGetName(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookRadioInfoGetName FAIL: " + t); }
            try { hookHideRadioNameFlag(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookHideRadioNameFlag FAIL: " + t); }
            try { scanAndHookMediaClasses(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": scanAndHookMediaClasses FAIL: " + t); }
            try { hookRadioCollectListEvent(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookRadioCollectListEvent FAIL: " + t); }
            try { hookLongPressInRadio(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookLongPressInRadio FAIL: " + t); }
        }

        if (isWidget) {
            try { hookWidgetHolder(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookWidgetHolder FAIL: " + t); }
            try { hookUpdateContentTitle(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookUpdateContentTitle FAIL: " + t); }
        }

        if (isMediaCenter) {
            try { hookMediaCenterRadioStationName(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookMediaCenter FAIL: " + t); }
            try { hookDimApiTitle(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookDimApi FAIL: " + t); }
        }

        if (isLauncher) {
            try { hookLauncherWidgetUpdate(lpparam); } catch (Throwable t) { XposedBridge.log(TAG + ": hookLauncher FAIL: " + t); }
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
                        int freq = (Integer) XposedHelpers.getObjectField(param.thisObject, "frequency");
                        // Записываем текущую частоту для приложения
                        StationsDatabase.writeCurrentFreq(StationsDatabase.normalizeFreq(String.valueOf(freq)));
                        String name = (String) param.getResult();
                        if (name != null && !name.isEmpty()) return;
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
        try {
            XposedHelpers.findAndHookMethod(android.appwidget.AppWidgetHostView.class,
                "updateAppWidget", android.widget.RemoteViews.class,
                new XC_MethodHook() {
                    private volatile boolean radioRulerHooked = false;
                    private volatile boolean initialNameSet = false;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Если хук уже установлен и имя подменено — ничего не делаем
                        if (radioRulerHooked && initialNameSet) return;

                        try {
                            android.widget.RemoteViews rv = (android.widget.RemoteViews) param.args[0];
                            if (rv == null) return;
                            if (!"ecarx.xsf.widget".equals(rv.getPackage())) return;

                            android.view.ViewGroup hostView = (android.view.ViewGroup) param.thisObject;
                            android.view.View radioRuler = hostView.findViewById(0x7f08014a);
                            if (radioRuler == null) return;

                            // Хукаем setRadioFreq один раз
                            if (!radioRulerHooked) {
                                radioRulerHooked = true;
                                Class<?> rrClass = radioRuler.getClass();
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
                                                    StationsDatabase.writeCurrentFreq(freq);
                                                    String name = StationsDatabase.findName(freq);
                                                    String display = StationsDatabase.formatDisplay(freq, name);
                                                    if (display != null) {
                                                        if (display.length() > 14) display = display.substring(0, 13) + "…";
                                                        XposedHelpers.setObjectField(p.thisObject, "pointerValueStr", display);
                                                        ((android.view.View) p.thisObject).invalidate();
                                                    }
                                                } catch (Throwable t) { }
                                            }
                                        });
                                    XposedBridge.log(TAG + ": ✓ hooked RadioRulerView.setRadioFreq");
                                } catch (Throwable t) { }
                            }

                            // Подменяем имя один раз при первом обнаружении
                            if (!initialNameSet) {
                                try {
                                    String pvs = (String) XposedHelpers.getObjectField(radioRuler, "pointerValueStr");
                                    if (pvs != null && pvs.matches("\\d+\\.?\\d*")) {
                                        String name = StationsDatabase.findName(pvs);
                                        String display = StationsDatabase.formatDisplay(pvs, name);
                                        if (display != null) {
                                            if (display.length() > 14) display = display.substring(0, 13) + "…";
                                            XposedHelpers.setObjectField(radioRuler, "pointerValueStr", display);
                                            radioRuler.invalidate();
                                            initialNameSet = true;
                                        }
                                    }
                                } catch (Throwable t) { }
                            }
                        } catch (Throwable t) { }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked AppWidgetHostView.updateAppWidget");
        } catch (Throwable t) { }
    }

    // ==================== 10. LONG PRESS IN FULLSCREEN RADIO ====================

    /**
     * Хукаем TextView.setText в процессе NSMedia.
     * Когда текст совпадает с именем станции из базы — навешиваем OnLongClickListener.
     * Долгое нажатие → диалог переименования прямо в NSMedia.
     */
    private void hookLongPressInRadio(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.ecarx.multimedia.module.radio.RadioFragment",
                lpparam.classLoader,
                "initView", android.view.View.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            final Object fragment = param.thisObject;
                            final android.widget.TextView tv = (android.widget.TextView)
                                XposedHelpers.getObjectField(fragment, "radio_name");
                            if (tv == null) {
                                XposedBridge.log(TAG + ": LP radio_name == null");
                                return;
                            }

                            tv.setClickable(true);
                            tv.setLongClickable(true);

                            tv.setOnLongClickListener(v -> {
                                try {
                                    int band = (Integer) XposedHelpers.getObjectField(fragment, "mCurBand");
                                    android.util.SparseIntArray freqMap = (android.util.SparseIntArray)
                                        XposedHelpers.getObjectField(fragment, "mCurFrequency");
                                    int freq = (freqMap != null) ? freqMap.get(band, 0) : 0;
                                    if (freq <= 0) return true;

                                    String current = tv.getText() != null ? tv.getText().toString() : "";
                                    String freqStr = (freq >= 87500)
                                        ? String.format(java.util.Locale.US, "%.1f", freq / 1000.0)
                                        : String.valueOf(freq);

                                    // Записываем текущую частоту
                                    StationsDatabase.writeCurrentFreq(freqStr);

                                    android.content.Context ctx = v.getContext();
                                    android.widget.EditText input = new android.widget.EditText(ctx);
                                    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                                    input.setText(current);
                                    input.setSelection(input.getText().length());

                                    String title = (freq >= 87500)
                                        ? String.format("FM %.1f МГц", freq / 1000.0)
                                        : freq + " кГц";

                                    new android.app.AlertDialog.Builder(ctx)
                                        .setTitle(title)
                                        .setView(input)
                                        .setPositiveButton("Сохранить", (d, w) -> {
                                            String name = input.getText().toString().trim();
                                            if (!name.isEmpty()) {
                                                StationsDatabase.updateStation(freqStr, name);
                                                tv.setText(name);
                                                android.widget.Toast.makeText(ctx,
                                                    "✓ " + freqStr + " → " + name,
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                            }
                                        })
                                        .setNegativeButton("Отмена", null)
                                        .show();
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": LP dialog error: " + t);
                                }
                                return true;
                            });

                            XposedBridge.log(TAG + ": ✓ LP listener attached to radio_name");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": LP initView error: " + t);
                        }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked RadioFragment.initView for long-press");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LP hook failed: " + t.getMessage());
        }
    }
}
