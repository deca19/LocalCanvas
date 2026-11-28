package com.localcanvas.app;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostics ring buffer and privacy-safe diagnostic report exporter.
 */
public final class DiagnosticsCenter {
    private static final List<String> LOGS = new ArrayList<>();
    private static final int MAX_LOGS = 120;

    private DiagnosticsCenter() {}

    public static synchronized void log(String tag, String message) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LOGS.add(0, ts + " [" + tag + "] " + message);
        if (LOGS.size() > MAX_LOGS) LOGS.remove(LOGS.size() - 1);
    }

    public static synchronized List<String> recentLogs() {
        return new ArrayList<>(LOGS);
    }

    public static String errorHint(String err) {
        if (err == null || err.isEmpty()) return "정상";
        String lower = err.toLowerCase(Locale.US);
        if (lower.contains("memory") || lower.contains("oom")) return "메모리 부족: 저메모리 모드(VAE Tiling)를 켜거나 해상도를 낮추세요.";
        if (lower.contains("vulkan")) return "Vulkan 초기화 실패: 설정에서 CPU 폴백을 켜고 재시도하세요.";
        if (lower.contains("sha-256") || lower.contains("corrupt") || lower.contains("손상")) return "파일 무결성 오류: 모델을 삭제하고 다시 다운로드하세요.";
        if (lower.contains("space") || lower.contains("공간")) return "저장 공간 부족: 미사용 모델이나 캐시를 정리하세요.";
        return "오류 발생: 진단 로그를 확인하세요.";
    }

    public static File exportSanitizedReport(Context c) throws Exception {
        PerformanceMonitor.Snapshot perf = PerformanceMonitor.capture(c);
        String abi = (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0)
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        StringBuilder sb = new StringBuilder();
        sb.append("LocalCanvas V16.0 Sanitized Diagnostics\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(", ABI ").append(abi).append(")\n");
        sb.append("RAM: ").append(perf.availRamMb).append("/").append(perf.totalRamMb).append(" MB\n");
        sb.append("Thermal: ").append(perf.thermalLabel).append(" · Battery: ").append(perf.batteryTempC).append("°C\n");
        sb.append("Native Loaded: ").append(NativeEngine.isNativeLibraryLoaded()).append("\n");
        if (NativeEngine.isNativeLibraryLoaded()) {
            sb.append("Native Runtime: ").append(NativeEngine.nativeRuntimeStatus()).append("\n");
        }
        sb.append("\n--- Recent Events (Sanitized) ---\n");
        for (String line : recentLogs()) {
            sb.append(line).append("\n");
        }
        File out = new File(c.getCacheDir(), "localcanvas_diag_" + System.currentTimeMillis() + ".txt");
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }
}
