package com.teleport.so2;

import android.content.Context;
import android.util.Log;
import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShizukuManager {
    
    private static final String TAG = "ShizukuManager";
    private static boolean teleportRunning = false;
    private static int sessionTeleports = 0;
    private static String selectedStretchRatio = "16:9";
    private static boolean stretchActive = false;

    public static boolean isPermissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() && isPermissionGranted();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (Shizuku.pingBinder() && !isPermissionGranted()) {
                Shizuku.requestPermission(1001);
            }
        } catch (Throwable ignored) {}
    }

    public static void execCommand(String command) {
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                
                // Используем Runtime.exec вместо Shizuku.newProcess (он приватный)
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                process.waitFor();
            } catch (Exception e) {
                Log.e(TAG, "execCommand error: " + e.getMessage());
            }
        }).start();
    }

    public static String execCommandSync(String command) {
        try {
            if (!isShizukuReady()) return "";
            
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            reader.close();
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "execCommandSync error: " + e.getMessage());
            return "";
        }
    }

    public static void setStretchConfig(String ratioCode, int defShort, int defLong, int nativeDpi) {
        selectedStretchRatio = ratioCode;
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                
                int width = defShort;
                int height = defLong;
                
                switch (ratioCode) {
                    case "4:3":
                        width = (int)(defLong * 0.75);
                        break;
                    case "3:2":
                        width = (int)(defLong * 0.66);
                        break;
                    case "16:10":
                        width = (int)(defLong * 0.625);
                        break;
                    case "16:9":
                    default:
                        width = defShort;
                        break;
                }
                
                String cmd = String.format("wm size %dx%d", width, height);
                execCommand(cmd);
                stretchActive = true;
            } catch (Exception e) {
                Log.e(TAG, "setStretchConfig error: " + e.getMessage());
            }
        }).start();
    }

    public static String resetStretch() {
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                execCommand("wm size reset");
                stretchActive = false;
            } catch (Exception e) {
                Log.e(TAG, "resetStretch error: " + e.getMessage());
            }
        }).start();
        return "Разрешение сброшено";
    }

    public static String getSelectedStretchRatio() {
        return selectedStretchRatio;
    }

    public static boolean isStretchActive() {
        return stretchActive;
    }

    public static String applyStretch() {
        setStretchConfig(selectedStretchRatio, 1080, 2400, 440);
        return "Растяжка применена: " + selectedStretchRatio;
    }

    public static void updateLiveConfig(int chunk, int threshold, int zone, int pollHz, int longSide, int shortSide) {
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                execCommand("settings put system pointer_speed " + (chunk / 10));
            } catch (Exception e) {
                Log.e(TAG, "updateLiveConfig error: " + e.getMessage());
            }
        }).start();
    }

    public static boolean startTeleport(int chunk, int threshold, int zone, int pollHz, int longSide, int shortSide, Runnable callback) {
        teleportRunning = true;
        sessionTeleports = 0;
        
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                
                execCommand("settings put secure touch_blocking_period 0.0");
                execCommand("settings put system pointer_speed 7");
                execCommand("setprop debug.sf.latch_unsignaled 1");
                
                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                Log.e(TAG, "startTeleport error: " + e.getMessage());
            }
        }).start();
        
        return true;
    }

    public static void stopTeleport() {
        teleportRunning = false;
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                
                execCommand("settings put secure touch_blocking_period 100");
                execCommand("settings put system pointer_speed 0");
            } catch (Exception e) {
                Log.e(TAG, "stopTeleport error: " + e.getMessage());
            }
        }).start();
    }

    public static boolean isTeleportRunning() {
        return teleportRunning;
    }

    public static int getSessionTeleports() {
        return sessionTeleports;
    }

    public static void addSessionTeleport() {
        sessionTeleports++;
    }

    public static String runFpsBooster(boolean aggressive) {
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                
                if (aggressive) {
                    execCommand("am kill-all");
                    execCommand("settings put global window_animation_scale 0.0");
                    execCommand("settings put global transition_animation_scale 0.0");
                    execCommand("settings put global animator_duration_scale 0.0");
                } else {
                    execCommand("settings put global window_animation_scale 1.0");
                    execCommand("settings put global transition_animation_scale 1.0");
                    execCommand("settings put global animator_duration_scale 1.0");
                }
            } catch (Exception e) {
                Log.e(TAG, "runFpsBooster error: " + e.getMessage());
            }
        }).start();
        return aggressive ? "FPS Booster ON" : "FPS Booster OFF";
    }

    public static String cleanRamBeforeGame() {
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                execCommand("am kill-all");
                execCommand("sync; echo 3 > /proc/sys/vm/drop_caches");
            } catch (Exception e) {
                Log.e(TAG, "cleanRamBeforeGame error: " + e.getMessage());
            }
        }).start();
        return "ОЗУ очищена";
    }

    public static void setDoNotDisturb(boolean enabled) {
        new Thread(() -> {
            try {
                if (!isShizukuReady()) return;
                
                if (enabled) {
                    execCommand("cmd notification set_do_not_disturb allow");
                } else {
                    execCommand("cmd notification set_do_not_disturb disallow");
                }
            } catch (Exception e) {
                Log.e(TAG, "setDoNotDisturb error: " + e.getMessage());
            }
        }).start();
    }

    public static boolean isStandoffForeground() {
        return true;
    }
}
