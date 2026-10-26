package com.teleport.so2;

import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;

public class ShizukuManager {

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPermissionGranted() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasPermission() {
        return isPermissionGranted();
    }

    public static void requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(1001);
                }
            }
        } catch (Exception ignored) {}
    }

    public static String execCommand(String command) {
        if (!isPermissionGranted()) return "";
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            Method newProcessMethod = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class
            );
            newProcessMethod.setAccessible(true);
            process = (Process) newProcessMethod.invoke(
                    null, new String[]{"sh", "-c", command}, null, null
            );

            if (process != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                process.waitFor();
            }
        } catch (Exception e) {
            try {
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
        return output.toString().trim();
    }

    public static String executeCommand(String command) {
        return execCommand(command);
    }

    public static String runCommand(String command) {
        return execCommand(command);
    }
}
