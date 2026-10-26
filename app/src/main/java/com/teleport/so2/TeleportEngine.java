package com.teleport.so2;

public class TeleportEngine {
    
    private static boolean stepQuantizationEnabled = false;
    private static float snapPowerMultiplier = 1.0f;

    /**
     * Включает/выключает квантование шага и задает множитель силы
     */
    public static void setStepQuantization(boolean enabled, float multiplier) {
        stepQuantizationEnabled = enabled;
        // Ограничиваем множитель разумными пределами (от 0.5x до 4.0x)
        snapPowerMultiplier = Math.max(0.5f, Math.min(4.0f, multiplier));
    }

    /**
     * Вычисляет дельту для телепорта с минимальным квантованным прыжком
     */
    public static float computeTeleportDelta(float rawDelta, float snapMultiplier) {
        if (Math.abs(rawDelta) < 0.15f) return 0f;
        
        float minSnapStep = 6.5f * snapMultiplier;
        float boosted = Math.abs(rawDelta) * (1.85f * snapMultiplier);
        float finalStep = Math.max(boosted, minSnapStep);
        
        return Math.copySign(finalStep, rawDelta);
    }
}
