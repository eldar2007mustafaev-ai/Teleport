package com.teleport.so2;

public class TeleportEngine {
    private static boolean active = false;
    private static boolean stepQuantizationEnabled = true;
    private static float snapPowerMultiplier = 6.0f; // Экстремальный максимум (600%)
    private static String currentPreset = "teleport";

    public static void setStepQuantization(boolean enabled, float multiplier) {
        stepQuantizationEnabled = enabled;
        snapPowerMultiplier = Math.max(1.0f, multiplier);
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Экстремальный расчет дельты флика: убирает мертвую зону
     * и дает моментальный скачок прицела без промежуточного сглаживания.
     */
    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (abs < 0.01f) return 0f;

        if (!stepQuantizationEnabled) {
            return rawDelta * snapPowerMultiplier;
        }

        float minSnapStep = 22.0f * snapPowerMultiplier;
        float nonLinearBoost = (float) Math.pow(abs, 1.15f) * (6.0f * snapPowerMultiplier);
        float finalStep = Math.max(nonLinearBoost, minSnapStep);
        return Math.copySign(finalStep, rawDelta);
    }

    /**
     * Применение жестких системных твиков тача через Shizuku
     */
    public static void startTeleport(int snapPercent, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(2.0f, snapPercent / 60.0f);

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put system pointer_speed 7; " +
                "settings put secure long_press_timeout 50; " +
                "settings put secure multi_press_timeout 50; " +
                "settings put secure tap_duration_threshold 0.0; " +
                "settings put secure touch_blocking_period 0.0; " +
                "setprop debug.sf.latch_unsignaled 1; " +
                "setprop debug.sf.disable_backpressure 1; " +
                "setprop debug.hwui.use_buffer_age false; " +
                "setprop windowsmgr.max_events_per_sec 500; " +
                "setprop ro.min_pointer_dur 1; " +
                "setprop touch.pressure.scale 0.0001; " +
                "setprop view.scroll_friction 0.0; " +
                "setprop debug.sf.high_fps_early_phase_offset_ns -9000000; " +
                "setprop debug.sf.early_phase_offset_ns -9000000; " +
                "cmd game mode performance com.axlebolt.standoff2";

            ShizukuManager.execCommand(cmd);
        }).start();
    }

    public static void stopTeleport() {
        active = false;
        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put system pointer_speed 0; " +
                "settings put secure long_press_timeout 400; " +
                "settings put secure multi_press_timeout 300; " +
                "setprop debug.sf.latch_unsignaled 0; " +
                "setprop debug.sf.disable_backpressure 0";
            ShizukuManager.execCommand(cmd);
        }).start();
    }
}
