package com.localcanvas.app;

/** Native inference boundary. No fake image is returned when the native engine is absent. */
public interface EngineContract {
    boolean isAvailable();
    String[] supportedFamilies();
    void generate(GenerationRequest request, ProgressListener listener);
    interface ProgressListener {
        void onProgress(String stage, float fraction);
        void onError(String message);
    }
}

final class GenerationRequest {
    final String prompt, negativePrompt, modelId;
    final int width, height, steps;
    final float cfg;
    final long seed;

    GenerationRequest(String p, String n, String m, int w, int h, int s, float c, long sd) {
        prompt = p;
        negativePrompt = n;
        modelId = m;
        width = w;
        height = h;
        steps = s;
        cfg = c;
        seed = sd;
    }
}
