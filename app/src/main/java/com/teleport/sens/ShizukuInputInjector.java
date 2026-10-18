package com.teleport.sens;

import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public class ShizukuInputInjector {
    
    private static final String TAG = "ShizukuInjector";
    private static boolean initialized = false;
    private static boolean available = false;
    private static Object inputManager = null;
    private static Method injectMethod = null;
    
    /**
     * Инициализация через рефлексию (не требует импорта Shizuku)
     */
    public static boolean init() {
        if (initialized) return available;
        
        try {
            // 1. Проверяем наличие класса Shizuku
            Class<?> shizukuClass;
            try {
                shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "Shizuku библиотека не найдена в classpath");
                initialized = true;
                available = false;
                return false;
            }
            
            // 2. Проверяем pingBinder()
            Method pingBinder = shizukuClass.getMethod("pingBinder");
            boolean isRunning = (Boolean) pingBinder.invoke(null);
            if (!isRunning) {
                Log.w(TAG, "Shizuku сервис не запущен");
                initialized = true;
                available = false;
                return false;
            }
            
            // 3. Проверяем разрешение
            Method checkPermission = shizukuClass.getMethod("checkSelfPermission");
            int permResult = (Integer) checkPermission.invoke(null);
            if (permResult != 0) { // PERMISSION_GRANTED = 0
                Log.w(TAG, "Нет разрешения Shizuku (код: " + permResult + ")");
                initialized = true;
                available = false;
                return false;
            }
            
            // 4. Получаем binder Shizuku
            Method getBinder = shizukuClass.getMethod("getBinder");
            IBinder shizukuBinder = (IBinder) getBinder.invoke(null);
            if (shizukuBinder == null) {
                Log.e(TAG, "Shizuku binder = null");
                initialized = true;
                available = false;
                return false;
            }
            
            // 5. Получаем input binder через Shizuku
            IBinder inputBinder = getInputService(shizukuBinder);
            if (inputBinder == null) {
                Log.e(TAG, "Не удалось получить input service");
                initialized = true;
                available = false;
                return false;
            }
            
            // 6. Получаем IInputManager через рефлексию
            Class<?> inputManagerClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = inputManagerClass.getMethod("asInterface", IBinder.class);
            inputManager = asInterface.invoke(null, inputBinder);
            
            // 7. Получаем метод injectInputEvent
            Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
            injectMethod = iInputManagerClass.getMethod("injectInputEvent", 
                android.view.InputEvent.class, int.class);
            
            initialized = true;
            available = true;
            Log.i(TAG, "✅ Shizuku INJECT_EVENTS готов!");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инициализации: " + e.getMessage(), e);
            initialized = true;
            available = false;
            return false;
        }
    }
    
    /**
     * Получаем binder сервиса input через Shizuku
     */
    private static IBinder getInputService(IBinder shizukuBinder) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            
            try {
                data.writeInterfaceToken("android.os.IServiceManager");
                data.writeString("input");
                data.writeInt(0);
                
                // TRANSACTION_GET_SERVICE = 1
                shizukuBinder.transact(1, data, reply, 0);
                reply.readException();
                return reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка получения input service: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Инжектим MotionEvent
     */
    public static boolean injectMotionEvent(MotionEvent event) {
        if (!available) {
            if (!init()) return false;
        }
        
        if (inputManager == null || injectMethod == null) {
            Log.e(TAG, "inputManager или injectMethod = null");
            return false;
        }
        
        try {
            // INJECT_INPUT_EVENT_MODE_ASYNC = 2
            Boolean result = (Boolean) injectMethod.invoke(inputManager, event, 2);
            return result != null && result;
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инжекции: " + e.getMessage(), e);
            return false;
        }
    }
    
    public static boolean isAvailable() {
        return available;
    }
    
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
