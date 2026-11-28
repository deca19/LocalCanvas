package com.localcanvas.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped executors shared across recreated Activities. */
public final class TaskRuntime {
    private static final ExecutorService FILE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService NETWORK_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final ExecutorService DECODE_EXECUTOR = Executors.newFixedThreadPool(2);

    private TaskRuntime() {}

    public static ExecutorService fileExecutor() { return FILE_EXECUTOR; }
    public static ExecutorService networkExecutor() { return NETWORK_EXECUTOR; }
    public static ExecutorService decodeExecutor() { return DECODE_EXECUTOR; }
}
