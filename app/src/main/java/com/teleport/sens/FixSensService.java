package com.teleport.sens;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public class FixSensService extends AccessibilityService {
    
    private static final String TAG = "FixSens";
    private static FixSensService instance;
    private boolean isActive = false;
    private Handler mainHandler;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        Log.i(TAG, "FixSens Service создан");
    }
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler.post(() -> 
            Toast.makeText(this, "✅ FixSens активен", Toast.LENGTH_SHORT).show()
        );
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }
    
    @Override
    public void onInterrupt() {
        instance = null;
    }
    
    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
    
    public static FixSensService getInstance() {
        return instance;
    }
    
    public boolean isServiceRunning() {
        return instance != null;
    }
    
    public void setActive(boolean active) {
        this.isActive = active;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public void setProcessorParams(float sharpness, float noise, float straighten) {
    }
    
    public float[] getProcessorParams() {
        return new float[]{1.30f, 1.0f, 0.60f};
    }
}
