package com.localcanvas.app;

import android.graphics.Bitmap;

/** JNI boundary for the real stable-diffusion.cpp native engine. */
public final class NativeEngine {
    private static final boolean LOADED;
    static {
        boolean loaded;
        try {
            System.loadLibrary("localcanvas_native");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private NativeEngine() {}

    public static boolean isNativeLibraryLoaded() { return LOADED; }
    public static boolean isGeneratingSafe() { return LOADED && nativeIsGenerating(); }

    public static native boolean nativeIsAvailable();
    public static native String nativeRuntimeStatus();
    public static native String nativeBackendStatus();
    public static native String nativeLastError();
    public static native String nativeLastGenInfo();
    public static native boolean nativeIsGenerating();
    public static native int nativeProgress();
    public static native void nativeCancel();
    public static native boolean nativeUnload();

    public static native Bitmap nativeGenerate(
            String modelPath, String prompt, String negativePrompt,
            int width, int height, int steps, float cfg, long seed, int threads,
            String sampler, String scheduler);

    public static native Bitmap[] nativeGenerateEx(
            String modelPath, String vaePath, String controlNetPath, String ipAdapterPath,
            String[] loraPaths, float[] loraWeights,
            String prompt, String negativePrompt,
            int width, int height, int steps, float cfg, long seed,
            int batchCount, int clipSkip, int threads,
            String sampler, String scheduler,
            boolean useVulkan, boolean cpuFallback, boolean flashAttn,
            boolean enableMmap, boolean vaeTiling,
            boolean hiresEnabled, float hiresScale, int hiresSteps, float hiresDenoise, String hiresUpscaler,
            Bitmap initBitmap, Bitmap maskBitmap, float strength,
            Bitmap controlBitmap, String controlType, float controlStrength,
            Bitmap ipAdapterBitmap, float ipAdapterStrength);

    public static native Bitmap nativeUpscale(
            String upscalerModelPath, Bitmap inputBitmap, int tileSize, int threads, boolean useVulkan);

    public static native Bitmap nativePreprocessCanny(
            Bitmap inputBitmap, float highThreshold, float lowThreshold, float weak, float strong);
}
