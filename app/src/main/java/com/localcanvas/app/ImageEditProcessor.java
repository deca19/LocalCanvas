package com.localcanvas.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Image preprocessing and PNG tEXt metadata utilities for Img2Img, Inpainting,
 * Outpainting, Crop/Rotate/Resize, and metadata-preserving PNG storage.
 */
public final class ImageEditProcessor {
    private static final byte[] PNG_SIG = {(byte)137, 80, 78, 71, 13, 10, 26, 10};

    private ImageEditProcessor() {}

    public static final class OutpaintPair {
        public final Bitmap initBitmap;
        public final Bitmap maskBitmap;
        OutpaintPair(Bitmap i, Bitmap m) { initBitmap = i; maskBitmap = m; }
    }

    /** Pads source image and generates a matching inpaint mask (black=keep, white=outpaint). */
    public static OutpaintPair createOutpaintPair(Bitmap src, int left, int top, int right, int bottom) {
        int newW = align8(src.getWidth() + Math.max(0, left) + Math.max(0, right));
        int newH = align8(src.getHeight() + Math.max(0, top) + Math.max(0, bottom));
        Bitmap init = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(init);
        ic.drawColor(Color.DKGRAY);
        ic.drawBitmap(src, Math.max(0, left), Math.max(0, top), null);

        Bitmap mask = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888);
        Canvas mc = new Canvas(mask);
        mc.drawColor(Color.WHITE);
        Paint keep = new Paint();
        keep.setColor(Color.BLACK);
        mc.drawRect(Math.max(0, left), Math.max(0, top),
                Math.max(0, left) + src.getWidth(), Math.max(0, top) + src.getHeight(), keep);
        return new OutpaintPair(init, mask);
    }

    public static Bitmap rotate(Bitmap src, float degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static Bitmap centerCropSquare(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        return Bitmap.createBitmap(src, x, y, size, size);
    }

    public static Bitmap resizeBicubic(Bitmap src, int targetW, int targetH) {
        return Bitmap.createScaledBitmap(src, Math.max(64, targetW), Math.max(64, targetH), true);
    }

    /**
     * CPU Canny-style edge map for ControlNet previews. The result is a real
     * binary RGB control image and can be passed to the native ControlNet path.
     * Thresholds are normalized to [0,1]; weak/strong control the output levels.
     */
    public static Bitmap canny(Bitmap src, float highThreshold, float lowThreshold,
                               float weak, float strong) {
        if (src == null || src.getWidth() < 3 || src.getHeight() < 3) {
            throw new IllegalArgumentException("Canny 입력 이미지가 너무 작습니다");
        }
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = new float[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            gray[i] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f;
        }
        float hi = Math.max(0.01f, Math.min(1f, highThreshold)) * 255f;
        float lo = Math.max(0f, Math.min(highThreshold, lowThreshold)) * 255f;
        int weakValue = Math.max(1, Math.min(255, Math.round(Math.max(0f, Math.min(1f, weak)) * 255f)));
        int strongValue = Math.max(1, Math.min(255, Math.round(Math.max(0f, Math.min(1f, strong)) * 255f)));
        int[] out = new int[w * h];
        boolean[] strongEdge = new boolean[w * h];
        boolean[] weakEdge = new boolean[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = -gray[i - w - 1] - 2f * gray[i - 1] - gray[i + w - 1]
                        + gray[i - w + 1] + 2f * gray[i + 1] + gray[i + w + 1];
                float gy = -gray[i - w - 1] - 2f * gray[i - w] - gray[i - w + 1]
                        + gray[i + w - 1] + 2f * gray[i + w] + gray[i + w + 1];
                float magnitude = (float) (Math.sqrt(gx * gx + gy * gy) * 255f);
                if (magnitude >= hi) strongEdge[i] = true;
                else if (magnitude >= lo) weakEdge[i] = true;
            }
        }
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                boolean keep = strongEdge[i];
                if (!keep && weakEdge[i]) {
                    for (int dy = -1; dy <= 1 && !keep; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (strongEdge[(y + dy) * w + x + dx]) { keep = true; break; }
                        }
                    }
                }
                int value = keep ? (strongEdge[i] ? strongValue : weakValue) : 0;
                out[i] = Color.rgb(value, value, value);
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    /**
     * Builds the control image that this app can prepare without a secondary
     * vision model. OpenPose intentionally remains unsupported here: a pose
     * detector is required and the source bitmap must not be mislabeled as a
     * pose map.
     */
    public static Bitmap controlPreview(Bitmap src, String type) {
        String mode = type == null ? "none" : type.toLowerCase(java.util.Locale.US);
        if ("none".equals(mode) || "tile".equals(mode)) return src;
        if ("canny".equals(mode) || "lineart".equals(mode)) {
            return canny(src, 0.8f, 0.1f, 0.5f, 1.0f);
        }
        if ("softedge".equals(mode) || "scribble".equals(mode)) {
            return sobelEdge(src);
        }
        if ("depth".equals(mode)) return depthLumaProxy(src);
        if ("openpose".equals(mode)) {
            throw new UnsupportedOperationException("OpenPose 전처리 모델이 설치되지 않았습니다");
        }
        throw new IllegalArgumentException("지원하지 않는 ControlNet 전처리: " + type);
    }

    /** Soft-edge style Sobel magnitude map for compatible scribble/softedge models. */
    public static Bitmap sobelEdge(Bitmap src) {
        if (src == null || src.getWidth() < 3 || src.getHeight() < 3) {
            throw new IllegalArgumentException("전처리 입력 이미지가 너무 작습니다");
        }
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = new float[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            gray[i] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f;
        }
        int[] out = new int[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = -gray[i - w - 1] - 2f * gray[i - 1] - gray[i + w - 1]
                        + gray[i - w + 1] + 2f * gray[i + 1] + gray[i + w + 1];
                float gy = -gray[i - w - 1] - 2f * gray[i - w] - gray[i - w + 1]
                        + gray[i + w - 1] + 2f * gray[i + w] + gray[i + w + 1];
                int value = Math.max(0, Math.min(255, Math.round((float) Math.sqrt(gx * gx + gy * gy) * 255f)));
                out[i] = Color.rgb(value, value, value);
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }

    /** Luminance depth proxy; true metric depth requires a dedicated depth model. */
    public static Bitmap depthLumaProxy(Bitmap src) {
        if (src == null) throw new IllegalArgumentException("전처리 입력 이미지가 없습니다");
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int value = 255 - Math.round(0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p));
            pixels[i] = Color.rgb(value, value, value);
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
    }

    private static int align8(int v) {
        return Math.max(64, ((v + 7) / 8) * 8);
    }

    /** Saves a Bitmap as PNG and injects a standard PNG tEXt chunk before IEND. */
    public static boolean savePngWithMetadata(Bitmap bmp, File target, String key, String value) {
        if (bmp == null || target == null) return false;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, raw)) return false;
        byte[] pngBytes = raw.toByteArray();
        if (key == null || key.isEmpty() || value == null || pngBytes.length < 20) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(pngBytes);
                return target.isFile() && target.length() > 0;
            } catch (IOException e) {
                return false;
            }
        }

        // Insert tEXt chunk right before the final 12-byte IEND chunk
        int iendOffset = pngBytes.length - 12;
        byte[] keyBytes = key.getBytes(StandardCharsets.ISO_8859_1);
        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[keyBytes.length + 1 + valBytes.length];
        System.arraycopy(keyBytes, 0, payload, 0, keyBytes.length);
        payload[keyBytes.length] = 0;
        System.arraycopy(valBytes, 0, payload, keyBytes.length + 1, valBytes.length);

        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(payload);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            out.write(pngBytes, 0, iendOffset);
            out.writeInt(payload.length);
            out.write(type);
            out.write(payload);
            out.writeInt((int) crc.getValue());
            out.write(pngBytes, iendOffset, 12);
            out.flush();
            return target.isFile() && target.length() > 0;
        } catch (IOException e) {
            target.delete();
            return false;
        }
    }

    /** Reads PNG tEXt chunks without decoding pixel data. */
    public static Map<String, String> readPngMetadata(File pngFile) {
        Map<String, String> map = new LinkedHashMap<>();
        if (pngFile == null || !pngFile.isFile() || pngFile.length() < 24) return map;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(pngFile)))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            for (int i = 0; i < 8; i++) if (sig[i] != PNG_SIG[i]) return map;
            while (true) {
                int len = in.readInt();
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.US_ASCII);
                if (len < 0 || len > 4 * 1024 * 1024) break;
                if ("tEXt".equals(type)) {
                    byte[] data = new byte[len];
                    in.readFully(data);
                    int nul = -1;
                    for (int i = 0; i < data.length; i++) {
                        if (data[i] == 0) { nul = i; break; }
                    }
                    if (nul > 0) {
                        String k = new String(data, 0, nul, StandardCharsets.ISO_8859_1);
                        String v = new String(data, nul + 1, data.length - nul - 1, StandardCharsets.UTF_8);
                        map.put(k, v);
                    }
                } else {
                    long skipped = 0;
                    while (skipped < len) {
                        long s = in.skip(len - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                }
                in.readInt(); // CRC
                if ("IEND".equals(type)) break;
            }
        } catch (Exception ignored) {}
        return map;
    }
}
