package com.teleport.so2;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import org.json.JSONObject;

public class DeviceScanner {

    public static String scanAndCalculateOptimal(Context context) {
        JSONObject res = new JSONObject();
        try {
            String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "ANDROID";
            String model = Build.MODEL != null ? Build.MODEL : "Device";

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            int refreshRate = 60;
            if (wm != null && wm.getDefaultDisplay() != null) {
                Display d = wm.getDefaultDisplay();
                d.getRealMetrics(dm);
                refreshRate = Math.round(d.getRefreshRate());
            } else {
                dm = context.getResources().getDisplayMetrics();
            }

            int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
            int longSide = Math.max(dm.widthPixels, dm.heightPixels);
            int dpi = dm.densityDpi;
            double ratio = (double) longSide / (double) Math.max(1, shortSide);

            String ratioName;
            if (ratio < 1.45) ratioName = "4:3";
            else if (ratio < 1.56) ratioName = "3:2";
            else if (ratio < 1.68) ratioName = "16:10";
            else if (ratio < 1.85) ratioName = "16:9";
            else if (ratio < 2.05) ratioName = "18:9";
            else if (ratio < 2.18) ratioName = "19.5:9";
            else ratioName = "20:9";

            int optimalChunk = 30;
            int optimalThreshold = 14;
            int optimalZonePct = 75;
            int optimalPollHz = Math.max(120, Math.min(240, refreshRate));

            ProfileManager.saveTeleportSettings(context, optimalChunk, optimalThreshold, optimalZonePct, optimalPollHz);

            res.put("status", "ok");
            res.put("device_name", manufacturer + " " + model);
            res.put("resolution", longSide + "x" + shortSide);
            res.put("dpi", dpi);
            res.put("refresh_rate", refreshRate);
            res.put("aspect_ratio", ratioName);
            res.put("optimal_chunk", optimalChunk);
            res.put("optimal_threshold", optimalThreshold);
        } catch (Exception e) {
            try {
                res.put("status", "error");
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res.toString();
    }
}
