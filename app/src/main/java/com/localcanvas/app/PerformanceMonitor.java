package com.localcanvas.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

/**
 * Monitors real Android RAM, Thermal API (API 29+), and Battery temperature/level
 * to recommend safe thread counts and low-memory mode without fabricating GPU metrics.
 */
public final class PerformanceMonitor {
    public static final class Snapshot {
        public final long availRamMb;
        public final long totalRamMb;
        public final boolean lowMemory;
        public final int thermalStatus;
        public final String thermalLabel;
        public final float batteryTempC;
        public final int batteryPercent;
        public final int recommendedThreads;
        public final boolean recommendLowMemMode;

        Snapshot(long avail, long total, boolean lowMem, int thermal, String label,
                 float tempC, int pct, int threads, boolean recLow) {
            availRamMb = avail; totalRamMb = total; lowMemory = lowMem;
            thermalStatus = thermal; thermalLabel = label;
            batteryTempC = tempC; batteryPercent = pct;
            recommendedThreads = threads; recommendLowMemMode = recLow;
        }
    }

    private PerformanceMonitor() {}

    public static Snapshot capture(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) am.getMemoryInfo(mi);
        long availMb = mi.availMem / (1024L * 1024L);
        long totalMb = mi.totalMem / (1024L * 1024L);

        int thermal = 0;
        String label = "정상 (NONE)";
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermal = pm.getCurrentThermalStatus();
            label = thermalLabel(thermal);
        }

        float tempC = -1f;
        int pct = -1;
        Intent batt = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt != null) {
            int rawTemp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (rawTemp > 0) tempC = rawTemp / 10.0f;
            int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) pct = (level * 100) / scale;
        }

        int threads = 4;
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) threads = 2;
        else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) threads = 3;
        boolean recLowMem = mi.lowMemory || availMb < 3500;

        return new Snapshot(availMb, totalMb, mi.lowMemory, thermal, label, tempC, pct, threads, recLowMem);
    }

    private static String thermalLabel(int s) {
        switch (s) {
            case PowerManager.THERMAL_STATUS_NONE: return "정상 (NONE)";
            case PowerManager.THERMAL_STATUS_LIGHT: return "약간 따뜻함 (LIGHT)";
            case PowerManager.THERMAL_STATUS_MODERATE: return "주의 (MODERATE)";
            case PowerManager.THERMAL_STATUS_SEVERE: return "고온 스로틀링 (SEVERE)";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "위험 (CRITICAL)";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "긴급 (EMERGENCY)";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "종료 임박 (SHUTDOWN)";
            default: return "알 수 없음 (" + s + ")";
        }
    }
}
