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
        
        // Инициализируем Shizuku
        useShizuku = ShizukuInputInjector.init();
        Log.i(TAG, "Shizuku: " + (useShizuku ? "✅ INJECT" : "⚠️ fallback"));
    }
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler.post(() -> {
            String msg = useShizuku ? 
                "✅ FixSens: Shizuku INJECT активен" : 
                "️ FixSens: fallback (нужен Shizuku)";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            processor.reset();
        }
    }
    
    @Override
    public void onInterrupt() { instance = null; }
    
    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
    
    public static FixSensService getInstance() { return instance; }
    public boolean isServiceRunning() { return instance != null; }
    public boolean isActive() { return isActive; }
    public boolean isUsingShizuku() { return useShizuku; }
    
    public void setActive(boolean active) {
        this.isActive = active;
        if (!active) processor.reset();
    }
    
    public void setProcessorParams(float s, float n, float st) {
        if (processor != null) processor.setParams(s, n, st);
    }
    
    public float[] getProcessorParams() {
        if (processor != null) return processor.getParams();
        return new float[]{1.30f, 1.0f, 0.60f};
    }
    
    public void processAndInjectTouch(float rawX, float rawY, int action) {
        if (!isActive) return;
        
        float[] modified = processor.process(rawX, rawY);
        float newX = modified[0];
        float newY = modified[1];
        
        long now = System.currentTimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, newX, newY, 0);
        
        boolean injected = false;
        if (useShizuku) {
            injected = ShizukuInputInjector.injectMotionEvent(event);
        }
        
        if (!injected) {
            injectGestureFallback(rawX, rawY, newX, newY);
        }
        
        event.recycle();
    }
    
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
}
