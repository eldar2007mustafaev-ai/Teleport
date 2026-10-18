package com.teleport.sens;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

public class ShizukuBinderWrapper {
    
    public static IBinder getBinder(String serviceName) {
        try {
            // Используем Shizuku для получения системного биндера
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            
            data.writeInterfaceToken("android.os.IServiceManager");
            data.writeString(serviceName);
            
            // Вызываем getService через Shizuku
            IBinder binder = Shizuku.getBinder();
            if (binder != null) {
                binder.transact(1, data, reply, 0); // 1 = getService
                IBinder result = reply.readStrongBinder();
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
