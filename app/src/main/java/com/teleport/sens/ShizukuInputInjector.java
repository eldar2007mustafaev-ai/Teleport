package com.teleport.sens;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public class ShizukuInputInjector {
    
    private static final String TAG = "ShizukuInjector";
    private static IBinder inputBinder = null;
    private static boolean initialized = false;
    private static boolean available = false;
    
    // Код транзакции для injectInputEvent в IInputManager
    // Android 10-14: обычно 39, но может меняться
    private static final int TRANSACTION_INJECT_INPUT_EVENT = 39;
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 2;
    
    /**
     * Инициализация — получаем binder сервиса input через Shizuku
     */
    public static boolean init() {
        if (initialized) return available;
        
        try {
            // Проверяем что Shizuku доступен
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku binder не отвечает");
                initialized = true;
                available = false;
                return false;
            }
            
            // Проверяем разрешение
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Нет разрешения Shizuku");
                initialized = true;
                available = false;
                return false;
            }
            
            // Получаем binder сервиса "input" через Shizuku
            // Это эквивалент ServiceManager.getService("input") но через Shizuku
            IBinder binder = getInputBinderViaShizuku();
            
            if (binder == null) {
                Log.e(TAG, "Не удалось получить input binder");
                initialized = true;
                available = false;
                return false;
            }
            
            inputBinder = binder;
            initialized = true;
            available = true;
            
            Log.i(TAG, "✅ ShizukuInputInjector инициализирован успешно");
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
    private static IBinder getInputBinderViaShizuku() {
        try {
            // Используем Shizuku для вызова ServiceManager.getService("input")
            // Shizuku позволяет выполнять код в контексте system_server
            
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            
            try {
                data.writeInterfaceToken("android.os.IServiceManager");
                data.writeString("input");
                data.writeInt(0); // userId
                
                // Получаем binder Shizuku service
                IBinder shizukuBinder = Shizuku.getBinder();
                if (shizukuBinder == null) {
                    Log.e(TAG, "Shizuku binder = null");
                    return null;
                }
                
                // Транзакция GET_SERVICE = 1
                boolean success = shizukuBinder.transact(1, data, reply, 0);
                
                if (success) {
                    reply.readException();
                    IBinder binder = reply.readStrongBinder();
                    return binder;
                } else {
                    Log.e(TAG, "Транзакция не удалась");
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка получения binder: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Инжектим MotionEvent через Shizuku
     */
    public static boolean injectMotionEvent(MotionEvent event) {
        if (!available) {
            if (!init()) return false;
        }
        
        if (inputBinder == null) {
            Log.e(TAG, "inputBinder = null");
            return false;
        }
        
        try {
            Parcel data = Parcel.obtain();
            
            try {
                data.writeInterfaceToken("android.hardware.input.IInputManager");
                data.writeParcelable(event, 0);
                data.writeInt(INJECT_INPUT_EVENT_MODE_ASYNC);
                
                boolean success = inputBinder.transact(
                    TRANSACTION_INJECT_INPUT_EVENT, 
                    data, 
                    null, 
                    IBinder.FLAG_ONEWAY
                );
                
                if (!success) {
                    Log.w(TAG, "Транзакция injectInputEvent не удалась");
                    return false;
                }
                
                return true;
                
            } finally {
                data.recycle();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Ошибка инжекции: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Проверяет доступен ли инжектор
     */
    public static boolean isAvailable() {
        return available;
    }
    
    /**
     * Сброс состояния (например, после потери binder)
     */
    public static void reset() {
        initialized = false;
        available = false;
        inputBinder = null;
    }
    
    /**
     * Получает статус для отображения в UI
     */
    public static String getStatus() {
        if (!Shizuku.pingBinder()) {
            return "Shizuku не запущен";
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Нет разрешения Shizuku";
        }
        if (!initialized) {
            return "Не инициализирован";
        }
        if (available) {
            return "✅ Работает (INJECT_EVENTS)";
        }
        return "❌ Binder недоступен";
    }
}
