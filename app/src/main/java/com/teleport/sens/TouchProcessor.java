package com.teleport.sens;

public class TouchProcessor {
    // Три магических числа как в FixSens
    private float sharpness = 1.30f;    // резкость (умножитель delta)
    private float noiseFilter = 1.0f;   // фильтр шума (меньше = сильнее фильтр)
    private float straighten = 0.60f;   // выпрямление (0 = нет, 1 = максимум)
    
    private float lastRawX = 0, lastRawY = 0;
    private float lastOutX = 0, lastOutY = 0;
    private boolean isFirst = true;
    
    // Для выпрямления
    private float accumulatedDx = 0;
    private float accumulatedDy = 0;
    private int moveSamples = 0;
    
    public void setParams(float sharpness, float noise, float straighten) {
        this.sharpness = sharpness;
        this.noiseFilter = noise;
        this.straighten = straighten;
    }
    
    public float[] getParams() {
        return new float[]{sharpness, noiseFilter, straighten};
    }
    
    /**
     * Обрабатывает сырое touch-событие и возвращает модифицированные координаты
     */
    public float[] process(float rawX, float rawY) {
        if (isFirst) {
            lastRawX = rawX;
            lastRawY = rawY;
            lastOutX = rawX;
            lastOutY = rawY;
            isFirst = false;
            return new float[]{rawX, rawY};
        }
        
        // 1. Считаем delta движения пальца
        float dx = rawX - lastRawX;
        float dy = rawY - lastRawY;
        
        // 2. Применяем резкость (умножаем delta) — это и даёт "телепорт"
        float boostedDx = dx * sharpness;
        float boostedDy = dy * sharpness;
        
        // 3. Фильтр шума (exponential moving average)
        float alpha = 1.0f / (1.0f + noiseFilter);
        float filteredX = lastOutX + boostedDx * alpha + lastOutX * (1 - alpha);
        float filteredY = lastOutY + boostedDy * alpha + lastOutY * (1 - alpha);
        
        // Проще: low-pass фильтр
        float smoothX = lastOutX + (boostedDx * (1.0f - noiseFilter * 0.3f));
        float smoothY = lastOutY + (boostedDy * (1.0f - noiseFilter * 0.3f));
        
        float newX = smoothX;
        float newY = smoothY;
        
        // 4. Выпрямление (snap to dominant axis)
        if (straighten > 0.01f) {
            accumulatedDx += Math.abs(boostedDx);
            accumulatedDy += Math.abs(boostedDy);
            moveSamples++;
            
            if (moveSamples >= 3) {
                // Определяем доминирующую ось
                if (accumulatedDx > accumulatedDy * 1.5f) {
                    // Горизонтальное движение — обнуляем вертикаль
                    newY = lastOutY + (newY - lastOutY) * (1.0f - straighten);
                } else if (accumulatedDy > accumulatedDx * 1.5f) {
                    // Вертикальное движение — обнуляем горизонталь
                    newX = lastOutX + (newX - lastOutX) * (1.0f - straighten);
                }
                
                // Сброс накопления
                accumulatedDx = 0;
                accumulatedDy = 0;
                moveSamples = 0;
            }
        }
        
        // Сохраняем для следующего кадра
        lastRawX = rawX;
        lastRawY = rawY;
        lastOutX = newX;
        lastOutY = newY;
        
        return new float[]{newX, newY};
    }
    
    public void reset() {
        isFirst = true;
        accumulatedDx = 0;
        accumulatedDy = 0;
        moveSamples = 0;
    }
    
    // Пресеты как в FixSens
    public static final float[] PRESET_CLEAN = {1.00f, 3.0f, 0.80f};
    public static final float[] PRESET_SHARP = {1.30f, 1.0f, 0.60f};
    public static final float[] PRESET_MAX = {1.30f, 1.0f, 0.90f};
    public static final float[] PRESET_CLEAN_SHARP = {1.30f, 1.0f, 0.00f};
    public static final float[] PRESET_HUNT = {1.50f, 1.0f, 0.70f};
    public static final float[] PRESET_HIGH_FPS = {1.20f, 2.0f, 0.80f};
}
