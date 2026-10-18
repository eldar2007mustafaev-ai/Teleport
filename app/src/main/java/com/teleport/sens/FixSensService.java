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
    
    // Для отслеживания текущего touch
    private float lastInjectedX = 0;
    private float lastInjectedY = 0;
    private boolean isTouching = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        processor = new TouchProcessor();
        processor.setParams(1.30f, 1.0f, 0.60f);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Инициализируем Shizuku инжектор
        useShizuku = ShizukuInputInjector.init();
        Log.i(TAG, "Shizuku инжектор: " + (useShizuku ? "✅ доступен" : "❌ недоступен, используем fallback"));
    }
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler.post(() -> {
            String msg = useShizuku ? 
                "✅ FixSens активен (Shizuku INJECT)" : 
                "⚠️ FixSens активен (fallback режим)";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
        Log.i(TAG, "FixSens Service подключён");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Отслеживаем начало/конец touch
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            processor.reset();
            isTouching = true;
        } else if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            isTouching = false;
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
            isTouching = false;
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
     * Главная функция: обрабатывает touch и инжектит модифицированное событие
     */
    public void processAndInjectTouch(float rawX, float rawY, int action) {
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
        
        boolean injected = false;
        
        // Пробуем через Shizuku
        if (useShizuku) {
            injected = ShizukuInputInjector.injectMotionEvent(event);
            if (injected) {
                lastInjectedX = newX;
                lastInjectedY = newY;
            } else {
                Log.w(TAG, "Shizuku инжекция не удалась, fallback");
            }
        }
        
        // Fallback: если Shizuku не сработал, используем dispatchGesture
        if (!injected) {
            injectGestureFallback(rawX, rawY, newX, newY);
        }
        
        event.recycle();
    }
    
    /**
     * Fallback: инъекция через dispatchGesture (Accessibility API)
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
    
    public boolean isUsingShizuku() {
        return useShizuku;
    }
}
