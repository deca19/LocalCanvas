package com.localcanvas.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent single-consumer inference queue with ModelLeaseManager integration,
 * pause/reorder/retry support, per-job temp cleanup, and lifecycle-safe observers.
 */
public final class GenerationQueue {
    private static final GenerationQueue SHARED = new GenerationQueue(true);

    public interface Listener {
        void started(int index, int total);
        void completed(Bitmap image, int index);
        void failed(String message, int index);
    }

    public interface QueueObserver {
        void onQueueChanged();
        void onJobCompleted(Job job, Bitmap[] images, String genInfo);
        void onJobFailed(Job job, String message);
    }

    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public static final class Job {
        public final String id;
        public final File model;
        public final File vae;
        public final File controlNet;
        public final File ipAdapter;
        public final String[] loraPaths;
        public final float[] loraWeights;
        public final String prompt, negative, sampler, scheduler;
        public final int width, height, steps, threads, batchCount, clipSkip;
        public final float cfg;
        public final long seed;
        public final boolean useVulkan, cpuFallback, flashAttn, enableMmap, vaeTiling;
        public final boolean hiresEnabled;
        public final float hiresScale, hiresDenoise;
        public final int hiresSteps;
        public final String hiresUpscaler;
        public final File initImageFile, maskImageFile, controlImageFile, ipAdapterImageFile;
        public final float strength, controlStrength, ipAdapterStrength;
        public final String controlType;
        public final long createdAt;

        public Job(File m, String p, String n, int w, int h, int s, float c, long sd, int th, String sm, String sc) {
            this(UUID.randomUUID().toString(), m, null, null, null, null, null, p, n, w, h, s, c, sd,
                    1, 1, th, sm, sc, true, true, true, true, false,
                    false, 1.5f, 10, 0.5f, "latent",
                    null, null, 0.75f, null, "none", 0.8f, null, 0.8f,
                    System.currentTimeMillis());
        }

        public Job(String id, File model, File vae, File controlNet, File ipAdapter,
                   String[] loraPaths, float[] loraWeights,
                   String prompt, String negative, int width, int height, int steps, float cfg, long seed,
                   int batchCount, int clipSkip, int threads, String sampler, String scheduler,
                   boolean useVulkan, boolean cpuFallback, boolean flashAttn, boolean enableMmap, boolean vaeTiling,
                   boolean hiresEnabled, float hiresScale, int hiresSteps, float hiresDenoise, String hiresUpscaler,
                   File initImageFile, File maskImageFile, float strength,
                   File controlImageFile, String controlType, float controlStrength,
                   File ipAdapterImageFile, float ipAdapterStrength, long createdAt) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.model = model;
            this.vae = vae;
            this.controlNet = controlNet;
            this.ipAdapter = ipAdapter;
            this.loraPaths = loraPaths;
            this.loraWeights = loraWeights;
            this.prompt = prompt;
            this.negative = negative;
            this.width = width;
            this.height = height;
            this.steps = steps;
            this.cfg = cfg;
            this.seed = seed;
            this.batchCount = Math.max(1, batchCount);
            this.clipSkip = Math.max(1, clipSkip);
            this.threads = Math.max(1, threads);
            this.sampler = sampler;
            this.scheduler = scheduler;
            this.useVulkan = useVulkan;
            this.cpuFallback = cpuFallback;
            this.flashAttn = flashAttn;
            this.enableMmap = enableMmap;
            this.vaeTiling = vaeTiling;
            this.hiresEnabled = hiresEnabled;
            this.hiresScale = hiresScale;
            this.hiresSteps = hiresSteps;
            this.hiresDenoise = hiresDenoise;
            this.hiresUpscaler = hiresUpscaler;
            this.initImageFile = initImageFile;
            this.maskImageFile = maskImageFile;
            this.strength = strength;
            this.controlImageFile = controlImageFile;
            this.controlType = controlType;
            this.controlStrength = controlStrength;
            this.ipAdapterImageFile = ipAdapterImageFile;
            this.ipAdapterStrength = ipAdapterStrength;
            this.createdAt = createdAt;
        }
    }

    public static final class HistoryEntry {
        public final Job job;
        public final State state;
        public final String error;
        HistoryEntry(Job j, State s, String e) { job = j; state = s; error = e; }
    }

    private static final class Work {
        final Job job;
        final Listener listener;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Work(Job j, Listener l) { job = j; listener = l; }
    }

    private final List<Work> pending = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Object queueLock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> modelRefs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<QueueObserver> observers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean restoredOnce = new AtomicBoolean();
    private volatile Work currentWork;
    private File persistenceFile;

    public GenerationQueue() { this(false); }

    private GenerationQueue(boolean shared) {
        worker.submit(this::consume);
    }

    public static GenerationQueue shared() { return SHARED; }

    public void attachPersistence(File file) {
        synchronized (queueLock) { this.persistenceFile = file; }
    }

    public void addObserver(QueueObserver o) {
        if (o != null && !observers.contains(o)) observers.add(o);
    }

    public void removeObserver(QueueObserver o) { observers.remove(o); }

    public void setPaused(boolean p) {
        paused.set(p);
        synchronized (queueLock) { queueLock.notifyAll(); }
        notifyQueueChanged();
    }

    public boolean isPaused() { return paused.get(); }

    public void submit(Job job, Listener listener) {
        if (job == null || listener == null) {
            throw new IllegalArgumentException("작업과 리스너가 필요합니다");
        }
        if (stopped.get()) throw new RejectedExecutionException("생성 큐가 종료되었습니다");
        if (job.model != null) {
            String path = job.model.getAbsolutePath();
            if (!ModelLeaseManager.shared().tryAcquireRead(path)) {
                throw new IllegalStateException("모델이 현재 삭제 또는 다운로드 중입니다");
            }
            modelRefs.merge(path, 1, Integer::sum);
        }
        states.put(job.id, State.QUEUED);
        synchronized (queueLock) {
            pending.add(new Work(job, listener));
            persistLocked();
            queueLock.notifyAll();
        }
        notifyQueueChanged();
    }

    private void consume() {
        while (!stopped.get()) {
            Work w;
            int total;
            synchronized (queueLock) {
                while (!stopped.get() && (paused.get() || pending.isEmpty())) {
                    try { queueLock.wait(500); } catch (InterruptedException e) {
                        if (stopped.get()) return;
                        Thread.currentThread().interrupt();
                    }
                }
                if (stopped.get()) return;
                w = pending.remove(0);
                total = pending.size() + 1;
                currentWork = w;
                persistLocked();
            }

            if (w.cancelled.get()) {
                states.put(w.job.id, State.CANCELLED);
                releaseModel(w.job.model);
                cleanupTempFile(w.job.initImageFile);
                cleanupTempFile(w.job.maskImageFile);
                cleanupTempFile(w.job.controlImageFile);
                cleanupTempFile(w.job.ipAdapterImageFile);
                currentWork = null;
                recordHistory(w.job, State.CANCELLED, "취소됨");
                notifyQueueChanged();
                continue;
            }

            states.put(w.job.id, State.RUNNING);
            notifyQueueChanged();
            safeStarted(w.listener, 0, total);

            Bitmap initBmp = decodeOptionalBitmap(w.job.initImageFile);
            Bitmap maskBmp = decodeOptionalBitmap(w.job.maskImageFile);
            Bitmap ctrlBmp = decodeOptionalBitmap(w.job.controlImageFile);
            Bitmap ipaBmp = decodeOptionalBitmap(w.job.ipAdapterImageFile);

            try {
                if (w.job.model == null || !w.job.model.isFile()) {
                    throw new IOException("모델 파일이 없습니다");
                }
                Bitmap[] images = NativeEngine.nativeGenerateEx(
                        w.job.model.getAbsolutePath(),
                        w.job.vae != null ? w.job.vae.getAbsolutePath() : null,
                        w.job.controlNet != null ? w.job.controlNet.getAbsolutePath() : null,
                        w.job.ipAdapter != null ? w.job.ipAdapter.getAbsolutePath() : null,
                        w.job.loraPaths, w.job.loraWeights,
                        w.job.prompt, w.job.negative,
                        w.job.width, w.job.height, w.job.steps, w.job.cfg, w.job.seed,
                        w.job.batchCount, w.job.clipSkip, w.job.threads,
                        w.job.sampler, w.job.scheduler,
                        w.job.useVulkan, w.job.cpuFallback, w.job.flashAttn,
                        w.job.enableMmap, w.job.vaeTiling,
                        w.job.hiresEnabled, w.job.hiresScale, w.job.hiresSteps, w.job.hiresDenoise, w.job.hiresUpscaler,
                        initBmp, maskBmp, w.job.strength,
                        ctrlBmp, w.job.controlType, w.job.controlStrength,
                        ipaBmp, w.job.ipAdapterStrength);

                if (images == null || images.length == 0 || images[0] == null) {
                    states.put(w.job.id, State.FAILED);
                    String err = "엔진이 이미지를 반환하지 않았습니다";
                    recordHistory(w.job, State.FAILED, err);
                    safeFailed(w.listener, err, 0);
                    notifyFailed(w.job, err);
                } else {
                    states.put(w.job.id, State.SUCCEEDED);
                    String genInfo = NativeEngine.isNativeLibraryLoaded() ? NativeEngine.nativeLastGenInfo() : "";
                    recordHistory(w.job, State.SUCCEEDED, null);
                    safeCompleted(w.listener, images[0], 0);
                    notifyCompleted(w.job, images, genInfo);
                }
            } catch (Throwable e) {
                states.put(w.job.id, State.FAILED);
                String msg = e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : "네이티브 생성 오류";
                try {
                    if (NativeEngine.isNativeLibraryLoaded()) {
                        String z = NativeEngine.nativeLastError();
                        if (z != null && !z.isEmpty()) msg = z;
                    }
                } catch (Throwable ignored) {}
                recordHistory(w.job, State.FAILED, msg);
                safeFailed(w.listener, msg, 0);
                notifyFailed(w.job, msg);
            } finally {
                recycleIfUnused(initBmp);
                recycleIfUnused(maskBmp);
                recycleIfUnused(ctrlBmp);
                recycleIfUnused(ipaBmp);
                cleanupTempFile(w.job.initImageFile);
                cleanupTempFile(w.job.maskImageFile);
                cleanupTempFile(w.job.controlImageFile);
                cleanupTempFile(w.job.ipAdapterImageFile);
                releaseModel(w.job.model);
                currentWork = null;
                notifyQueueChanged();
            }
        }
    }

    private static void cleanupTempFile(File f) {
        if (f != null && f.getName().startsWith("tmp_") && f.isFile()) {
            f.delete();
        }
    }

    private static Bitmap decodeOptionalBitmap(File f) {
        if (f == null || !f.isFile()) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private static void recycleIfUnused(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    private void recordHistory(Job j, State s, String err) {
        synchronized (queueLock) {
            history.add(0, new HistoryEntry(j, s, err));
            if (history.size() > 50) history.remove(history.size() - 1);
        }
    }

    public List<HistoryEntry> historySnapshot() {
        synchronized (queueLock) { return new ArrayList<>(history); }
    }

    public List<Job> pendingSnapshot() {
        synchronized (queueLock) {
            List<Job> out = new ArrayList<>();
            for (Work w : pending) out.add(w.job);
            return out;
        }
    }

    public Job currentJob() {
        Work w = currentWork;
        return w != null ? w.job : null;
    }

    public boolean moveUp(String jobId) {
        synchronized (queueLock) {
            for (int i = 1; i < pending.size(); i++) {
                if (pending.get(i).job.id.equals(jobId)) {
                    Collections.swap(pending, i, i - 1);
                    persistLocked();
                    notifyQueueChanged();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean cancelJob(String jobId) {
        Work cur = currentWork;
        if (cur != null && cur.job.id.equals(jobId)) {
            cur.cancelled.set(true);
            if (NativeEngine.isNativeLibraryLoaded()) NativeEngine.nativeCancel();
            return true;
        }
        synchronized (queueLock) {
            for (int i = 0; i < pending.size(); i++) {
                Work w = pending.get(i);
                if (w.job.id.equals(jobId)) {
                    pending.remove(i);
                    w.cancelled.set(true);
                    states.put(jobId, State.CANCELLED);
                    releaseModel(w.job.model);
                    recordHistory(w.job, State.CANCELLED, "사용자 취소");
                    persistLocked();
                    notifyQueueChanged();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isModelInUse(String path) {
        if (path == null) return false;
        String abs = new File(path).getAbsolutePath();
        // Callers acquire the write lease before asking this question. Including
        // ModelLeaseManager.isBusy(abs) here would report the caller's own lease as
        // a conflicting reader and make every download/import/delete fail closed.
        // The lease manager independently blocks competing operations.
        return modelRefs.containsKey(abs);
    }

    public State state(String id) { return states.get(id); }

    public int pendingCount() {
        synchronized (queueLock) { return pending.size(); }
    }

    public List<String> queuedIds() {
        synchronized (queueLock) {
            List<String> out = new ArrayList<>();
            for (Work w : pending) out.add(w.job.id);
            return out;
        }
    }

    public void cancelPending() {
        synchronized (queueLock) {
            for (Work w : pending) {
                w.cancelled.set(true);
                states.put(w.job.id, State.CANCELLED);
                releaseModel(w.job.model);
            }
            pending.clear();
            persistLocked();
        }
        notifyQueueChanged();
    }

    private void releaseModel(File model) {
        if (model == null) return;
        String p = model.getAbsolutePath();
        modelRefs.computeIfPresent(p, (k, v) -> v <= 1 ? null : v - 1);
        ModelLeaseManager.shared().releaseRead(p);
    }

    private void persistLocked() {
        if (persistenceFile == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Work w : pending) {
                JSONObject o = new JSONObject();
                o.put("id", w.job.id);
                o.put("model", w.job.model != null ? w.job.model.getAbsolutePath() : "");
                o.put("prompt", w.job.prompt);
                o.put("negative", w.job.negative);
                o.put("width", w.job.width);
                o.put("height", w.job.height);
                o.put("steps", w.job.steps);
                o.put("cfg", w.job.cfg);
                o.put("seed", w.job.seed);
                o.put("sampler", w.job.sampler);
                o.put("scheduler", w.job.scheduler);
                arr.put(o);
            }
            Files.write(persistenceFile.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public void restoreFromDisk(Listener defaultListener) {
        if (!restoredOnce.compareAndSet(false, true)) return;
        File f;
        synchronized (queueLock) { f = persistenceFile; }
        if (f == null || !f.isFile()) return;
        try {
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String modelPath = o.optString("model", "");
                if (modelPath.isEmpty()) continue;
                Job job = new Job(new File(modelPath), o.optString("prompt", ""),
                        o.optString("negative", ""), o.optInt("width", 512), o.optInt("height", 512),
                        o.optInt("steps", 20), (float) o.optDouble("cfg", 7.0), o.optLong("seed", -1),
                        4, o.optString("sampler", "euler"), o.optString("scheduler", "auto"));
                submit(job, defaultListener);
            }
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        stopped.set(true);
        cancelPending();
        worker.shutdownNow();
    }

    private void notifyQueueChanged() {
        for (QueueObserver o : observers) {
            try { o.onQueueChanged(); } catch (Throwable ignored) {}
        }
    }

    private void notifyCompleted(Job j, Bitmap[] imgs, String info) {
        for (QueueObserver o : observers) {
            try { o.onJobCompleted(j, imgs, info); } catch (Throwable ignored) {}
        }
    }

    private void notifyFailed(Job j, String msg) {
        for (QueueObserver o : observers) {
            try { o.onJobFailed(j, msg); } catch (Throwable ignored) {}
        }
    }

    private static void safeStarted(Listener l, int i, int t) { try { l.started(i, t); } catch (Throwable ignored) {} }
    private static void safeCompleted(Listener l, Bitmap b, int i) { try { l.completed(b, i); } catch (Throwable ignored) {} }
    private static void safeFailed(Listener l, String m, int i) { try { l.failed(m, i); } catch (Throwable ignored) {} }
}
