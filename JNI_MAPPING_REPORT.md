# LocalCanvas V15.4 — JNI Mapping Report

모든 `NativeEngine.java`의 `native` 선언과 `app/src/main/cpp/localcanvas_jni.cpp`의 C++ JNI 구현 간 1:1 매핑 검증표입니다.

| # | Java 선언 (`NativeEngine`) | C++ JNI 함수명 | 인자/반환 매핑 | 엔진 호출 | 상태 |
|---|---|---|---|---|---|
| 1 | `nativeIsAvailable()` | `Java_com_localcanvas_app_NativeEngine_nativeIsAvailable` | `()Z` | `sd_list_devices`, `sd_set_log_callback` | `IMPLEMENTED` |
| 2 | `nativeRuntimeStatus()` | `Java_com_localcanvas_app_NativeEngine_nativeRuntimeStatus` | `()Ljava/lang/String;` | `g_active_backend`, `g_runtime_devices` | `IMPLEMENTED` |
| 3 | `nativeBackendStatus()` | `Java_com_localcanvas_app_NativeEngine_nativeBackendStatus` | `()Ljava/lang/String;` | `g_active_backend`, `g_runtime_devices` | `IMPLEMENTED` |
| 4 | `nativeLastError()` | `Java_com_localcanvas_app_NativeEngine_nativeLastError` | `()Ljava/lang/String;` | `g_last_error` | `IMPLEMENTED` |
| 5 | `nativeLastGenInfo()` | `Java_com_localcanvas_app_NativeEngine_nativeLastGenInfo` | `()Ljava/lang/String;` | `sd_result_get_generation_info` | `IMPLEMENTED` |
| 6 | `nativeIsGenerating()` | `Java_com_localcanvas_app_NativeEngine_nativeIsGenerating` | `()Z` | `g_generating` | `IMPLEMENTED` |
| 7 | `nativeProgress()` | `Java_com_localcanvas_app_NativeEngine_nativeProgress` | `()I` | `g_progress` | `IMPLEMENTED` |
| 8 | `nativeCancel()` | `Java_com_localcanvas_app_NativeEngine_nativeCancel` | `()V` | `sd_cancel_generation` | `IMPLEMENTED` |
| 9 | `nativeUnload()` | `Java_com_localcanvas_app_NativeEngine_nativeUnload` | `()Z` | `sd_dgl_ctx_unload_models`, `sd_ctx_free` | `IMPLEMENTED` |
| 10 | `nativeGenerate(...)` | `Java_com_localcanvas_app_NativeEngine_nativeGenerate` | `(...)Landroid/graphics/Bitmap;` | `nativeGenerateEx` 위임 | `IMPLEMENTED` |
| 11 | `nativeGenerateEx(...)` | `Java_com_localcanvas_app_NativeEngine_nativeGenerateEx` | `(...)[Landroid/graphics/Bitmap;` | `sd_ctx_create`, `sd_generate_image` | `IMPLEMENTED` (LoRA C 구조체는 `UNSUPPORTED`) |
| 12 | `nativeUpscale(...)` | `Java_com_localcanvas_app_NativeEngine_nativeUpscale` | `(...)Landroid/graphics/Bitmap;` | `sd_upscaler_create`, `sd_upscale` | `IMPLEMENTED` |
| 13 | `nativePreprocessCanny(...)` | `Java_com_localcanvas_app_NativeEngine_nativePreprocessCanny` | `(...)Landroid/graphics/Bitmap;` | no pinned C API symbol | `UNSUPPORTED` (명시적 예외 반환) |
