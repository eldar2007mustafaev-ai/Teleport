package com.teleport.sens;

import android.os.IBinder;
import android.os.RemoteException;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class ShizukuInputInjector {
    
    private static Object inputManager = null;
    private static Method injectInputEventMethod = null;
    
    public static boolean init() {
        try {
            // Получаем InputManager через Shizuku
            IBinder binder = ShizukuBinderWrapper.getBinder("input");
            if (binder == null) return false;
            
            // Получаем класс InputManager
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            
            // Получаем метод getInstance()
            Method getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance");
            inputManager = getInstanceMethod.invoke(null);
            
            // Получаем метод injectInputEvent()
            injectInputEventMethod = inputManagerClass.getDeclaredMethod(
                "injectInputEvent", 
                InputEvent.class, 
                int.class
            );
            injectInputEventMethod.setAccessible(true);
            
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean injectMotionEvent(MotionEvent event) {
        if (injectInputEventMethod == null || inputManager == null) {
            if (!init()) return false;
        }
        
        try {
            // INJECT_INPUT_EVENT_MODE_ASYNC = 2
            return (boolean) injectInputEventMethod.invoke(inputManager, event, 2);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean isAvailable() {
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
