#include <jni.h>
#include <android/bitmap.h>
#include <atomic>
#include <mutex>
#include <string>
#ifndef LOCALCANVAS_HAS_SDCPP
#define LOCALCANVAS_HAS_SDCPP 0
#endif
#if LOCALCANVAS_HAS_SDCPP
#include "stable-diffusion.h"
static std::atomic<int> progress{0}; static std::mutex mu; static sd_ctx_t* cached=nullptr; static sd_ctx_t* running=nullptr; static std::string cachedModel;
static void cb(const sd_generation_progress_t* p,void*){if(p&&p->total)progress.store((int)(p->completed*100/p->total));}
#endif
static void error(JNIEnv* e,const char* t,const char* m){jclass c=e->FindClass(t);if(c)e->ThrowNew(c,m);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localcanvas_app_NativeEngine_nativeIsAvailable(JNIEnv*,jclass){return LOCALCANVAS_HAS_SDCPP?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT jstring JNICALL Java_com_localcanvas_app_NativeEngine_nativeBackendStatus(JNIEnv*e,jclass){return e->NewStringUTF(LOCALCANVAS_HAS_SDCPP?"stable-diffusion.cpp + Vulkan requested":"native engine not linked");}
extern "C" JNIEXPORT jint JNICALL Java_com_localcanvas_app_NativeEngine_nativeProgress(JNIEnv*,jclass){#if LOCALCANVAS_HAS_SDCPP
return progress.load();#else
return -1;#endif}
extern "C" JNIEXPORT void JNICALL Java_com_localcanvas_app_NativeEngine_nativeCancel(JNIEnv*,jclass){#if LOCALCANVAS_HAS_SDCPP
if(running)sd_cancel_generation(running,SD_CANCEL_ALL);#endif}
extern "C" JNIEXPORT void JNICALL Java_com_localcanvas_app_NativeEngine_nativeUnload(JNIEnv*,jclass){#if LOCALCANVAS_HAS_SDCPP
std::lock_guard<std::mutex>l(mu);if(cached){auto*d=sd_ctx_get_dgl_ctx(cached);if(d){sd_dgl_ctx_unload_models(d);sd_dgl_ctx_release_staging(d);}sd_ctx_free(cached);cached=nullptr;cachedModel.clear();}progress.store(0);#endif}
#if LOCALCANVAS_HAS_SDCPP
static jobject bitmap(JNIEnv*e,const uint8_t*s,int w,int h,int c){jclass cc=e->FindClass("android/graphics/Bitmap$Config");jfieldID f=e->GetStaticFieldID(cc,"ARGB_8888","Landroid/graphics/Bitmap$Config;");jobject cfg=e->GetStaticObjectField(cc,f);jclass bc=e->FindClass("android/graphics/Bitmap");jmethodID m=e->GetStaticMethodID(bc,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");jobject b=e->CallStaticObjectMethod(bc,m,w,h,cfg);AndroidBitmapInfo i{};void*p=nullptr;AndroidBitmap_getInfo(e,b,&i);AndroidBitmap_lockPixels(e,b,&p);for(int y=0;y<h;y++){auto*r=(uint32_t*)((uint8_t*)p+y*i.stride);for(int x=0;x<w;x++){size_t k=(size_t)(y*w+x)*c;uint8_t R=s[k],G=c>1?s[k+1]:R,B=c>2?s[k+2]:R;r[x]=0xff000000u|(R<<16)|(G<<8)|B;}}AndroidBitmap_unlockPixels(e,b);return b;}
#endif
extern "C" JNIEXPORT jobject JNICALL Java_com_localcanvas_app_NativeEngine_nativeGenerate(JNIEnv*e,jclass,jstring jm,jstring jp,jstring jn,jint w,jint h,jint steps,jfloat cfg,jlong seed,jint threads){#if !LOCALCANVAS_HAS_SDCPP
error(e,"java/lang/UnsupportedOperationException","stable-diffusion.cpp is not linked");return nullptr;#else
const char*m=e->GetStringUTFChars(jm,nullptr),*p=e->GetStringUTFChars(jp,nullptr),*n=e->GetStringUTFChars(jn,nullptr);sd_ctx_t*ctx=nullptr;sd_ctx_opt_t*o=sd_ctx_opt_create();sd_img_gen_opt_t*io=nullptr;sd_result_t*r=nullptr;{std::lock_guard<std::mutex>l(mu);if(cached&&cachedModel==m)ctx=cached;else{if(cached)sd_ctx_free(cached);sd_opt_set_string(o,"model_path",m);sd_opt_set_int(o,"n_threads",threads>0?threads:4);if(sd_ctx_create(o,&ctx)!=SD_STATUS_OK){error(e,"java/lang/IllegalStateException",sd_get_last_error());goto done;}cached=ctx;cachedModel=m;}}running=ctx;sd_set_generation_progress_callback(ctx,cb,nullptr);progress.store(0);io=sd_img_gen_opt_create();if(!io||sd_opt_set_string(io,"prompt",p)!=SD_STATUS_OK||sd_opt_set_string(io,"negative_prompt",n)!=SD_STATUS_OK||sd_opt_set_int(io,"width",w)!=SD_STATUS_OK||sd_opt_set_int(io,"height",h)!=SD_STATUS_OK||sd_opt_set_int(io,"seed",seed)!=SD_STATUS_OK||sd_opt_set_int(io,"sample.steps",steps)!=SD_STATUS_OK||sd_opt_set_float(io,"sample.guidance.txt_cfg",cfg)!=SD_STATUS_OK||sd_opt_set_string(io,"sample.method","euler")!=SD_STATUS_OK||sd_generate_image(ctx,io,&r)!=SD_STATUS_OK){error(e,"java/lang/IllegalStateException",sd_get_last_error());goto done;}if(!r||sd_result_get_image_count(r)<1){error(e,"java/lang/IllegalStateException","engine returned no image");goto done;}{auto*t=sd_result_get_image(r,0);auto*d=(const uint8_t*)sd_tensor_data(t);size_t z=sd_tensor_byte_size(t);int c=z>=(size_t)w*h*4?4:3;jobject out=bitmap(e,d,w,h,c);sd_result_free(r);sd_opt_free(io);sd_opt_free(o);running=nullptr;e->ReleaseStringUTFChars(jm,m);e->ReleaseStringUTFChars(jp,p);e->ReleaseStringUTFChars(jn,n);return out;}done:running=nullptr;if(r)sd_result_free(r);if(io)sd_opt_free(io);if(o)sd_opt_free(o);e->ReleaseStringUTFChars(jm,m);e->ReleaseStringUTFChars(jp,p);e->ReleaseStringUTFChars(jn,n);return nullptr;#endif}
  
