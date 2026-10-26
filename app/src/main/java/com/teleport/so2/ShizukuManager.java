package com.teleport.so2;

import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

public class ShizukuManager {

    public static boolean isPermissionGranted() {
        try {
            if (Shizuku.isPreV11()) {
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                return false;
            } else {
                Shizuku.requestPermission(0);
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0);
            }
        } catch (Exception ignored) {}
    }

    public static void execCommand(String command) {
        try {
            if (isPermissionGranted()) {
                String[] cmd = {"sh", "-c", command};
                Shizuku.newProcess(cmd, null, null);
            }
        } catch (Exception ignored) {}
    }
}
