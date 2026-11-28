package com.localcanvas.app;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-scoped download coordinator that survives Activity recreation.
 * Tracks real speed, ETA, progress, and per-model cancellation tokens.
 */
public final class DownloadCoordinator {
    private static final DownloadCoordinator SHARED = new DownloadCoordinator();

    public static final class DownloadStatus {
        public final String modelId;
        public final long doneBytes;
        public final long totalBytes;
        public final long elapsedMs;
        public final boolean active;
        public final String error;

        DownloadStatus(String id, long d, long t, long e, boolean a, String err) {
            modelId = id; doneBytes = d; totalBytes = t; elapsedMs = e; active = a; error = err;
        }

        public int percent() {
            return totalBytes > 0 ? (int) Math.min(100L, (doneBytes * 100L) / totalBytes) : 0;
        }

        public double speedBytesPerSec() {
            return elapsedMs > 0 ? (doneBytes * 1000.0) / elapsedMs : 0.0;
        }

        public long etaSeconds() {
            double spd = speedBytesPerSec();
            if (totalBytes <= 0 || spd <= 1.0) return -1L;
            return (long) ((totalBytes - doneBytes) / spd);
        }
    }

    public interface Observer {
        void onDownloadUpdated(DownloadStatus status);
        void onDownloadCompleted(String modelId, File file);
    }

    private final Map<String, AtomicBoolean> cancelTokens = new ConcurrentHashMap<>();
    private final Map<String, DownloadStatus> statuses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();

    private DownloadCoordinator() {}

    public static DownloadCoordinator shared() { return SHARED; }

    public void addObserver(Observer o) {
        if (o != null && !observers.contains(o)) observers.add(o);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public DownloadStatus getStatus(String modelId) {
        return statuses.get(modelId);
    }

    public boolean isDownloading(String modelId) {
        DownloadStatus st = statuses.get(modelId);
        return st != null && st.active;
    }

    public void cancel(String modelId) {
        AtomicBoolean token = cancelTokens.get(modelId);
        if (token != null) token.set(true);
    }

    public void startDownload(ModelManager manager, String id, ModelManager.AssetType type,
                              String url, String sha256) {
        if (isDownloading(id)) return;
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancelTokens.put(id, cancel);
        DownloadStatus initial = new DownloadStatus(id, 0, -1, 0, true, null);
        statuses.put(id, initial);
        notifyUpdated(initial);

        TaskRuntime.networkExecutor().submit(() -> {
            try {
                File out = manager.download(id, type, url, sha256, (done, total, elapsed) -> {
                    DownloadStatus st = new DownloadStatus(id, done, total, elapsed, true, null);
                    statuses.put(id, st);
                    notifyUpdated(st);
                }, cancel);
                DownloadStatus doneSt = new DownloadStatus(id, out.length(), out.length(), 1, false, null);
                statuses.put(id, doneSt);
                notifyCompleted(id, out);
            } catch (Exception e) {
                DownloadStatus errSt = new DownloadStatus(id, 0, -1, 0, false,
                        e.getMessage() != null ? e.getMessage() : "다운로드 실패");
                statuses.put(id, errSt);
                notifyUpdated(errSt);
            } finally {
                cancelTokens.remove(id);
            }
        });
    }

    private void notifyUpdated(DownloadStatus st) {
        for (Observer o : observers) {
            try { o.onDownloadUpdated(st); } catch (Throwable ignored) {}
        }
    }

    private void notifyCompleted(String id, File f) {
        for (Observer o : observers) {
            try { o.onDownloadCompleted(id, f); } catch (Throwable ignored) {}
        }
    }
}
