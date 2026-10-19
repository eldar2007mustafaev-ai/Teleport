package com.teleport.sens;

public class TouchProcessor {
    private float sharpness = 1.30f;
    private float noiseFilter = 1.0f;
    private float straighten = 0.60f;
    
    private float lastRawX = 0, lastRawY = 0;
    private float lastOutX = 0, lastOutY = 0;
    private boolean isFirst = true;
    
    public void setParams(float sharpness, float noise, float straighten) {
        this.sharpness = sharpness;
        this.noiseFilter = noise;
        this.straighten = straighten;
    }
    
    public float[] getParams() {
        return new float[]{sharpness, noiseFilter, straighten};
    }
    
    public float[] process(float rawX, float rawY) {
        if (isFirst) {
            lastRawX = rawX;
            lastRawY = rawY;
            lastOutX = rawX;
            lastOutY = rawY;
            isFirst = false;
            return new float[]{rawX, rawY};
        }
        
        float dx = rawX - lastRawX;
        float dy = rawY - lastRawY;
        
        // Умножаем на резкость
        float boostedDx = dx * sharpness;
        float boostedDy = dy * sharpness;
        
        float newX = lastOutX + boostedDx;
        float newY = lastOutY + boostedDy;
        
        lastRawX = rawX;
        lastRawY = rawY;
        lastOutX = newX;
        lastOutY = newY;
        
        return new float[]{newX, newY};
    }
    
    public void reset() {
        isFirst = true;
    }
}
