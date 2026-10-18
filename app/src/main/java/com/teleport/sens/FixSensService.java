package com.teleport.sens;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class FixSensService extends AccessibilityService {
    
    private static final String TAG = "FixSens";
    private static FixSensService instance;
    
    private TouchProcessor processor;
    private boolean isActive = false;
    private Handler mainHandler;
    private boolean useShizuku = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        processor = new TouchProcessor();
        processor.setParams(1.30f, 1.0f, 0.60f);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Проверяем доступность Shizuku
        if (ShizukuInputInjector.isAvailable()) {
            useShizuku = ShizukuInputInjector.init();
            Log.i(TAG, "Shizuku " + (useShizuku ? "доступен" : "недоступен"));
        }
        
        Log.i(TAG, "FixSens Service создан");
    }
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler.post(() -> 
            Toast.makeText(this, "✅ FixSens сервис активен", Toast.LENGTH_SHORT).show()
        );
        Log.i(TAG, "FixSens Service подключён");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            processor.reset();
        }
    }
    
    @Override
    public void onInterrupt() {
        instance = null;
        Log.w(TAG, "FixSens Service прерван");
    }
    
    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
        Log.i(TAG, "FixSens Service уничтожен");
    }
    
    public static FixSensService getInstance() {
        return instance;
    }
    
    public boolean isServiceRunning() {
        return instance != null;
    }
    
    public void setActive(boolean active) {
        this.isActive = active;
        if (!active) {
            processor.reset();
        }
        Log.i(TAG, "FixSens " + (active ? "ВКЛ" : "ВЫКЛ"));
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public void setProcessorParams(float sharpness, float noise, float straighten) {
        if (processor != null) {
            processor.setParams(sharpness, noise, straighten);
            Log.i(TAG, String.format("Параметры: %.2f · %.2f · %.2f", 
                sharpness, noise, straighten));
        }
    }
    
    public float[] getProcessorParams() {
        if (processor != null) {
            return processor.getParams();
        }
        return new float[]{1.30f, 1.0f, 0.60f};
    }
    
    /**
     * Инжекция модифицированного touch-события через Shizuku
     */
    public void injectModifiedTouch(float rawX, float rawY, int action) {
        if (!isActive) return;
        
        // Модифицируем координаты через TouchProcessor
        float[] modified = processor.process(rawX, rawY);
        float newX = modified[0];
        float newY = modified[1];
        
        // Создаём MotionEvent
        long now = System.currentTimeMillis();
        MotionEvent event = MotionEvent.obtain(
            now, now, action, newX, newY, 0
        );
        
        // Инжектим через Shizuku
        if (useShizuku) {
            boolean success = ShizukuInputInjector.injectMotionEvent(event);
            if (!success) {
                Log.w(TAG, "Shizuku инъекция не удалась, используем fallback");
                injectGestureFallback(rawX, rawY, newX, newY);
            }
        } else {
            // Fallback: используем dispatchGesture
            injectGestureFallback(rawX, rawY, newX, newY);
        }
        
        event.recycle();
    }
    
    /**
     * Fallback: инъекция через dispatchGesture (если Shizuku недоступен)
     */
    private void injectGestureFallback(float fromX, float fromY, float toX, float toY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        
        GestureDescription.StrokeDescription stroke = 
            new GestureDescription.StrokeDescription(path, 0, 10);
        
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();
        
        dispatchGesture(gesture, null, mainHandler);
    }
    
    public float[] processTouch(float rawX, float rawY) {
        if (!isActive) return new float[]{rawX, rawY};
        return processor.process(rawX, rawY);
    }
}
