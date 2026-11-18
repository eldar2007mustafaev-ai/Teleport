package com.teleport.so2;

import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import java.io.OutputStream;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuManager {

    public static boolean isPermissionGranted() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                return false;
            } else {
                Shizuku.requestPermission(1001);
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable e) {
            return false;
        }
    }

    public static void execCommand(String command) {
        try {
            if (!isPermissionGranted()) return;

            Process process = null;
            try {
                Method newProcessMethod = Shizuku.class.getDeclaredMethod(
                        "newProcess",
                        String[].class,
                        String[].class,
                        String.class
                );
                newProcessMethod.setAccessible(true);
                process = (Process) newProcessMethod.invoke(
                        null,
                        new String[]{"sh"},
                        null,
                        null
                );
                if (process != null) {
                    OutputStream os = process.getOutputStream();
                    os.write((command + "\nexit\n").getBytes());
                    os.flush();
                    os.close();
                    process.waitFor();
                    return;
                }
            } catch (Throwable ignored) {}

            // Резервный прямой вызов sh -c, если поток ввода закрыт
            for (Method m : Shizuku.class.getDeclaredMethods()) {
                if ("newProcess".equals(m.getName())) {
                    m.setAccessible(true);
                    Process p = (Process) m.invoke(null, new String[]{"sh", "-c", command}, null, null);
                    if (p != null) {
                        p.waitFor();
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }
}
