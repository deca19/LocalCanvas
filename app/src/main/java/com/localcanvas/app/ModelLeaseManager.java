package com.localcanvas.app;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates read leases (inference/queue) and exclusive write leases (download/import/delete)
 * per canonical model path to eliminate concurrency races.
 */
public final class ModelLeaseManager {
    private static final ModelLeaseManager INSTANCE = new ModelLeaseManager();

    private static final class LockState {
        final AtomicInteger readers = new AtomicInteger(0);
        boolean writerActive = false;
    }

    private final ConcurrentMap<String, LockState> locks = new ConcurrentHashMap<>();

    private ModelLeaseManager() {}

    public static ModelLeaseManager shared() { return INSTANCE; }

    private static String key(String path) {
        if (path == null) return "";
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return new File(path).getAbsolutePath();
        }
    }

    public boolean tryAcquireRead(String path) {
        String k = key(path);
        LockState state = locks.computeIfAbsent(k, x -> new LockState());
        synchronized (state) {
            if (state.writerActive) return false;
            state.readers.incrementAndGet();
            return true;
        }
    }

    public void releaseRead(String path) {
        String k = key(path);
        LockState state = locks.get(k);
        if (state == null) return;
        synchronized (state) {
            int rem = state.readers.decrementAndGet();
            if (rem < 0) state.readers.set(0);
        }
    }

    public boolean tryAcquireWrite(String path) {
        String k = key(path);
        LockState state = locks.computeIfAbsent(k, x -> new LockState());
        synchronized (state) {
            if (state.writerActive || state.readers.get() > 0) return false;
            state.writerActive = true;
            return true;
        }
    }

    public void releaseWrite(String path) {
        String k = key(path);
        LockState state = locks.get(k);
        if (state == null) return;
        synchronized (state) {
            state.writerActive = false;
        }
    }

    public boolean isBusy(String path) {
        String k = key(path);
        LockState state = locks.get(k);
        if (state == null) return false;
        synchronized (state) {
            return state.writerActive || state.readers.get() > 0;
        }
    }
}
