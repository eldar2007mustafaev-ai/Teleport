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

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger sessionTeleports = new AtomicInteger(0);
    private static volatile String lastError = "";

    // Текущий размер чанка/пресета (18 - Сенса, 30 - Макс резкая, 44 - Телепорт, 12 - Плавная)
    private static volatile int microChunkSize = 30;

    // Параметры растяга для плавающей кнопки (FloatingButtonService)
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
     * Применяет системные aShell / Shizuku твики сенсора и ввода без перехвата экрана.
     * Полностью исключает фантомные нажатия.
     */
    private static void applyAShellTeleportConfig(int chunk) {
        int pointerSpeed;
        int longPressTimeout;
        int multiPressTimeout;
        String scrollFriction;

        if (chunk >= 40) {
            // Пресет "Телепорт (44 пикс.)" — максимальная резкость и мгновенный отклик
            pointerSpeed = 7;
            longPressTimeout = 180;
            multiPressTimeout = 120;
            scrollFriction = "0.001";
        } else if (chunk >= 25) {
            // Пресет "Макс резкая (30 пикс.)" — агрессивный чанковый отклик
            pointerSpeed = 7;
            longPressTimeout = 200;
            multiPressTimeout = 150;
            scrollFriction = "0.003";
        } else if (chunk >= 16) {
            // Пресет "Сенса (18 пикс.)" — ускоренный спортивный отклик
            pointerSpeed = 5;
            longPressTimeout = 240;
            multiPressTimeout = 180;
            scrollFriction = "0.005";
        } else {
            // Пресет "Плавная (12 пикс.)" — стабильный мягкий трекинг
            pointerSpeed = 3;
            longPressTimeout = 300;
            multiPressTimeout = 220;
            scrollFriction = "0.008";
        }

        StringBuilder cmd = new StringBuilder();
        // Настройки скорости указателя и минимальных задержек тача через системные таблицы Android
        cmd.append("settings put system pointer_speed ").append(pointerSpeed).append("; ");
        cmd.append("settings put secure long_press_timeout ").append(longPressTimeout).append("; ");
        cmd.append("settings put secure multi_press_timeout ").append(multiPressTimeout).append("; ");
        cmd.append("settings put secure tap_duration_threshold 0.0; ");
        cmd.append("settings put secure touch_blocking_period 0.0; ");

        // Свойства SurfaceFlinger и InputDispatcher (как в aShell скриптах для мгновенного отклика)
        cmd.append("setprop debug.sf.latch_unsignaled 1 2>/dev/null; ");
        cmd.append("setprop debug.sf.disable_backpressure 1 2>/dev/null; ");
        cmd.append("setprop debug.hwui.use_hint_manager true 2>/dev/null; ");
        cmd.append("setprop view.scroll_friction ").append(scrollFriction).append(" 2>/dev/null; ");
        cmd.append("cmd power set-fixed-performance-mode-enabled true 2>/dev/null; ");

        execCommand(cmd.toString());
        sessionTeleports.incrementAndGet();
    }

    /**
     * Обновляет конфигурацию на лету при переключении пресетов.
     */
    public static void updateLiveConfig(int chunk, int threshold, int zonePct, int pollHz, int scrW, int scrH) {
        microChunkSize = chunk;
        if (running.get() && isShizukuReady()) {
            new Thread(() -> applyAShellTeleportConfig(microChunkSize)).start();
        }
    }

    /**
     * Проверяет, активно ли приложение com.axlebolt.standoff2 на экране.
     */
    public static boolean isStandoffForeground() {
        String dump = execCommand("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
        return dump != null && dump.contains("com.axlebolt.standoff2");
    }

    /**
     * Запускает режим телепорта сенсы через Shizuku (aShell-команды) без перехвата касаний.
     */
    public static synchronized boolean startTeleport(int chunk, int threshold, int zonePct, int pollHz,
                                                     int scrW, int scrH, TeleportCallback callback) {
        if (!isShizukuReady()) {
            requestPermission();
            return false;
        }

        microChunkSize = chunk;
        running.set(true);

        new Thread(() -> {
            applyAShellTeleportConfig(microChunkSize);
            if (callback != null) {
                callback.onTeleportSnap();
            }
        }, "SO2-AShell-Teleport-Thread").start();

        return true;
    }

    /**
     * Останавливает режим телепорта и возвращает стандартные настройки тача Android.
     */
    public static synchronized void stopTeleport() {
        if (!running.getAndSet(false)) return;
        if (!isShizukuReady()) return;

        new Thread(() -> {
            String resetCmd =
                    "settings put system pointer_speed 0; " +
                    "settings put secure long_press_timeout 400; " +
                    "settings put secure multi_press_timeout 300; " +
                    "setprop view.scroll_friction 0.015 2>/dev/null; " +
                    "cmd power set-fixed-performance-mode-enabled false 2>/dev/null;";
            execCommand(resetCmd);
        }, "SO2-AShell-Reset-Thread").start();
    }

    /**
     * Сохраняет выбранное соотношение сторон и базовые параметры экрана для плавающей кнопки.
     */
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
        return "Выбран формат " + selectedStretchRatio + " для кнопки растяга";
    }

    /**
     * Применяет wm size и wm density (вызывается из FloatingButtonService).
     */
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
        stretchActive = true;
        return "Растяг " + selectedStretchRatio + " включён (" + nativeShortSide + "x" + targetLong + ")";
    }

    /**
     * Сбрасывает разрешение и плотность через wm size reset и wm density reset.
     */
    public static String resetStretch() {
        if (!isShizukuReady()) return "Нет прав Shizuku";
        execCommand("wm size reset; wm density reset");
        stretchActive = false;
        return "Экран растянут (сброшен к родным параметрам)";
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
