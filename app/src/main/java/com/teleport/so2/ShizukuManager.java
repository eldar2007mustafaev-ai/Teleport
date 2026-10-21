package com.teleport.so2;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuManager {

    public interface TeleportCallback {
        void onTeleportSnap();
    }

    // Константа асинхронной инъекции без задержки (0 мс)
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static final String PREF_NAME = "so2_teleport_profile_prefs";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger sessionTeleports = new AtomicInteger(0);
    private static volatile String lastError = "";
    private static Thread workerThread = null;
    private static Process activeProcess = null;

    // Параметры мгновенного микро-чанкового телепорта
    private static volatile int microChunkSize = 30;
    private static volatile int thresholdPx = 14;
    private static volatile int aimZoneStartPct = 75;
    private static volatile int pollRateHz = 240;
    private static volatile int screenWidth = 2400;
    private static volatile int screenHeight = 1080;

    // Параметры растяга экрана для FloatingButtonService
    private static volatile String selectedStretchRatio = "16:9";
    private static volatile int nativeShortSide = 1080;
    private static volatile int nativeLongSide = 2400;
    private static volatile int nativeDpi = 440;
    private static volatile boolean stretchApplied = false;

    private static volatile int rawMaxX = 0;
    private static volatile int rawMaxY = 0;

    private static Object iInputManagerInstance = null;
    private static Method injectInputEventMethod = null;

    public static String getLastError() { return lastError; }
    public static int getSessionTeleports() { return sessionTeleports.get(); }
    public static void resetSessionTeleports() { sessionTeleports.set(0); }
    public static boolean isTeleportRunning() { return running.get(); }
    public static boolean isStretchApplied() { return stretchApplied; }

    public static boolean isShizukuReady() {
        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Сервис Shizuku не запущен.";
                return false;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                lastError = "Права Shizuku не выданы.";
                return false;
            }
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = "Ошибка Shizuku: " + t.getMessage();
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001);
            }
        } catch (Throwable ignored) {}
    }

    private static Process startShizukuShell(String cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
    }

    public static String execCommand(String cmd) {
        if (!isShizukuReady()) return "";
        try {
            Process p = startShizukuShell(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static synchronized boolean initShizukuInputManager() {
        if (iInputManagerInstance != null && injectInputEventMethod != null) return true;
        try {
            IBinder rawBinder = SystemServiceHelper.getSystemService("input");
            if (rawBinder == null) return false;

            ShizukuBinderWrapper wrappedBinder = new ShizukuBinderWrapper(rawBinder);
            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            iInputManagerInstance = asInterface.invoke(null, wrappedBinder);

            injectInputEventMethod = iInputManagerInstance.getClass().getMethod(
                    "injectInputEvent", InputEvent.class, int.class
            );
            return true;
        } catch (Throwable t) {
            lastError = "Ошибка IInputManager: " + t.getMessage();
            return false;
        }
    }

    private static void detectRawTouchBounds() {
        try {
            String info = execCommand("getevent -lp | grep -E 'ABS_MT_POSITION_X|ABS_MT_POSITION_Y'");
            if (info == null || info.isEmpty()) return;
            Pattern maxPat = Pattern.compile("max\\s+(\\d+)");
            for (String line : info.split("\n")) {
                Matcher m = maxPat.matcher(line);
                if (m.find()) {
                    int val = Integer.parseInt(m.group(1));
                    if (line.contains("ABS_MT_POSITION_X") && val > 0) {
                        rawMaxX = val;
                    } else if (line.contains("ABS_MT_POSITION_Y") && val > 0) {
                        rawMaxY = val;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * МГНОВЕННЫЙ ПРЫЖОК (0 мс) в центр микро-чанка через IInputManager.injectInputEvent().
     * Использует MotionEvent.obtain() и обязательный recycle() для предотвращения утечек памяти.
     */
    public static void injectInstantTeleportSnap(int fromX, int fromY, int chunkX, int chunkY) {
        if (!running.get()) return;
        try {
            if (!initShizukuInputManager()) return;
            long now = SystemClock.uptimeMillis();

            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX, fromY, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            MotionEvent instantJump = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, chunkX, chunkY, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
            instantJump.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, chunkX, chunkY, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            injectInputEventMethod.invoke(iInputManagerInstance, down, INJECT_INPUT_EVENT_MODE_ASYNC);
            injectInputEventMethod.invoke(iInputManagerInstance, instantJump, INJECT_INPUT_EVENT_MODE_ASYNC);
            injectInputEventMethod.invoke(iInputManagerInstance, up, INJECT_INPUT_EVENT_MODE_ASYNC);

            down.recycle();
            instantJump.recycle();
            up.recycle();
        } catch (Throwable ignored) {}
    }

    public static void updateLiveConfig(int chunk, int threshold, int zonePct, int pollHz, int scrW, int scrH) {
        microChunkSize = Math.max(10, Math.min(50, chunk));
        thresholdPx = Math.max(6, Math.min(40, threshold));
        aimZoneStartPct = 75;
        pollRateHz = Math.max(60, Math.min(240, pollHz));
        if (scrW > 0 && scrH > 0) {
            screenWidth = Math.max(scrW, scrH);
            screenHeight = Math.min(scrW, scrH);
        }
    }

    /**
     * Проверяет, находится ли пакет com.axlebolt.standoff2 в активном окне на экране.
     */
    public static boolean isStandoffForeground() {
        if (!isShizukuReady()) return false;
        String dump = execCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'");
        if (dump != null && dump.contains("com.axlebolt.standoff2")) {
            return true;
        }
        String actDump = execCommand("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        return actDump != null && actDump.contains("com.axlebolt.standoff2");
    }

    private static boolean isStandoffInMatch() {
        String dump = execCommand("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        if (dump == null || !dump.contains("com.axlebolt.standoff2")) {
            return false;
        }
        return !dump.contains("MainMenu") && !dump.contains("Lobby") && !dump.contains("Authorization");
    }

    public static synchronized boolean startTeleport(int chunk, int threshold, int zonePct, int pollHz,
                                                     int scrW, int scrH, TeleportCallback callback) {
        stopTeleport();
        if (!isShizukuReady()) {
            requestPermission();
            return false;
        }
        if (!initShizukuInputManager()) {
            return false;
        }

        updateLiveConfig(chunk, threshold, 75, pollHz, scrW, scrH);
        running.set(true);

        workerThread = new Thread(() -> {
            try {
                detectRawTouchBounds();
                activeProcess = startShizukuShell("getevent -ql");
                BufferedReader reader = new BufferedReader(new InputStreamReader(activeProcess.getInputStream()));

                int curX = -1, curY = -1;
                int lastChunkX = -1, lastChunkY = -1;
                long lastSnapTime = 0L;
                long lastMatchCheckTime = 0L;
                boolean inMatch = false;

                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    if (!running.get()) break;

                    long now = SystemClock.uptimeMillis();
                    if (now - lastMatchCheckTime > 1200L) {
                        inMatch = isStandoffInMatch();
                        lastMatchCheckTime = now;
                    }
                    // Если сейчас открыто само приложение SO2 Teleport или меню, касания вообще не трогаем
                    if (!inMatch) continue;

                    if (line.contains("ABS_MT_POSITION_X")) {
                        int v = parseVal(line);
                        if (v >= 0) curX = normalizeX(v);
                    } else if (line.contains("ABS_MT_POSITION_Y")) {
                        int v = parseVal(line);
                        if (v >= 0) curY = normalizeY(v);
                    } else if (line.contains("ABS_MT_TRACKING_ID") && line.contains("ffffffff")) {
                        lastChunkX = -1;
                        lastChunkY = -1;
                    } else if (line.contains("SYN_REPORT")) {
                        // Перехват ТОЛЬКО в правой зоне экрана (x > screenWidth * 0.75)
                        int rightZoneLimitX = (int) (screenWidth * 0.75f);
                        if (curX > rightZoneLimitX && curY >= 0) {
                            // Неизменная формула центра микро-чанка:
                            int chunkX = (curX / microChunkSize) * microChunkSize + (microChunkSize / 2);
                            int chunkY = (curY / microChunkSize) * microChunkSize + (microChunkSize / 2);

                            if (lastChunkX < 0 || lastChunkY < 0) {
                                lastChunkX = chunkX;
                                lastChunkY = chunkY;
                            } else {
                                double dist = Math.hypot(curX - lastChunkX, curY - lastChunkY);
                                long minInterval = Math.max(4L, 1000L / pollRateHz);

                                if ((chunkX != lastChunkX || chunkY != lastChunkY)
                                        && dist >= thresholdPx
                                        && (now - lastSnapTime) >= minInterval) {

                                    injectInstantTeleportSnap(curX, curY, chunkX, chunkY);
                                    lastChunkX = chunkX;
                                    lastChunkY = chunkY;
                                    lastSnapTime = now;

                                    sessionTeleports.incrementAndGet();
                                    if (callback != null) callback.onTeleportSnap();
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                running.set(false);
            }
        }, "SO2-Instant-Teleport-Thread");

        workerThread.setDaemon(true);
        workerThread.start();
        return true;
    }

    private static int normalizeX(int rawVal) {
        if (rawMaxX > 0 && rawMaxY > 0) {
            int maxLong = Math.max(rawMaxX, rawMaxY);
            return Math.round((rawVal / (float) maxLong) * screenWidth);
        }
        return rawVal;
    }

    private static int normalizeY(int rawVal) {
        if (rawMaxX > 0 && rawMaxY > 0) {
            int maxShort = Math.min(rawMaxX, rawMaxY);
            return Math.round((rawVal / (float) maxShort) * screenHeight);
        }
        return rawVal;
    }

    public static synchronized void stopTeleport() {
        running.set(false);
        if (activeProcess != null) {
            try { activeProcess.destroy(); } catch (Throwable ignored) {}
            activeProcess = null;
        }
        if (workerThread != null) {
            try { workerThread.interrupt(); } catch (Throwable ignored) {}
            workerThread = null;
        }
    }

    private static int parseVal(String line) {
        try {
            String[] p = line.trim().split("\\s+");
            return Integer.parseInt(p[p.length - 1], 16);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Сохраняет выбранное соотношение сторон в настройках для использования плавающей кнопкой.
     */
    public static String saveStretchConfig(Context context, String ratioCode, int baseShortSide, int baseLongSide, int baseDpi) {
        nativeShortSide = baseShortSide;
        nativeLongSide = baseLongSide;
        nativeDpi = baseDpi;
        selectedStretchRatio = (ratioCode != null && !ratioCode.isEmpty()) ? ratioCode : "16:9";

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("stretch_ratio", selectedStretchRatio)
                .putInt("native_short", baseShortSide)
                .putInt("native_long", baseLongSide)
                .putInt("native_dpi", baseDpi)
                .apply();

        return "Формат " + selectedStretchRatio + " сохранён для плавающей кнопки 📐";
    }

    /**
     * Применяет wm size и wm density (вызывается из FloatingButtonService при нажатии кнопки в Standoff 2).
     */
    public static String applyStretch(Context context) {
        if (!isShizukuReady()) return "Нет прав Shizuku";

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String ratio = prefs.getString("stretch_ratio", selectedStretchRatio);
        int shortSide = prefs.getInt("native_short", nativeShortSide);
        int dpi = prefs.getInt("native_dpi", nativeDpi);

        int targetLong;
        int targetDpi = Math.max(280, Math.round(dpi * 0.9f));

        switch (ratio) {
            case "4:3":
                targetLong = Math.round(shortSide * (4f / 3f));
                break;
            case "3:2":
                targetLong = Math.round(shortSide * 1.5f);
                break;
            case "16:10":
                targetLong = Math.round(shortSide * 1.6f);
                break;
            case "16:9":
            default:
                targetLong = Math.round(shortSide * (16f / 9f));
                break;
        }

        if (targetLong % 2 != 0) targetLong++;

        execCommand("wm size " + shortSide + "x" + targetLong + "; wm density " + targetDpi);
        screenWidth = targetLong;
        screen
