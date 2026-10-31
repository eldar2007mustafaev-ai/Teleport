package com.teleport.so2;

import android.content.pm.PackageManager;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;

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
                Shizuku.requestPermission(0);
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable e) {
            return false;
        }
    }

    public static void execCommand(String command) {
        try {
            if (!isPermissionGranted()) return;

            String[] shellCmd = new String[]{ "sh", "-c", command };

            Method newProcessMethod = Shizuku.class.getDeclaredMethod(
                    "newProcess",
                    String[].class,
                    String[].class,
                    String.class
            );
            newProcessMethod.setAccessible(true);

            Process process = (Process) newProcessMethod.invoke(null, shellCmd, null, null);
            if (process != null) {
                process.waitFor();
            }
        } catch (Throwable ignored) {}
    }
}
