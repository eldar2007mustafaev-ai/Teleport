package com.teleport.so2;

public class TeleportEngine {
    
    /**
     * Вычисляет дельту для телепорта с минимальным квантованным прыжком
     */
    public static float computeTeleportDelta(float rawDelta, float snapMultiplier) {
        if (Math.abs(rawDelta) < 0.15f) return 0f;
        
        // Минимальный квантованный прыжок даже на маленьком флике:
        float minSnapStep = 6.5f * snapMultiplier;
        float boosted = Math.abs(rawDelta) * (1.85f * snapMultiplier);
        float finalStep = Math.max(boosted, minSnapStep);
        
        return Math.copySign(finalStep, rawDelta);
    }
    
    /**
     * Включает/выключает квантование шага
     */
    public static void setStepQuantization(boolean enabled, float multiplier) {
        // Здесь можно добавить дополнительную логику при необходимости
        // Пока просто сохраняем параметры
    }
}
