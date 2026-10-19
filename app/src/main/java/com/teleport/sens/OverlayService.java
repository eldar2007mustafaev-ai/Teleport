package com.teleport.sens;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OverlayService создан");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra("action");
            Log.i(TAG, "Action: " + action);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "OverlayService уничтожен");
    }
}
