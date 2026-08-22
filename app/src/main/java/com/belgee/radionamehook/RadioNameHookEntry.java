package com.belgee.radionamehook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.List;

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
    private static final String STATUS_DIR = "/sdcard/RadioNames/hook_status";

    // Ссылка на сам фрагмент — нужна чтобы проверить его РЕАЛЬНУЮ текущую
    // частоту (mCurBand/mCurFrequency), чтобы не путать какой вызов getName()
    // относится к реально видимой на экране станции, а какой — к фоновому
    // (например, перебор частот при построении списка/избранного).
    private static volatile java.lang.ref.WeakReference<Object> currentRadioFragment = null;

    private void writeHookStatus(String pkg) {
        try {
            java.io.File dir = new java.io.File(STATUS_DIR);
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(STATUS_DIR, pkg + ".txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                w.write(String.valueOf(System.currentTimeMillis()));
            }
        } catch (Throwable t) { }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean isNSMedia = "com.ecarx.multimedia".equals(lpparam.packageName);
        boolean isWidget = "ecarx.xsf.widget".equals(lpparam.packageName);
        boolean isMediaCenter = "ecarx.xsf.mediacenter".equals(lpparam.packageName);
        boolean isLauncher = "ecarx.launcher3".equals(lpparam.packageName);

        if (!isNSMedia && !isWidget && !isMediaCenter && !isLauncher) return;

        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName);
        StationsDatabase.init();
        writeHookStatus(lpparam.packageName);

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

    // ==================== 1. RADIO INFO GET NAME (полноэкранное радио) ====================

    private void hookRadioInfoGetName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = lpparam.classLoader.loadClass("com.ecarx.common.bean.radio.RadioInfo");
            XposedHelpers.findAndHookMethod(cls, "getName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int freq = (Integer) XposedHelpers.getObjectField(param.thisObject, "frequency");
                        String normFreq = StationsDatabase.normalizeFreq(String.valueOf(freq));

                        // isCurrentlyTuned нужен ТОЛЬКО для writeCurrentFreq (используется
                        // кнопкой "Переименовать текущую" в приложении) — там правда важно
                        // не путать реально играющую станцию с фоновыми вызовами getName().
                        //
                        // Для САМОЙ подмены имени (param.setResult) эта проверка была
                        // ЛИШНЕЙ и даже вредной: она завязана на mCurBand/mCurFrequency
                        // фрагмента, а те, похоже, не всегда успевают синхронизироваться
                        // при перелистывании стрелками — отсюда "Нет информации" пропадала
                        // именно на стрелках. param.setResult() сам по себе безопасен —
                        // влияет ТОЛЬКО на возврат ЭТОГО КОНКРЕТНОГО вызова, корректно
                        // привязанного к своей freq, поэтому подмену делаем БЕЗ оглядки
                        // на эту проверку.
                        String actualFreq = getActualTunedFreq();
                        boolean isCurrentlyTuned = actualFreq != null && actualFreq.equals(normFreq);
                        if (isCurrentlyTuned) {
                            StationsDatabase.writeCurrentFreq(normFreq);
                        }

                        String name = (String) param.getResult();
                        if (name != null && !name.isEmpty()) return;
                        String found = StationsDatabase.findName(String.valueOf(freq));
                        if (found != null) {
                            found = StationsDatabase.truncateForDisplay(found, 28);
                            param.setResult(found);
                        } else {
                            // Данных о станции нет — подменяем явным текстом.
                            param.setResult("Нет информации");
                        }
                    } catch (Throwable t) { }
                }
            });
            XposedBridge.log(TAG + ": ✓ hooked RadioInfo.getName");
        } catch (Throwable t) { }
    }

    /** Реальная текущая настроенная частота фрагмента (mCurBand/mCurFrequency), или null если недоступна. */
    private String getActualTunedFreq() {
        try {
            java.lang.ref.WeakReference<Object> fragRef = currentRadioFragment;
            Object fragment = fragRef != null ? fragRef.get() : null;
            if (fragment == null) return null;
            int band = (Integer) XposedHelpers.getObjectField(fragment, "mCurBand");
            android.util.SparseIntArray freqMap = (android.util.SparseIntArray)
                XposedHelpers.getObjectField(fragment, "mCurFrequency");
            int actualFreq = (freqMap != null) ? freqMap.get(band, 0) : 0;
            if (actualFreq <= 0) return null;
            return (actualFreq >= 87500)
                ? String.format(java.util.Locale.US, "%.1f", actualFreq / 1000.0)
                : String.valueOf(actualFreq);
        } catch (Throwable t) { return null; }
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
                                    // Частота берётся из САМОГО объекта (getRadioFrequency) —
                                    // подмена всегда корректно привязана именно к ней, поэтому
                                    // сверка с mCurBand/mCurFrequency фрагмента не нужна (та
                                    // проверка ломала подмену на стрелках влево/вправо из-за
                                    // гонки — эти поля не всегда успевают синхронизироваться).
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null) {
                                        param.setResult(StationsDatabase.truncateForDisplay(found, 28));
                                    } else {
                                        param.setResult("Нет информации");
                                    }
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
                            // Частота из САМОГО объекта — подмена корректно привязана к
                            // ней, сверка с фрагментом не нужна (ломала на стрелках).
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.setResult(StationsDatabase.truncateForDisplay(found, 28));
                            } else {
                                param.setResult("Нет информации");
                            }
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
                                    if (found != null) param.setResult(StationsDatabase.truncateForDisplay(found, 28));
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
                                XposedHelpers.setObjectField(item, "name", StationsDatabase.truncateForDisplay(found, 28));
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
                                    XposedHelpers.setObjectField(holder, "mLastUiRadioName", StationsDatabase.truncateForDisplay(found, 28));
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
                                XposedHelpers.setObjectField(holder, "mLastRadioFreq", StationsDatabase.truncateForDisplay(found, 28) + " " + freq);
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
                    private volatile boolean classHookInstalled = false;
                    // WeakHashMap отслеживает КОНКРЕТНЫЕ экземпляры RadioRulerView.
                    // Когда Android пересоздаёт виджет (например, при возврате с другого
                    // приложения), появляется НОВЫЙ объект — его нужно обработать заново,
                    // а старая запись сама уйдёт из карты когда объект соберёт GC.
                    private final java.util.Map<Object, Boolean> substitutedInstances =
                        new java.util.WeakHashMap<>();

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.widget.RemoteViews rv = (android.widget.RemoteViews) param.args[0];
                            if (rv == null) return;
                            // Быстрый фильтр — пропускаем ЛЮБЫЕ чужие виджеты сразу,
                            // это самая частая ветка, поэтому она должна быть дешёвой.
                            if (!"ecarx.xsf.widget".equals(rv.getPackage())) return;

                            android.view.ViewGroup hostView = (android.view.ViewGroup) param.thisObject;
                            android.view.View radioRuler = hostView.findViewById(0x7f08014a);
                            if (radioRuler == null) return;

                            // Хукаем setRadioFreq один раз на класс — этот хук работает
                            // для ВСЕХ будущих экземпляров класса, переустанавливать не нужно.
                            if (!classHookInstalled) {
                                classHookInstalled = true;
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
                                                        // Лимит зависит от стиля: "Только имя" — оригинальный
                                                        // размер шрифта, значит лимит жёстче (на 2 меньше общего);
                                                        // "Частота + имя" — настраиваемый размер, лимит мягче.
                                                        int limit = (StationsDatabase.getDisplayStyle() == 0) ? 18 : 17;
                                                        if (display.length() > limit) display = display.substring(0, limit - 1) + "…";
                                                        adjustPointerTextSize(p.thisObject, display.length());
                                                        XposedHelpers.setObjectField(p.thisObject, "pointerValueStr", display);
                                                        ((android.view.View) p.thisObject).invalidate();
                                                    }
                                                } catch (Throwable t) { }
                                            }
                                        });
                                    XposedBridge.log(TAG + ": ✓ hooked RadioRulerView.setRadioFreq");
                                } catch (Throwable t) { }
                            }

                            // Подменяем имя для ЭТОГО конкретного экземпляра,
                            // если ещё не подменяли (новый объект после пересоздания виджета).
                            if (!substitutedInstances.containsKey(radioRuler)) {
                                try {
                                    String pvs = (String) XposedHelpers.getObjectField(radioRuler, "pointerValueStr");
                                    // Раньше тут была строгая проверка "похоже ли pvs на голое
                                    // число" — если нет, навсегда сдавались на этом экземпляре.
                                    // Именно это давало пропажу имени после возврата с камеры
                                    // заднего вида: новый экземпляр виджета мог иметь pvs НЕ в
                                    // виде чистого числа в момент проверки, и имя больше никогда
                                    // не подставлялось для него. Теперь просто пробуем найти имя
                                    // по pvs как есть — если не найдётся, findName сам вернёт null,
                                    // без вреда.
                                    if (pvs != null && !pvs.isEmpty()) {
                                        String name = StationsDatabase.findName(pvs);
                                        String display = StationsDatabase.formatDisplay(pvs, name);
                                        if (display != null) {
                                            int limit = (StationsDatabase.getDisplayStyle() == 0) ? 18 : 17;
                                            if (display.length() > limit) display = display.substring(0, limit - 1) + "…";
                                            adjustPointerTextSize(radioRuler, display.length());
                                            XposedHelpers.setObjectField(radioRuler, "pointerValueStr", display);
                                            radioRuler.invalidate();
                                        }
                                    }
                                    // Отмечаем экземпляр обработанным в любом случае —
                                    // чтобы не пытаться на каждом обновлении, даже если pvs не число
                                    substitutedInstances.put(radioRuler, true);
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
    /**
     * Пытается уменьшить размер шрифта у текста имени станции через pointerPaint.
     * НОВЫЙ ПОДХОД: похоже что onDraw() каждый кадр СБРАСЫВАЕТ размер шрифта
     * pointerPaint обратно (из общего поля fontSize) — поэтому прямой вызов
     * pointerPaint.setTextSize() держался недолго, следующий кадр всё стирал.
     * Вместо борьбы с этим — перехватываем САМ МЕТОД Paint.setTextSize()
     * и подменяем значение на лету КАЖДЫЙ раз когда его пытаются установить
     * (неважно кто и сколько раз вызывает — наш перехват всегда последний).
     * Хук ставится на класс Paint глобально, но проверяет конкретный объект
     * (== pointerPaint), поэтому остальные Paint в процессе не затрагивает.
     */
    private static Float pointerOriginalTextSize = null;
    private static volatile android.graphics.Paint trackedPointerPaint = null;
    private static volatile float pointerScaleFactor = 1.0f;
    private static volatile boolean suppressPointerSizeHook = false;
    private static volatile boolean pointerSizeHookInstalled = false;
    private static volatile boolean pointerBoldHookInstalled = false;
    private static volatile boolean suppressPointerBoldHook = false;

    private void adjustPointerTextSize(Object radioRuler, int textLen) {
        try {
            // НАШЛИ ПРАВИЛЬНЫЙ ОБЪЕКТ: логи с реального устройства показали
            // mPaint textSize=64.0 color=-1(белый) — это ОГРОМНЫЙ размер и типичный
            // "текст на тёмном фоне" цвет, в отличие от pointerPaint (10-12, свой
            // приглушённый цвет — судя по всему это игла-указатель на шкале, не текст).
            // Весь этот месяц меняли не тот Paint — теперь переключаемся на mPaint.
            Object paintObj = XposedHelpers.getObjectField(radioRuler, "mPaint");
            if (!(paintObj instanceof android.graphics.Paint)) return;
            android.graphics.Paint textPaint = (android.graphics.Paint) paintObj;

            if (pointerOriginalTextSize == null) pointerOriginalTextSize = textPaint.getTextSize();
            trackedPointerPaint = textPaint;
            installPointerPaintSizeHookOnce();
            installPointerPaintBoldHookOnce();

            // Убираем жирность — по сравнению со штатной GMC-панелью там же
            // жирная только частота ("FM 87.5"), а название обычным начертанием
            // и заметно ýже.
            android.graphics.Typeface tfBefore = textPaint.getTypeface();
            suppressPointerBoldHook = true;
            try {
                textPaint.setFakeBoldText(false);
                if (tfBefore != null && tfBefore.isBold()) {
                    textPaint.setTypeface(android.graphics.Typeface.create(tfBefore, android.graphics.Typeface.NORMAL));
                }
            } catch (Throwable t) { } finally {
                suppressPointerBoldHook = false;
            }

            // ЕДИНЫЙ фиксированный размер для ВСЕХ имён — раньше размер менялся
            // в зависимости от длины текста, что выглядело непоследовательно при
            // переключении станций (то крупно, то мелко). Теперь всегда один и
            // тот же размер (~28, как у цифр шкалы numPaint — визуально
            // согласовано), а совсем длинные имена обрезаются с "…" на уровне
            // текста (см. truncateForDisplay), а не меняют размер шрифта.
            // Масштаб теперь читается из настройки в приложении, не захардкожен —
            // можно подбирать прямо на устройстве без пересборки APK.
            // Раздельная логика по стилям: для "Только имя" — оригинальный
            // (не уменьшенный) размер, для "Частота + имя" — настраиваемый
            // через пресеты в приложении.
            final float FIXED_SCALE = (StationsDatabase.getDisplayStyle() == 0)
                ? 1.0f : StationsDatabase.getFontScale();

            pointerScaleFactor = FIXED_SCALE;

            suppressPointerSizeHook = true;
            try {
                textPaint.setTextSize(pointerOriginalTextSize * FIXED_SCALE);
            } finally {
                suppressPointerSizeHook = false;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": adjustPointerTextSize error: " + t);
        }
    }

    /** Ставится один раз на весь процесс — перехватывает Paint.setTextSize(float) глобально. */
    private void installPointerPaintSizeHookOnce() {
        if (pointerSizeHookInstalled) return;
        pointerSizeHookInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setTextSize", float.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (suppressPointerSizeHook) return;
                            if (param.thisObject != trackedPointerPaint) return;
                            // Подстраховка на случай если что-то (например onDraw())
                            // попытается сбросить размер напрямую — не подтвердилось
                            // на практике (см. историю), но оставляем как safety net.
                            float requested = (Float) param.args[0];
                            if (pointerScaleFactor != 1.0f) {
                                param.args[0] = requested * pointerScaleFactor;
                            }
                        } catch (Throwable t) { }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked Paint.setTextSize (targeted pointer shrink)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Paint.setTextSize hook install failed: " + t);
        }
    }

    /**
     * Перехватывает Paint.setFakeBoldText() и Paint.setTypeface() — если
     * onDraw() каждый кадр принудительно ставит жирное начертание обратно
     * (как и с размером), прямой единоразовый вызов не продержится. Так же
     * как с размером — перехватываем сами методы, действуем только на
     * pointerPaint, остальные Paint в процессе не трогаем.
     */
    private void installPointerPaintBoldHookOnce() {
        if (pointerBoldHookInstalled) return;
        pointerBoldHookInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setFakeBoldText", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (suppressPointerBoldHook) return;
                            if (param.thisObject == trackedPointerPaint) {
                                param.args[0] = Boolean.FALSE;
                            }
                        } catch (Throwable t) { }
                    }
                });
            XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setTypeface",
                android.graphics.Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (suppressPointerBoldHook) return;
                            if (param.thisObject == trackedPointerPaint) {
                                android.graphics.Typeface tf = (android.graphics.Typeface) param.args[0];
                                if (tf != null && tf.isBold()) {
                                    param.args[0] = android.graphics.Typeface.create(tf, android.graphics.Typeface.NORMAL);
                                }
                            }
                        } catch (Throwable t) { }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked Paint.setFakeBoldText/setTypeface (targeted pointer un-bold)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Paint bold hook install failed: " + t);
        }
    }

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
                            currentRadioFragment = new java.lang.ref.WeakReference<>(fragment);

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
                                    // Раньше проверка ловила только "чистые цифры" или точное
                                    // совпадение с частотой — если NSMedia показывает частоту
                                    // "украшенную" (например "103.4 MHz" или "FM 103.4"), это
                                    // не ловилось и подсказка "Нет информации" не появлялась.
                                    // Теперь снимаем цифры/точки/запятые/пробелы/известные
                                    // единицы измерения и смотрим — осталось ли что-то ещё.
                                    String stripped = current.trim()
                                        .replaceAll("(?i)[\\d.,\\s]|MHz|МГц|KHz|кГц|FM|AM", "");
                                    boolean isEmpty = stripped.isEmpty();
                                    if (isEmpty) {
                                        input.setHint("Нет информации — впиши название");
                                    } else {
                                        input.setText(current);
                                        input.setSelection(input.getText().length());
                                    }

                                    String title = (freq >= 87500)
                                        ? String.format("FM %.1f МГц", freq / 1000.0)
                                        : "AM " + freq + " кГц";

                                    new android.app.AlertDialog.Builder(ctx)
                                        .setTitle(title)
                                        .setView(input)
                                        .setPositiveButton("Сохранить", (d, w) -> {
                                            String name = input.getText().toString().trim();
                                            if (!name.isEmpty()) {
                                                StationsDatabase.updateStation(freqStr, name);
                                                // Пользователь мог ввести длинное имя — режем
                                                // только для отображения, в базе остаётся полное.
                                                String shown = name.length() > 28
                                                    ? name.substring(0, 27) + "…" : name;
                                                tv.setText(shown);
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
