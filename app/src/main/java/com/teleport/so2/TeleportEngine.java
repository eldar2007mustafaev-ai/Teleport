package com.teleport.so2;

public class TeleportEngine {
    private static boolean stepQuantizationEnabled = true;
    private static float snapPowerMultiplier = 3.5f; // 350% ULTRA MAX по умолчанию

    public static void setStepQuantization(boolean enabled, float multiplier) {
        stepQuantizationEnabled = enabled;
        snapPowerMultiplier = Math.max(1.0f, multiplier);
    }

    /**
     * Вычисляет агрессивный телепорт-импульс для мгновенного флика.
     */
    public static float computeTeleportDelta(float rawDelta) {
        float abs = Math.abs(rawDelta);
        if (abs < 0.04f) return 0f;

        if (!stepQuantizationEnabled) {
            return rawDelta * snapPowerMultiplier;
        }

        // Жесткий стартовый шаг для микро-фликов (убирает вязкость сенсора):
        float minSnapStep = 11.5f * snapPowerMultiplier;

        // Нелинейный разгон без промежуточного сглаживания:
        float nonLinearBoost = (float) Math.pow(abs, 1.38f) * (3.2f * snapPowerMultiplier);

        float finalStep = Math.max(nonLinearBoost, minSnapStep);
        return Math.copySign(finalStep, rawDelta);
    }
}
