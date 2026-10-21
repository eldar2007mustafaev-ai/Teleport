package com.teleport.so2;

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

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean stretchMonitorRunning = new AtomicBoolean(false);
    private static final AtomicInteger sessionTeleports = new AtomicInteger(0);
    private static volatile String lastError = "";
    private static Thread workerThread = null;
    private static Thread stretchMonitorThread = null;
    private static Process activeProcess = null;

    // Параметры мгновенного микро-чанкового телепорта
    private static volatile int microChunkSize = 30;
    private static volatile int thresholdPx = 14;
    private static volatile int aimZoneStartPct = 75;
    private static volatile int pollRateHz = 240;
    private static volatile int screenWidth = 2400;
    private static volatile int screenHeight = 1080;

    // Параметры автоматического растяга только для Standoff 2
    private static volatile String selectedStretchRatio = "DEFAULT";
    private static volatile int nativeShortSide = 1080;
    private static volatile int nativeLongSide = 2400;
    private static volatile boolean isStretchCurrentlyApplied = false;

    private static volatile int rawMaxX = 0;
    private static volatile int rawMaxY = 0;

    private static Object iInputManagerInstance = null;
    private static Method injectInputEventMethod = null;

    public static String getLastError() { return lastError; }
    public static int getSessionTeleports() { return sessionTeleports.get(); }
    public static void resetSessionTeleports() { sessionTeleports.set(0); }
    public static boolean isTeleportRunning() { return running.get(); }

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
        aimZoneStartPct = Math.max(50, Math.min(85, zonePct));
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
        String dump = execCommand("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        return dump != null && dump.contains("com.axlebolt.standoff2");
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

        updateLiveConfig(chunk, threshold, zonePct, pollHz, scrW, scrH);
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
                boolean inMatch = true;

                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastMatchCheckTime > 1500L) {
                        inMatch = isStandoffInMatch();
                        lastMatchCheckTime = now;
                    }
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
                        int rightZoneStartX = (screenWidth * aimZoneStartPct) / 100;
                        if (curX >= rightZoneStartX && curY >= 0) {
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
     * Установка желаемого соотношения сторон.
     * Применяется ТОЛЬКО когда открыт Standoff 2, и автоматически сбрасывается при выходе из игры.
     */
    public static String setTargetStretchRatio(String ratioCode, int baseShortSide, int baseLongSide) {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        nativeShortSide = baseShortSide;
        nativeLongSide = baseLongSide;

        if (ratioCode == null || "DEFAULT".equalsIgnoreCase(ratioCode)) {
            selectedStretchRatio = "DEFAULT";
            if (isStretchCurrentlyApplied) {
                execCommand("wm size reset");
                isStretchCurrentlyApplied = false;
            }
            screenWidth = baseLongSide;
            screenHeight = baseShortSide;
            return "Экран сброшен по умолчанию";
        }

        selectedStretchRatio = ratioCode;
        startAutoStretchMonitor();
        return "Растяг " + ratioCode + " сохранён! Он включится автоматически при входе в Standoff 2 и сбросится при выходе.";
    }

    private static void applyStretchInternal(String ratioCode) {
        int targetLong;
        switch (ratioCode) {
            case "4:3":
                targetLong = Math.round(nativeShortSide * (4f / 3f));
                break;
            case "3:2":
                targetLong = Math.round(nativeShortSide * 1.5f);
                break;
            case "16:10":
                targetLong = Math.round(nativeShortSide * 1.6f);
                break;
            case "16:9":
                targetLong = Math.round(nativeShortSide * (16f / 9f));
                break;
            default:
                return;
        }
        if (targetLong % 2 != 0) targetLong++;
        execCommand("wm size " + nativeShortSide + "x" + targetLong);
        screenWidth = targetLong;
        screenHeight = nativeShortSide;
        isStretchCurrentlyApplied = true;
    }

    /**
     * Фоновый мониторинг активного окна через Shizuku API:
     * когда активен com.axlebolt.standoff2 — включает выбранный растяг;
     * когда Standoff 2 свёрнут или закрыт — мгновенно делает wm size reset.
     */
    public static synchronized void startAutoStretchMonitor() {
        if (stretchMonitorRunning.get()) return;
        stretchMonitorRunning.set(true);

        stretchMonitorThread = new Thread(() -> {
            while (stretchMonitorRunning.get()) {
                try {
                    if (isShizukuReady() && !"DEFAULT".equalsIgnoreCase(selectedStretchRatio)) {
                        boolean so2Active = isStandoffForeground();
                        if (so2Active && !isStretchCurrentlyApplied) {
                            applyStretchInternal(selectedStretchRatio);
                        } else if (!so2Active && isStretchCurrentlyApplied) {
                            execCommand("wm size reset");
                            screenWidth = nativeLongSide;
                            screenHeight = nativeShortSide;
                            isStretchCurrentlyApplied = false;
                        }
                    } else if (isStretchCurrentlyApplied && "DEFAULT".equalsIgnoreCase(selectedStretchRatio)) {
                        execCommand("wm size reset");
                        isStretchCurrentlyApplied = false;
                    }
                    Thread.sleep(1200L);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        }, "SO2-AutoStretch-Monitor");

        stretchMonitorThread.setDaemon(true);
        stretchMonitorThread.start();
    }

    public static synchronized void stopAutoStretchMonitor() {
        stretchMonitorRunning.set(false);
        if (isStretchCurrentlyApplied) {
            execCommand("wm size reset");
            isStretchCurrentlyApplied = false;
        }
        if (stretchMonitorThread != null) {
            try { stretchMonitorThread.interrupt(); } catch (Throwable ignored) {}
            stretchMonitorThread = null;
        }
    }

    public static String resetScreenToDefault() {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        selectedStretchRatio = "DEFAULT";
        isStretchCurrentlyApplied = false;
        execCommand("wm size reset");
        screenWidth = nativeLongSide;
        screenHeight = nativeShortSide;
        return "Экран возвращён к заводским настройкам";
    }

    public static String runFpsBooster(boolean aggressive) {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        if (aggressive) {
            execCommand("am kill-all 2>/dev/null; cmd activity trim-memory RUNNING_CRITICAL 2>/dev/null; settings put global window_animation_scale 0.0; settings put global transition_animation_scale 0.0; settings put global animator_duration_scale 0.0");
            return "Бустер FPS (Макс): фоновые процессы остановлены!";
        } else {
            execCommand("am kill-all 2>/dev/null; settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0");
            return "Стандартный режим восстановлен";
        }
    }

    public static String cleanRamBeforeGame() {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        execCommand("am kill-all 2>/dev/null; cmd activity trim-memory RUNNING_CRITICAL 2>/dev/null");
        return "ОЗУ успешно очищена перед матчем!";
    }

    public static void setDoNotDisturb(boolean enable) {
        if (!isShizukuReady()) return;
        execCommand(enable ? "cmd notification set_dnd priority 2>/dev/null" : "cmd notification set_dnd off 2>/dev/null");
    }
}
