package com.teleport.so2;

import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import rikka.shizuku.Shizuku;

public class ShizukuManager {

    public interface TeleportCallback {
        void onTeleportSnap();
    }

    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger sessionTeleports = new AtomicInteger(0);
    private static volatile String lastError = "";

    private static volatile int microChunkSize = 30;
    private static volatile int thresholdPx = 14;
    private static volatile int pollRateHz = 240;
    private static volatile int screenWidth = 2400;
    private static volatile int screenHeight = 1080;

    private static volatile String selectedStretchRatio = "16:9";
    private static volatile int nativeShortSide = 1080;
    private static volatile int nativeLongSide = 2400;
    private static volatile int nativeDpi = 440;
    private static volatile boolean stretchActive = false;

    public static String getLastError() { return lastError; }
    public static int getSessionTeleports() { return sessionTeleports.get(); }
    public static void resetSessionTeleports() { sessionTeleports.set(0); }
    public static boolean isTeleportRunning() { return running.get(); }
    public static boolean isStretchActive() { return stretchActive; }
    public static String getSelectedStretchRatio() { return selectedStretchRatio; }

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

    /**
     * Прямая калибровка резкости сенсы через Shizuku shell без перехватчиков касаний.
     */
    private static void applyDirectSensCommands(int chunk) {
        int pointerSpeed;
        int touchTimeout;
        if (chunk >= 38) {
            // Макс резкая
            pointerSpeed = 7;
            touchTimeout = 150;
        } else if (chunk >= 24) {
            // Средняя / Настраиваемая
            pointerSpeed = 5;
            touchTimeout = 180;
        } else {
            // Плавная
            pointerSpeed = 3;
            touchTimeout = 220;
        }

        String cmd = "settings put system pointer_speed " + pointerSpeed + "; "
                + "settings put secure multi_press_timeout " + touchTimeout + "; "
                + "settings put secure long_press_timeout " + touchTimeout + "; "
                + "settings put global window_animation_scale 0.0; "
                + "settings put global transition_animation_scale 0.0; "
                + "settings put global animator_duration_scale 0.0; "
                + "cmd power set-fixed-performance-mode-enabled true 2>/dev/null";
        execCommand(cmd);
    }

    public static void updateLiveConfig(int chunk) {
        microChunkSize = Math.max(10, Math.min(50, chunk));
        if (running.get() && isShizukuReady()) {
            new Thread(() -> applyDirectSensCommands(microChunkSize)).start();
        }
    }

    public static void updateLiveConfig(int chunk, int threshold, int zonePct, int pollHz, int scrW, int scrH) {
        microChunkSize = Math.max(10, Math.min(50, chunk));
        thresholdPx = Math.max(6, Math.min(40, threshold));
        pollRateHz = Math.max(60, Math.min(240, pollHz));
        if (scrW > 0 && scrH > 0) {
            screenWidth = Math.max(scrW, scrH);
            screenHeight = Math.min(scrW, scrH);
        }
        if (running.get() && isShizukuReady()) {
            new Thread(() -> applyDirectSensCommands(microChunkSize)).start();
        }
    }

    public static boolean isStandoffForeground() {
        String dump = execCommand("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        return dump != null && dump.contains("com.axlebolt.standoff2");
    }

    public static synchronized boolean startTeleport(int chunk) {
        if (!isShizukuReady()) {
            requestPermission();
            return false;
        }
        microChunkSize = Math.max(10, Math.min(50, chunk));
        new Thread(() -> applyDirectSensCommands(microChunkSize)).start();
        running.set(true);
        sessionTeleports.incrementAndGet();
        return true;
    }

    public static synchronized boolean startTeleport(int chunk, int threshold, int zonePct, int pollHz,
                                                     int scrW, int scrH, TeleportCallback callback) {
        if (!isShizukuReady()) {
            requestPermission();
            return false;
        }
        updateLiveConfig(chunk, threshold, zonePct, pollHz, scrW, scrH);
        new Thread(() -> {
            applyDirectSensCommands(microChunkSize);
            if (callback != null) callback.onTeleportSnap();
        }).start();
        running.set(true);
        sessionTeleports.incrementAndGet();
        return true;
    }

    public static synchronized void stopTeleport() {
        running.set(false);
        if (isShizukuReady()) {
            new Thread(() -> execCommand(
                    "settings put system pointer_speed 0; "
                    + "settings put secure multi_press_timeout 300; "
                    + "settings put secure long_press_timeout 400; "
                    + "cmd power set-fixed-performance-mode-enabled false 2>/dev/null"
            )).start();
        }
    }

    public static String setStretchConfig(String ratioCode, int baseShortSide, int baseLongSide, int baseDpi) {
        if (baseShortSide > 0 && baseLongSide > 0) {
            nativeShortSide = Math.min(baseShortSide, baseLongSide);
            nativeLongSide = Math.max(baseShortSide, baseLongSide);
        }
        if (baseDpi > 0) {
            nativeDpi = baseDpi;
        }
        if (ratioCode != null && !ratioCode.isEmpty()) {
            selectedStretchRatio = ratioCode;
        }
        return "Выбран формат " + selectedStretchRatio;
    }

    public static String applyStretch() {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        int targetLong;
        switch (selectedStretchRatio) {
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
            default:
                targetLong = Math.round(nativeShortSide * (16f / 9f));
                break;
        }
        if (targetLong % 2 != 0) targetLong++;
        int targetDpi = Math.max(240, Math.round(nativeDpi * 0.92f));

        execCommand("wm size " + nativeShortSide + "x" + targetLong + "; wm density " + targetDpi);
        screenWidth = targetLong;
        screenHeight = nativeShortSide;
        stretchActive = true;
        return "Растяг " + selectedStretchRatio + " включён";
    }

    public static String resetStretch() {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        execCommand("wm size reset; wm density reset");
        screenWidth = nativeLongSide;
        screenHeight = nativeShortSide;
        stretchActive = false;
        return "Экран сброшен по умолчанию";
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
        return "ОЗУ успешно очищена!";
    }

    public static void setDoNotDisturb(boolean enable) {
        if (!isShizukuReady()) return;
        execCommand(enable ? "cmd notification set_dnd priority 2>/dev/null" : "cmd notification set_dnd off 2>/dev/null");
    }
}
