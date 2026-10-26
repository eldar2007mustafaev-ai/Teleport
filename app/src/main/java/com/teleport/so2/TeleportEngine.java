package com.teleport.so2;

public class TeleportEngine {
    private static boolean active = false;
    private static boolean stepQuantizationEnabled = true;
    private static float snapPowerMultiplier = 4.0f; // Максимальная резкость (400%)
    private static String currentPreset = "teleport";

    public static void setStepQuantization(boolean enabled, float multiplier) {
        stepQuantizationEnabled = enabled;
        snapPowerMultiplier = Math.max(1.0f, multiplier);
    }

    public static boolean isActive() {
        return active;
    }

    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (abs < 0.025f) return 0f; // Уменьшена мертвая зона для моментального отклика

        if (!stepQuantizationEnabled) {
            return rawDelta * snapPowerMultiplier;
        }

        // Агрессивный стартовый шаг и мгновенный перенос без сглаживания
        float minSnapStep = 16.0f * snapPowerMultiplier;
        float nonLinearBoost = (float) Math.pow(abs, 1.25f) * (4.5f * snapPowerMultiplier);
        float finalStep = Math.max(nonLinearBoost, minSnapStep);
        return Math.copySign(finalStep, rawDelta);
    }

    public static void startTeleport(int snapPercent, String presetId) {
        active = true;
        currentPreset = presetId != null ? presetId : "teleport";
        snapPowerMultiplier = Math.max(1.0f, snapPercent / 100.0f);

        if (!ShizukuManager.isPermissionGranted()) return;

        new Thread(() -> {
            String cmd =
                "settings put system pointer_speed 7; " +
                "settings put secure long_press_timeout 90; " +
                "settings put secure multi_press_timeout 80; " +
                "settings put secure tap_duration_threshold 0.0; " +
                "settings put secure touch_blocking_period 0.0; " +
                "setprop debug.sf.latch_unsignaled 1; " +
                "setprop debug.sf.disable_backpressure 1; " +
                "setprop debug.hwui.use_buffer_age false; " +
                "setprop windowsmgr.max_events_per_sec 380; " +
                "setprop ro.min_pointer_dur 1; " +
                "setprop touch.pressure.scale 0.0001; " +
                "setprop view.scroll_friction 0.0005; " +
                "setprop debug.sf.high_fps_early_phase_offset_ns -7000000; " +
                "setprop debug.sf.early_phase_offset_ns -7000000; " +
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
