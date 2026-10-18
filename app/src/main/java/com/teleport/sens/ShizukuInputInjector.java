package com.teleport.sens;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public class ShizukuInputInjector {
    
    private static final String TAG = "ShizukuInjector";
    private static boolean initialized = false;
    private static boolean available = false;
    private static Object inputManager = null;
    private static Method injectMethod = null;
    
    public static boolean init() {
        if (initialized) return available;
        
        try {
            // 1. Проверяем наличие Shizuku класса
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            
            // 2. pingBinder
            Method pingBinder = shizukuClass.getMethod("pingBinder");
            boolean isRunning = (Boolean) pingBinder.invoke(null);
            if (!isRunning) {
                Log.w(TAG, "Shizuku не запущен");
                initialized = true;
                available = false;
                return false;
            }
            
            // 3. checkSelfPermission
            Method checkPerm = shizukuClass.getMethod("checkSelfPermission");
            int permResult = (Integer) checkPerm.invoke(null);
            if (permResult != 0) {
                Log.w(TAG, "Нет разрешения Shizuku");
                initialized = true;
                available = false;
                return false;
            }
            
            // 4. Получаем Shizuku binder
            Method getBinder = shizukuClass.getMethod("getBinder");
            IBinder shizukuBinder = (IBinder) getBinder.invoke(null);
            if (shizukuBinder == null) {
                Log.e(TAG, "Shizuku binder = null");
                initialized = true;
                available = false;
                return false;
            }
            
            // 5. Получаем input service через транзакцию
            IBinder inputBinder = getInputService(shizukuBinder);
            if (inputBinder == null) {
                Log.e(TAG, "input service = null");
                initialized = true;
                available = false;
                return false;
            }
            
            // 6. IInputManager.Stub.asInterface
            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            inputManager = asInterface.invoke(null, inputBinder);
            
            // 7. injectInputEvent метод
            Class<?> iimClass = Class.forName("android.hardware.input.IInputManager");
            injectMethod = iimClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            
            initialized = true;
            available = true;
            Log.i(TAG, "✅ Shizuku INJECT_EVENTS готов!");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка: " + e.getMessage(), e);
            initialized = true;
            available = false;
            return false;
        }
    }
    
    private static IBinder getInputService(IBinder shizukuBinder) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.IServiceManager");
                data.writeString("input");
                data.writeInt(0);
                shizukuBinder.transact(1, data, reply, 0);
                reply.readException();
                return reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "getInputService error: " + e.getMessage());
            return null;
        }
    }
    
    public static boolean injectMotionEvent(MotionEvent event) {
        if (!available) {
            if (!init()) return false;
        }
        if (inputManager == null || injectMethod == null) return false;
        
        try {
            Boolean result = (Boolean) injectMethod.invoke(inputManager, event, 2);
            return result != null && result;
        } catch (Exception e) {
            Log.e(TAG, "inject error: " + e.getMessage());
            return false;
        }
    }
    
    public static boolean isAvailable() { return available; }
    
    public static String getStatus() {
        if (!initialized) return "Не инициализирован";
        if (available) return "✅ Shizuku INJECT_EVENTS работает";
        return "❌ Shizuku недоступен";
    }
    
    public static void reset() {
        initialized = false;
        available = false;
        inputManager = null;
        injectMethod = null;
    }
}
