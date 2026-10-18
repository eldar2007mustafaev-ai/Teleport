package com.teleport.sens;

public class TouchProcessor {
    private float sharpness = 1.30f;
    private float noiseFilter = 1.0f;
    private float straighten = 0.60f;
    
    private float lastRawX = 0, lastRawY = 0;
    private float lastOutX = 0, lastOutY = 0;
    private boolean isFirst = true;
    
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
        
        float boostedDx = dx * sharpness;
        float boostedDy = dy * sharpness;
        
        float smoothX = lastOutX + (boostedDx * (1.0f - noiseFilter * 0.3f));
        float smoothY = lastOutY + (boostedDy * (1.0f - noiseFilter * 0.3f));
        
        float newX = smoothX;
        float newY = smoothY;
        
        if (straighten > 0.01f) {
            accumulatedDx += Math.abs(boostedDx);
            accumulatedDy += Math.abs(boostedDy);
            moveSamples++;
            
            if (moveSamples >= 3) {
                if (accumulatedDx > accumulatedDy * 1.5f) {
                    newY = lastOutY + (newY - lastOutY) * (1.0f - straighten);
                } else if (accumulatedDy > accumulatedDx * 1.5f) {
                    newX = lastOutX + (newX - lastOutX) * (1.0f - straighten);
                }
                
                accumulatedDx = 0;
                accumulatedDy = 0;
                moveSamples = 0;
            }
        }
        
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
}
