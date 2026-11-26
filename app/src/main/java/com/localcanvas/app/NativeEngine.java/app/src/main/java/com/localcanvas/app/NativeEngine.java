package com.localcanvas.app;
import android.graphics.Bitmap;
public final class NativeEngine {
 private static final boolean LOADED;
 static { boolean x; try { System.loadLibrary("localcanvas_native"); x=true; } catch (UnsatisfiedLinkError e) { x=false; } LOADED=x; }
 private NativeEngine() {}
 public static boolean isNativeLibraryLoaded(){return LOADED;}
 public static native boolean nativeIsAvailable();
 public static native String nativeBackendStatus();
 public static native Bitmap nativeGenerate(String modelPath,String prompt,String negativePrompt,int width,int height,int steps,float cfg,long seed,int threads);
}  
