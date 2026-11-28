#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#ifndef LOCALCANVAS_HAS_SDCPP
#define LOCALCANVAS_HAS_SDCPP 0
#endif

#define LOG_TAG "LocalCanvasNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_state_mutex;
static std::atomic<bool> g_generating{false};
static std::atomic<int> g_progress{0};
static std::string g_last_error;
static std::string g_last_gen_info;
static std::string g_active_backend = "none";
static constexpr const char* kEngineCommit = "dee497f4df67786a24a30b46176ebc188f247192";

static void setError(const char* text) {
    std::lock_guard<std::mutex> l(g_state_mutex);
    g_last_error = text ? text : "unknown native error";
}

static void throwJava(JNIEnv* env, const char* type, const std::string& message) {
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message.c_str());
}

static std::string jstrToStd(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* p = env->GetStringUTFChars(js, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(js, p);
    return out;
}

#if LOCALCANVAS_HAS_SDCPP
#include "stable-diffusion.h"

static sd_ctx_t* g_cached_ctx = nullptr;
static std::string g_cached_key;
static std::string g_runtime_devices;
static std::once_flag g_runtime_init;

static void logCallback(enum sd_log_level_t level, const char* text, void*) {
    if (!text) return;
    if (level >= SD_LOG_ERROR) LOGE("sd.cpp: %s", text);
    else LOGI("sd.cpp: %s", text);
}

static void progressCallback(const sd_generation_progress_t* p, void*) {
    if (p && p->total > 0) {
        int pct = (int)((p->completed * 100) / p->total);
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        g_progress.store(pct);
    }
}

static void initRuntime() {
    sd_set_log_callback(logCallback, nullptr);
    size_t n = sd_list_devices(nullptr, 0);
    if (n > 0) {
        g_runtime_devices.resize(n);
        sd_list_devices(g_runtime_devices.data(), n + 1);
    }
}

static std::string engineError(const char* fallback) {
    const char* e = sd_get_last_error();
    return (e && *e) ? std::string(e) : std::string(fallback);
}

static void freeCachedContextLocked() {
    if (g_cached_ctx) {
        sd_dgl_ctx_t* d = sd_ctx_get_dgl_ctx(g_cached_ctx);
        if (d) {
            sd_dgl_ctx_unload_models(d);
            sd_dgl_ctx_release_staging(d);
        }
        sd_ctx_free(g_cached_ctx);
        g_cached_ctx = nullptr;
        g_cached_key.clear();
        g_active_backend = "none";
    }
}

static jobject tensorToBitmap(JNIEnv* env, const sd_tensor_t* image, int rw, int rh) {
    if (!image || sd_tensor_type(image) != SD_TENSOR_U8 || sd_tensor_rank(image) != 3) {
        setError("engine returned a non-U8 rank-3 image tensor");
        return nullptr;
    }
    int c = (int)sd_tensor_dim(image, 0);
    int w = (int)sd_tensor_dim(image, 1);
    int h = (int)sd_tensor_dim(image, 2);
    if (c != 3 || w <= 0 || h <= 0 || (rw > 0 && w != rw) || (rh > 0 && h != rh)) {
        setError("image tensor shape is not RGB [3,width,height] matching the request");
        return nullptr;
    }
    size_t expected = (size_t)c * w * h;
    if (sd_tensor_byte_size(image) != expected || !sd_tensor_data(image)) {
        setError("image tensor byte size or data pointer is invalid");
        return nullptr;
    }

    jclass cc = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID fid = env->GetStaticFieldID(cc, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(cc, fid);
    jclass bc = env->FindClass("android/graphics/Bitmap");
    jmethodID create = env->GetStaticMethodID(bc, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bc, create, w, h, config);
    if (!bitmap || env->ExceptionCheck()) return nullptr;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        setError("Android Bitmap is not RGBA_8888");
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        setError("Failed to lock Android Bitmap pixels");
        return nullptr;
    }
    const auto* src = (const uint8_t*)sd_tensor_data(image);
    for (int y = 0; y < h; y++) {
        auto* row = (uint32_t*)((uint8_t*)pixels + y * info.stride);
        for (int x = 0; x < w; x++) {
            size_t i = ((size_t)y * w + x) * 3;
            row[x] = 0xff000000u | ((uint32_t)src[i] << 16) | ((uint32_t)src[i + 1] << 8) | src[i + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

static sd_tensor_t* bitmapToRgbTensor(JNIEnv* env, jobject bitmap) {
    if (!bitmap) return nullptr;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        setError("Input bitmap must be non-empty RGBA_8888");
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        setError("Failed to lock input bitmap pixels");
        return nullptr;
    }
    int w = (int)info.width;
    int h = (int)info.height;
    std::vector<uint8_t> rgb((size_t)w * h * 3);
    for (int y = 0; y < h; y++) {
        const auto* row = (const uint32_t*)((const uint8_t*)pixels + y * info.stride);
        for (int x = 0; x < w; x++) {
            uint32_t px = row[x];
            size_t i = ((size_t)y * w + x) * 3;
            rgb[i]     = (uint8_t)(px & 0xffu);
            rgb[i + 1] = (uint8_t)((px >> 8) & 0xffu);
            rgb[i + 2] = (uint8_t)((px >> 16) & 0xffu);
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    int64_t shape[3] = {3, w, h};
    return sd_tensor_create(SD_TENSOR_U8, 3, shape, rgb.data(), rgb.size());
}

static sd_status_t createContextForBackend(
        const std::string& model, const std::string& vae,
        const std::string& controlNet, const std::string& ipAdapter,
        const std::string& backend, int threads, bool flashAttn, bool enableMmap, bool vaeConvDirect,
        sd_ctx_t** outCtx) {
    sd_ctx_opt_t* co = sd_ctx_opt_create();
    if (!co) return SD_STATUS_OUT_OF_MEMORY;
    sd_status_t st = sd_opt_set_string(co, "model_path", model.c_str());
    if (st == SD_STATUS_OK && !vae.empty()) st = sd_opt_set_string(co, "vae_path", vae.c_str());
    if (st == SD_STATUS_OK && !controlNet.empty()) st = sd_opt_set_string(co, "control_net_path", controlNet.c_str());
    if (st == SD_STATUS_OK && !ipAdapter.empty()) st = sd_opt_set_string(co, "ip_adapter_path", ipAdapter.c_str());
    if (st == SD_STATUS_OK) st = sd_opt_set_int(co, "n_threads", threads > 0 ? threads : 4);
    if (st == SD_STATUS_OK) st = sd_opt_set_string(co, "backend", backend.c_str());
    if (st == SD_STATUS_OK) st = sd_opt_set_string(co, "params_backend", backend.c_str());
    if (st == SD_STATUS_OK) st = sd_opt_set_string(co, "lora_apply_mode", "at_runtime");
    if (st == SD_STATUS_OK) st = sd_opt_set_bool(co, "flash_attn", flashAttn);
    if (st == SD_STATUS_OK) st = sd_opt_set_bool(co, "diffusion_flash_attn", flashAttn);
    if (st == SD_STATUS_OK) st = sd_opt_set_bool(co, "enable_mmap", enableMmap);
    if (st == SD_STATUS_OK && vaeConvDirect) st = sd_opt_set_bool(co, "vae_conv_direct", true);
    if (st == SD_STATUS_OK) st = sd_ctx_create(co, outCtx);
    sd_opt_free(co);
    return st;
}
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localcanvas_app_NativeEngine_nativeIsAvailable(JNIEnv*, jclass) {
#if LOCALCANVAS_HAS_SDCPP
    std::call_once(g_runtime_init, initRuntime);
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcanvas_app_NativeEngine_nativeRuntimeStatus(JNIEnv* env, jclass) {
#if LOCALCANVAS_HAS_SDCPP
    std::call_once(g_runtime_init, initRuntime);
    std::lock_guard<std::mutex> l(g_state_mutex);
    std::string s = std::string("engine=stable-diffusion.cpp; commit=") + kEngineCommit + "; vulkan=compiled; active_backend="
            + g_active_backend + "; devices=" + g_runtime_devices;
    return env->NewStringUTF(s.c_str());
#else
    return env->NewStringUTF("engine=unavailable");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcanvas_app_NativeEngine_nativeBackendStatus(JNIEnv* env, jclass) {
#if LOCALCANVAS_HAS_SDCPP
    std::call_once(g_runtime_init, initRuntime);
    std::lock_guard<std::mutex> l(g_state_mutex);
    std::string s = std::string("stable-diffusion.cpp linked; commit=") + kEngineCommit + "; Vulkan compiled; active=" + g_active_backend
            + "; devices: " + g_runtime_devices;
    return env->NewStringUTF(s.c_str());
#else
    return env->NewStringUTF("native library loaded, stable-diffusion.cpp unavailable");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcanvas_app_NativeEngine_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_state_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localcanvas_app_NativeEngine_nativeLastGenInfo(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_state_mutex);
    return env->NewStringUTF(g_last_gen_info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localcanvas_app_NativeEngine_nativeIsGenerating(JNIEnv*, jclass) {
    return g_generating.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localcanvas_app_NativeEngine_nativeProgress(JNIEnv*, jclass) {
    return g_progress.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_localcanvas_app_NativeEngine_nativeCancel(JNIEnv*, jclass) {
#if LOCALCANVAS_HAS_SDCPP
    std::lock_guard<std::mutex> l(g_state_mutex);
    if (g_cached_ctx && g_generating.load()) {
        sd_cancel_generation(g_cached_ctx, SD_CANCEL_ALL);
    }
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localcanvas_app_NativeEngine_nativeUnload(JNIEnv*, jclass) {
#if LOCALCANVAS_HAS_SDCPP
    if (g_generating.load()) {
        setError("cannot unload a model while generation is running");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> l(g_state_mutex);
    freeCachedContextLocked();
    g_progress.store(0);
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_localcanvas_app_NativeEngine_nativeGenerateEx(
        JNIEnv* env, jclass,
        jstring jm, jstring jvae, jstring jcn, jstring jipa,
        jobjectArray jLoraPaths, jfloatArray jLoraWeights,
        jstring jp, jstring jn,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jint batchCount, jint clipSkip, jint threads,
        jstring jsampler, jstring jscheduler,
        jboolean useVulkan, jboolean cpuFallback, jboolean flashAttn,
        jboolean enableMmap, jboolean vaeTiling,
        jboolean hiresEnabled, jfloat hiresScale, jint hiresSteps, jfloat hiresDenoise, jstring jhiresUpscaler,
        jobject jInitBitmap, jobject jMaskBitmap, jfloat strength,
        jobject jControlBitmap, jstring jControlType, jfloat controlStrength,
        jobject jIpAdapterBitmap, jfloat ipAdapterStrength) {
#if !LOCALCANVAS_HAS_SDCPP
    throwJava(env, "java/lang/UnsupportedOperationException", "stable-diffusion.cpp is not linked into this build");
    return nullptr;
#else
    if (width < 64 || height < 64 || width > 2048 || height > 2048 || steps < 1 || steps > 200 || batchCount < 1 || batchCount > 8) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid generation dimensions, steps, or batchCount");
        return nullptr;
    }
    bool idle = false;
    if (!g_generating.compare_exchange_strong(idle, true)) {
        throwJava(env, "java/lang/IllegalStateException", "another generation is already running");
        return nullptr;
    }

    std::string model = jstrToStd(env, jm);
    std::string vae = jstrToStd(env, jvae);
    std::string cn = jstrToStd(env, jcn);
    std::string ipa = jstrToStd(env, jipa);
    std::string prompt = jstrToStd(env, jp);
    std::string negative = jstrToStd(env, jn);
    std::string sampler = jstrToStd(env, jsampler);
    std::string scheduler = jstrToStd(env, jscheduler);
    std::string hiresUpscaler = jstrToStd(env, jhiresUpscaler);
    std::string controlType = jstrToStd(env, jControlType);

    std::vector<std::string> loraPaths;
    std::vector<float> loraWeights;
    if (jLoraPaths && jLoraWeights) {
        jsize lpLen = env->GetArrayLength(jLoraPaths);
        jsize lwLen = env->GetArrayLength(jLoraWeights);
        jsize nLoras = lpLen < lwLen ? lpLen : lwLen;
        jfloat* wPtr = env->GetFloatArrayElements(jLoraWeights, nullptr);
        for (jsize i = 0; i < nLoras; i++) {
            auto js = (jstring)env->GetObjectArrayElement(jLoraPaths, i);
            std::string p = jstrToStd(env, js);
            if (!p.empty() && wPtr) {
                loraPaths.push_back(p);
                loraWeights.push_back(wPtr[i]);
            }
            if (js) env->DeleteLocalRef(js);
        }
        if (wPtr) env->ReleaseFloatArrayElements(jLoraWeights, wPtr, JNI_ABORT);
    }

    std::string desiredBackend = useVulkan ? "vulkan" : "cpu";
    std::string cacheKey = model + "|" + vae + "|" + cn + "|" + ipa + "|" + desiredBackend
            + "|" + (flashAttn ? "1" : "0") + "|" + (enableMmap ? "1" : "0") + "|" + (vaeTiling ? "1" : "0");

    sd_ctx_t* ctx = nullptr;
    sd_img_gen_opt_t* io = nullptr;
    sd_result_t* result = nullptr;
    sd_tensor_t* initTensor = nullptr;
    sd_tensor_t* maskTensor = nullptr;
    sd_tensor_t* controlTensor = nullptr;
    sd_tensor_t* ipAdapterTensor = nullptr;
    jobjectArray outputArray = nullptr;
    std::string failure;

    {
        std::lock_guard<std::mutex> l(g_state_mutex);
        if (g_cached_ctx && g_cached_key == cacheKey) {
            ctx = g_cached_ctx;
        } else {
            freeCachedContextLocked();
            sd_status_t st = createContextForBackend(model, vae, cn, ipa, desiredBackend, threads, flashAttn, enableMmap, vaeTiling, &ctx);
            if (st != SD_STATUS_OK && useVulkan && cpuFallback) {
                LOGI("Vulkan context creation failed (%s), falling back to CPU", engineError("").c_str());
                desiredBackend = "cpu";
                cacheKey = model + "|" + vae + "|" + cn + "|" + ipa + "|cpu|"
                        + (flashAttn ? "1" : "0") + "|" + (enableMmap ? "1" : "0") + "|" + (vaeTiling ? "1" : "0");
                st = createContextForBackend(model, vae, cn, ipa, "cpu", threads, flashAttn, enableMmap, vaeTiling, &ctx);
            }
            if (st != SD_STATUS_OK || !ctx) {
                failure = engineError("context creation failed");
            } else {
                g_cached_ctx = ctx;
                g_cached_key = cacheKey;
                g_active_backend = desiredBackend;
            }
        }
    }

    if (failure.empty() && !sd_ctx_supports_image_generation(ctx)) {
        const char* v = sd_get_model_version_name(ctx);
        failure = std::string("model does not support image generation: ") + (v ? v : "unknown");
    }

    if (failure.empty()) {
        sd_set_generation_progress_callback(ctx, progressCallback, nullptr);
        g_progress.store(0);
        io = sd_img_gen_opt_create();
        if (!io) {
            failure = "failed to allocate image generation options";
        } else {
            sd_status_t st = SD_STATUS_OK;
            if (st == SD_STATUS_OK) st = sd_opt_set_string(io, "prompt", prompt.c_str());
            if (st == SD_STATUS_OK) st = sd_opt_set_string(io, "negative_prompt", negative.c_str());
            if (st == SD_STATUS_OK) st = sd_opt_set_int(io, "width", width);
            if (st == SD_STATUS_OK) st = sd_opt_set_int(io, "height", height);
            if (st == SD_STATUS_OK) st = sd_opt_set_int(io, "seed", seed);
            if (st == SD_STATUS_OK) st = sd_opt_set_int(io, "batch_count", batchCount);
            if (st == SD_STATUS_OK && clipSkip >= 1) st = sd_opt_set_int(io, "clip_skip", clipSkip);
            if (st == SD_STATUS_OK) st = sd_opt_set_int(io, "sample.steps", steps);
            if (st == SD_STATUS_OK) st = sd_opt_set_float(io, "sample.guidance.txt_cfg", cfg);
            if (st == SD_STATUS_OK && !sampler.empty()) st = sd_opt_set_string(io, "sample.method", sampler.c_str());
            if (st == SD_STATUS_OK && !scheduler.empty()) st = sd_opt_set_string(io, "sample.scheduler", scheduler.c_str());
            if (st == SD_STATUS_OK && vaeTiling) st = sd_opt_set_bool(io, "vae_tiling.enabled", true);

            if (st == SD_STATUS_OK && !loraPaths.empty()) {
                std::vector<sd_lora_t> loras;
                loras.reserve(loraPaths.size());
                for (size_t i = 0; i < loraPaths.size(); ++i) {
                    loras.push_back(sd_lora_t{false, loraWeights[i], loraPaths[i].c_str()});
                }
                st = sd_opt_set_loras(io, "loras", loras.data(), loras.size());
            }

            if (st == SD_STATUS_OK && jInitBitmap) {
                initTensor = bitmapToRgbTensor(env, jInitBitmap);
                if (!initTensor) st = SD_STATUS_INVALID_ARGUMENT;
                else {
                    st = sd_opt_set_tensor(io, "init_image", initTensor);
                    if (st == SD_STATUS_OK) st = sd_opt_set_float(io, "strength", strength);
                }
            }
            if (st == SD_STATUS_OK && jMaskBitmap) {
                maskTensor = bitmapToRgbTensor(env, jMaskBitmap);
                if (!maskTensor) st = SD_STATUS_INVALID_ARGUMENT;
                else st = sd_opt_set_tensor(io, "mask_image", maskTensor);
            }
            if (st == SD_STATUS_OK && jControlBitmap) {
                controlTensor = bitmapToRgbTensor(env, jControlBitmap);
                if (!controlTensor) st = SD_STATUS_INVALID_ARGUMENT;
                else {
                    st = sd_opt_set_tensor(io, "control_image", controlTensor);
                    if (st == SD_STATUS_OK && !controlType.empty()) st = sd_opt_set_string(io, "control_type", controlType.c_str());
                    if (st == SD_STATUS_OK) st = sd_opt_set_float(io, "control_strength", controlStrength);
                }
            }
            if (st == SD_STATUS_OK && jIpAdapterBitmap) {
                ipAdapterTensor = bitmapToRgbTensor(env, jIpAdapterBitmap);
                if (!ipAdapterTensor) st = SD_STATUS_INVALID_ARGUMENT;
                else {
                    st = sd_opt_set_tensor(io, "ip_adapter_image", ipAdapterTensor);
                    if (st == SD_STATUS_OK) st = sd_opt_set_float(io, "ip_adapter_strength", ipAdapterStrength);
                }
            }
            if (st == SD_STATUS_OK && hiresEnabled) {
                st = sd_opt_set_bool(io, "hires.enabled", true);
                if (st == SD_STATUS_OK) st = sd_opt_set_float(io, "hires.scale", hiresScale);
                if (st == SD_STATUS_OK && hiresSteps > 0) st = sd_opt_set_int(io, "hires.steps", hiresSteps);
                if (st == SD_STATUS_OK) st = sd_opt_set_float(io, "hires.denoising_strength", hiresDenoise);
                if (st == SD_STATUS_OK && !hiresUpscaler.empty()) st = sd_opt_set_string(io, "hires.upscaler", hiresUpscaler.c_str());
            }

            if (st != SD_STATUS_OK) {
                failure = engineError("failed to configure generation options");
            } else if (sd_generate_image(ctx, io, &result) != SD_STATUS_OK) {
                failure = engineError("image generation failed");
            }
        }
        sd_set_generation_progress_callback(ctx, nullptr, nullptr);
    }

    if (failure.empty()) {
        size_t count = result ? sd_result_get_image_count(result) : 0;
        if (count < 1) {
            failure = "engine returned no image";
        } else {
            jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
            outputArray = env->NewObjectArray((jsize)count, bitmapCls, nullptr);
            int expectedW = hiresEnabled ? 0 : width;
            int expectedH = hiresEnabled ? 0 : height;
            for (size_t i = 0; i < count; i++) {
                jobject bmp = tensorToBitmap(env, sd_result_get_image(result, i), expectedW, expectedH);
                if (!bmp) {
                    failure = "failed to convert image tensor to Bitmap";
                    outputArray = nullptr;
                    break;
                }
                env->SetObjectArrayElement(outputArray, (jsize)i, bmp);
            }
            if (failure.empty()) {
                const char* info = sd_result_get_generation_info(result, 0);
                std::lock_guard<std::mutex> l(g_state_mutex);
                g_last_gen_info = info ? info : "";
            }
        }
    }

    if (initTensor) sd_tensor_free(initTensor);
    if (maskTensor) sd_tensor_free(maskTensor);
    if (controlTensor) sd_tensor_free(controlTensor);
    if (ipAdapterTensor) sd_tensor_free(ipAdapterTensor);
    if (result) sd_result_free(result);
    if (io) sd_opt_free(io);

    g_generating.store(false);
    if (!failure.empty()) {
        setError(failure.c_str());
        throwJava(env, "java/lang/IllegalStateException", failure);
        return nullptr;
    }
    setError("");
    return outputArray;
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_localcanvas_app_NativeEngine_nativeGenerate(
        JNIEnv* env, jclass cls,
        jstring jm, jstring jp, jstring jn,
        jint width, jint height, jint steps, jfloat cfg, jlong seed, jint threads,
        jstring jsampler, jstring jscheduler) {
    jobjectArray arr = Java_com_localcanvas_app_NativeEngine_nativeGenerateEx(
            env, cls, jm, nullptr, nullptr, nullptr, nullptr, nullptr, jp, jn,
            width, height, steps, cfg, seed, 1, 1, threads,
            jsampler, jscheduler, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_FALSE,
            JNI_FALSE, 1.0f, 0, 0.5f, nullptr,
            nullptr, nullptr, 0.75f,
            nullptr, nullptr, 0.8f,
            nullptr, 0.8f);
    if (!arr || env->GetArrayLength(arr) < 1) return nullptr;
    return env->GetObjectArrayElement(arr, 0);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_localcanvas_app_NativeEngine_nativeUpscale(
        JNIEnv* env, jclass, jstring jUpscalerModel, jobject jBitmap, jint tileSize, jint threads, jboolean useVulkan) {
#if !LOCALCANVAS_HAS_SDCPP
    throwJava(env, "java/lang/UnsupportedOperationException", "stable-diffusion.cpp is not linked into this build");
    return nullptr;
#else
    std::string model = jstrToStd(env, jUpscalerModel);
    sd_tensor_t* input = bitmapToRgbTensor(env, jBitmap);
    if (!input) {
        throwJava(env, "java/lang/IllegalArgumentException", "invalid input bitmap for upscale");
        return nullptr;
    }
    sd_upscaler_opt_t* load = sd_upscaler_opt_create();
    sd_upscaler_t* upscaler = nullptr;
    sd_upscale_opt_t* opt = nullptr;
    sd_result_t* result = nullptr;
    jobject outBmp = nullptr;
    std::string failure;

    if (!load) {
        failure = "out of memory";
    } else {
        sd_status_t st = sd_opt_set_string(load, "model_path", model.c_str());
        if (st == SD_STATUS_OK) st = sd_opt_set_int(load, "n_threads", threads > 0 ? threads : 4);
        if (st == SD_STATUS_OK) st = sd_opt_set_string(load, "backend", useVulkan ? "vulkan" : "cpu");
        if (st == SD_STATUS_OK && tileSize > 0) st = sd_opt_set_int(load, "tile_size", tileSize);
        if (st == SD_STATUS_OK) st = sd_upscaler_create(load, &upscaler);
        if (st == SD_STATUS_OK && upscaler) {
            opt = sd_upscale_opt_create();
            if (!opt) st = SD_STATUS_OUT_OF_MEMORY;
            if (st == SD_STATUS_OK) st = sd_opt_set_tensor(opt, "image", input);
            if (st == SD_STATUS_OK) st = sd_upscale(upscaler, opt, &result);
        }
        if (st != SD_STATUS_OK) failure = engineError("upscale failed");
    }

    if (failure.empty() && result && sd_result_get_image_count(result) > 0) {
        outBmp = tensorToBitmap(env, sd_result_get_image(result, 0), 0, 0);
        if (!outBmp) failure = "failed to decode upscaled tensor";
    } else if (failure.empty()) {
        failure = "upscaler returned no image";
    }

    sd_tensor_free(input);
    if (result) sd_result_free(result);
    if (opt) sd_opt_free(opt);
    if (upscaler) sd_upscaler_free(upscaler);
    if (load) sd_opt_free(load);

    if (!failure.empty()) {
        setError(failure.c_str());
        throwJava(env, "java/lang/IllegalStateException", failure);
        return nullptr;
    }
    return outBmp;
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_localcanvas_app_NativeEngine_nativePreprocessCanny(
        JNIEnv* env, jclass, jobject jBitmap, jfloat highThreshold, jfloat lowThreshold, jfloat weak, jfloat strong) {
#if !LOCALCANVAS_HAS_SDCPP
    throwJava(env, "java/lang/UnsupportedOperationException", "stable-diffusion.cpp is not linked into this build");
    return nullptr;
#else
    (void)jBitmap; (void)highThreshold; (void)lowThreshold; (void)weak; (void)strong;
    throwJava(env, "java/lang/UnsupportedOperationException",
              "Canny preprocessing is not exposed by the pinned stable-diffusion.cpp C API");
    return nullptr;
#endif
}
