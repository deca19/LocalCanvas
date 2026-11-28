package com.localcanvas.app;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages models, VAEs, ControlNets, and Upscalers in app-private storage.
 * Enforces ModelLeaseManager locks, URL+ETag bound resumable downloads, bounded retries,
 * full GGUF/SafeTensors validation, and same-directory atomic replacement.
 */
public final class ModelManager {
    private static final int MAX_RESUME_RESTARTS = 3;
    private static final long SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L;

    public interface Progress {
        void onProgress(long done, long total, long elapsedMs);
    }

    public enum AssetType {
        CHECKPOINT("models", ".model"),
        VAE("vaes", ".vae"),
        CONTROLNET("controlnets", ".cn"),
        UPSCALER("upscalers", ".upscaler");

        public final String dirName;
        public final String ext;
        AssetType(String d, String e) { dirName = d; ext = e; }
    }

    public static final class Model {
        public final String id, name, architecture, license, downloadUrl, sha256;
        public final long expectedBytes;
        public final File file;
        public final AssetType type;

        Model(String i, String n, String a, String l, long e, String u, String h, File f, AssetType t) {
            id = i; name = n; architecture = a; license = l;
            expectedBytes = e; downloadUrl = u; sha256 = h; file = f; type = t;
        }

        public boolean isInstalled() {
            return file.isFile() && file.length() > 0;
        }
    }

    private static final class Spec {
        final String id, name, arch, license, url, sha;
        final long estimate;
        final AssetType type;
        Spec(String i, String n, String a, String l, long e, String u, String h, AssetType t) {
            id = i; name = n; arch = a; license = l; estimate = e; url = u; sha = h; type = t;
        }
    }

    private static final List<Spec> CATALOG = Arrays.asList(
            new Spec("sd15", "Stable Diffusion 1.5", "SD 1.x", "CreativeML Open RAIL-M", 4270000000L,
                    "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors", null, AssetType.CHECKPOINT),
            new Spec("sd2", "Stable Diffusion 2.x", "SD 2.x", "라이선스 원문 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("sdxl", "Stable Diffusion XL", "SDXL", "Stability AI license", 6940000000L,
                    "https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0/resolve/main/sd_xl_base_1.0.safetensors",
                    "31e35c80fc4829d14f90153f4c74cd59c90b779f6afe05a74cd6120b893f7e5b", AssetType.CHECKPOINT),
            new Spec("illustrious-xl", "Illustrious XL", "SDXL derivative", "저작자 라이선스 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("noobai-xl", "NoobAI XL", "SDXL derivative", "저작자 라이선스 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("pony-xl", "Pony Diffusion XL", "SDXL derivative", "저작자 라이선스 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("animagine-xl", "Animagine XL", "SDXL derivative", "저작자 라이선스 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("flux", "FLUX", "DiT/FLUX", "접근 조건 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("sd35", "Stable Diffusion 3.5", "MMDiT", "접근 조건 확인 필요", 0, null, null, AssetType.CHECKPOINT),
            new Spec("vae-ft-mse", "SD1.5 VAE ft-MSE", "SD 1.x VAE", "CreativeML Open RAIL-M", 335000000L,
                    "https://huggingface.co/stabilityai/sd-vae-ft-mse-original/resolve/main/vae-ft-mse-840000-ema-pruned.safetensors", null, AssetType.VAE),
            new Spec("sdxl-vae-fp16", "SDXL VAE fp16-fix", "SDXL VAE", "MIT", 335000000L,
                    "https://huggingface.co/madebyollin/sdxl-vae-fp16-fix/resolve/main/sdxl.vae.safetensors", null, AssetType.VAE)
    );

    private final Context context;
    private final File baseDir;
    private final File root;
    private final boolean allowLocalhostForTest;

    public ModelManager(Context c) {
        this.context = c.getApplicationContext();
        this.baseDir = context.getFilesDir();
        this.root = new File(baseDir, "models");
        this.allowLocalhostForTest = false;
        this.root.mkdirs();
        AtomicModelFile.cleanupStaleArtifacts(this.root);
    }

    /** Package/test constructor for pure-JVM HTTP integration tests without Android Context. */
    ModelManager(File rootDir, boolean allowLocalhostForTest) {
        this.context = null;
        this.baseDir = rootDir.getParentFile() != null ? rootDir.getParentFile() : rootDir;
        this.root = rootDir;
        this.allowLocalhostForTest = allowLocalhostForTest;
        this.root.mkdirs();
    }

    public File directory() { return root; }

    public File directoryFor(AssetType type) {
        if (type == AssetType.CHECKPOINT) return root;
        File d = new File(baseDir, type.dirName);
        d.mkdirs();
        return d;
    }

    private File fileFor(String id, AssetType type) {
        return new File(directoryFor(type), id + type.ext);
    }

    private File fileFor(String id) {
        return fileFor(id, AssetType.CHECKPOINT);
    }

    public static void validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("모델 ID 형식이 올바르지 않습니다");
        }
    }

    public long freeBytes() {
        if (context != null) {
            return new StatFs(root.getAbsolutePath()).getAvailableBytes();
        }
        return root.getUsableSpace();
    }

    public List<Model> list() {
        List<Model> out = new ArrayList<>();
        for (Spec s : CATALOG) {
            out.add(new Model(s.id, s.name, s.arch, s.license, s.estimate, s.url, s.sha,
                    fileFor(s.id, s.type), s.type));
        }
        for (AssetType t : AssetType.values()) {
            File dir = directoryFor(t);
            File[] fs = dir.listFiles((d, n) -> n.endsWith(t.ext));
            if (fs != null) {
                for (File f : fs) {
                    String id = f.getName().substring(0, f.getName().length() - t.ext.length());
                    boolean known = false;
                    for (Spec s : CATALOG) {
                        if (s.id.equals(id) && s.type == t) { known = true; break; }
                    }
                    if (!known) {
                        out.add(new Model(id, "사용자 " + t.name() + ": " + id, "사용자 지정",
                                "사용자 확인", f.length(), null, null, f, t));
                    }
                }
            }
        }
        return out;
    }

    public List<Model> listByType(AssetType type) {
        List<Model> out = new ArrayList<>();
        for (Model m : list()) {
            if (m.type == type) out.add(m);
        }
        return out;
    }

    public Model find(String id) {
        if (id == null) return null;
        for (Model m : list()) {
            if (id.equals(m.id)) return m;
        }
        return null;
    }

    public File importFile(Uri uri, String id) throws IOException {
        return importFile(uri, id, AssetType.CHECKPOINT);
    }

    public File importFile(Uri uri, String id, AssetType type) throws IOException {
        if (context == null) throw new IOException("Android Context가 필요합니다");
        validId(id);
        File dest = fileFor(id, type);
        File part = new File(dest.getPath() + ".import.part");
        String path = dest.getAbsolutePath();
        if (!ModelLeaseManager.shared().tryAcquireWrite(path)) {
            throw new IOException("사용 중이거나 다운로드 중인 모델입니다");
        }
        try {
            if (GenerationQueue.shared().isModelInUse(path)) {
                throw new IOException("대기열에서 사용 중인 모델입니다");
            }
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("파일을 열 수 없습니다");
                copy(in, part, null, new AtomicBoolean(false));
            }
            if (type == AssetType.CHECKPOINT || type == AssetType.VAE || type == AssetType.CONTROLNET) {
                validateFormat(part);
            }
            atomicReplace(part, dest);
            return dest;
        } finally {
            part.delete();
            ModelLeaseManager.shared().releaseWrite(path);
        }
    }

    public File download(String id, String url, String expectedSha256, Progress progress, AtomicBoolean cancel) throws Exception {
        return download(id, AssetType.CHECKPOINT, url, expectedSha256, progress, cancel);
    }

    /** Downloads with bounded retries, URL+ETag bound resume metadata, and atomic replacement. */
    public File download(String id, AssetType type, String url, String expectedSha256,
                         Progress progress, AtomicBoolean cancel) throws Exception {
        validId(id);
        validateUrl(url);
        File dest = fileFor(id, type);
        File part = new File(dest.getPath() + ".part");
        File meta = new File(dest.getPath() + ".part.meta");
        String lockPath = dest.getAbsolutePath();

        if (!ModelLeaseManager.shared().tryAcquireWrite(lockPath)) {
            throw new IOException("이미 다운로드 중이거나 사용 중인 모델입니다");
        }
        try {
            if (GenerationQueue.shared().isModelInUse(lockPath)) {
                throw new IOException("사용 중인 모델입니다");
            }

            long started = System.currentTimeMillis();
            for (int attempt = 0; attempt <= MAX_RESUME_RESTARTS; attempt++) {
                if (cancel != null && cancel.get()) {
                    throw new CancellationException("다운로드가 취소되었습니다");
                }
                MetaInfo savedMeta = readMeta(meta);
                if (savedMeta != null && !url.equals(savedMeta.url)) {
                    // URL changed for the same model ID: discard stale partial file
                    part.delete();
                    meta.delete();
                    savedMeta = null;
                }

                long offset = part.isFile() ? part.length() : 0;
                if (offset > 0 && freeBytes() < SAFETY_MARGIN_BYTES) {
                    throw new IOException("다운로드 재개에 필요한 저장 공간이 부족합니다");
                }

                HttpURLConnection h = null;
                try {
                    h = (HttpURLConnection) new URL(url).openConnection();
                    h.setConnectTimeout(20000);
                    h.setReadTimeout(60000);
                    h.setInstanceFollowRedirects(true);
                    h.setRequestProperty("Accept-Encoding", "identity");
                    if (offset > 0) {
                        if (savedMeta == null || savedMeta.etag == null || savedMeta.etag.isEmpty() || savedMeta.etag.startsWith("W/")) {
                            // No strong ETag bound to this partial file -> restart cleanly
                            part.delete();
                            meta.delete();
                            offset = 0;
                        } else {
                            h.setRequestProperty("Range", "bytes=" + offset + "-");
                            h.setRequestProperty("If-Range", savedMeta.etag);
                        }
                    }

                    h.connect();
                    int code = h.getResponseCode();
                    if (code == 416 && offset > 0) {
                        part.delete();
                        meta.delete();
                        if (attempt == MAX_RESUME_RESTARTS) {
                            throw new IOException("HTTP 416 재시도 한도 초과");
                        }
                        continue;
                    }
                    if (code != 200 && code != 206) {
                        throw new IOException("HTTP " + code);
                    }

                    String range = h.getHeaderField("Content-Range");
                    long contentLength = h.getContentLengthLong();
                    String responseTag = h.getHeaderField("ETag");
                    long total;

                    if (offset > 0) {
                        if (code != 206 || responseTag == null || responseTag.startsWith("W/")
                                || savedMeta == null || !savedMeta.etag.equals(responseTag)) {
                            part.delete();
                            meta.delete();
                            if (attempt == MAX_RESUME_RESTARTS) {
                                throw new IOException("재개 응답 ETag/상태 불일치로 재시도 한도 초과");
                            }
                            continue;
                        }
                        DownloadValidator.Range parsed = DownloadValidator.parseContentRange(range);
                        if (parsed.start != offset || parsed.end != parsed.total - 1
                                || (contentLength >= 0 && contentLength != parsed.end - parsed.start + 1)) {
                            throw new IOException("Content-Range가 로컬 파일과 일치하지 않습니다");
                        }
                        total = parsed.total;
                    } else if (code == 206) {
                        DownloadValidator.Range parsed = DownloadValidator.parseContentRange(range);
                        if (parsed.start != 0 || parsed.end != parsed.total - 1
                                || (contentLength >= 0 && contentLength != parsed.total)) {
                            throw new IOException("잘못된 전체 응답 범위");
                        }
                        total = parsed.total;
                    } else {
                        total = contentLength;
                    }

                    if (total > 0 && freeBytes() - SAFETY_MARGIN_BYTES < Math.max(0, total - offset)) {
                        throw new IOException("다운로드에 필요한 저장 공간이 부족합니다");
                    }

                    // Save URL + strong ETag only after validating response
                    if (responseTag != null && !responseTag.isEmpty() && !responseTag.startsWith("W/")) {
                        writeMeta(meta, url, responseTag);
                    } else if (offset == 0) {
                        meta.delete();
                    }

                    try (InputStream in = new BufferedInputStream(h.getInputStream());
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(part, offset > 0))) {
                        copy(in, out, progress, cancel, offset, total, started, directoryFor(type));
                    }

                    if (cancel != null && cancel.get()) {
                        throw new CancellationException("다운로드가 취소되었습니다");
                    }
                    if (!part.isFile() || part.length() == 0) {
                        throw new IOException("빈 파일입니다");
                    }
                    if (total > 0 && part.length() != total) {
                        throw new IOException("다운로드가 완전히 끝나지 않았습니다: " + part.length() + "/" + total);
                    }
                    if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                            && !expectedSha256.trim().equalsIgnoreCase(hash(part))) {
                        part.delete();
                        meta.delete();
                        throw new IOException("SHA-256 무결성 검사 실패");
                    }
                    if (type == AssetType.CHECKPOINT || type == AssetType.VAE || type == AssetType.CONTROLNET) {
                        validateFormat(part);
                    }
                    atomicReplace(part, dest);
                    meta.delete();
                    return dest;
                } finally {
                    if (h != null) h.disconnect();
                }
            }
            throw new IOException("다운로드 재시도 한도를 초과했습니다");
        } finally {
            ModelLeaseManager.shared().releaseWrite(lockPath);
        }
    }

    private void validateUrl(String url) {
        if (url == null) throw new IllegalArgumentException("URL이 비어 있습니다");
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("https://")) return;
        if (allowLocalhostForTest && (lower.startsWith("http://127.0.0.1:") || lower.startsWith("http://localhost:"))) {
            return;
        }
        throw new IllegalArgumentException("HTTPS 주소만 허용합니다");
    }

    private static final class MetaInfo {
        final String url, etag;
        MetaInfo(String u, String e) { url = u; etag = e; }
    }

    private static void writeMeta(File meta, String url, String etag) {
        try {
            String data = url + "\n" + etag;
            Files.write(meta.toPath(), data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static MetaInfo readMeta(File meta) {
        if (!meta.isFile()) return null;
        try {
            List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
            if (lines.size() >= 2) {
                String u = lines.get(0).trim();
                String e = lines.get(1).trim();
                if (!u.isEmpty() && !e.isEmpty()) return new MetaInfo(u, e);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream in, File file, Progress p, AtomicBoolean cancel) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            copy(in, out, p, cancel, 0, -1, System.currentTimeMillis(), file.getParentFile());
        }
    }

    private static void copy(InputStream in, OutputStream out, Progress p, AtomicBoolean cancel,
                             long offset, long total, long started, File spaceRoot) throws IOException {
        byte[] buf = new byte[1024 * 1024];
        long done = offset;
        long lastNotify = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (cancel != null && cancel.get()) {
                throw new CancellationException("다운로드가 취소되었습니다");
            }
            out.write(buf, 0, n);
            done += n;
            if (spaceRoot != null && total > 0 && spaceRoot.getUsableSpace() - SAFETY_MARGIN_BYTES < Math.max(0, total - done)) {
                throw new IOException("다운로드 중 저장 공간이 부족합니다");
            }
            long now = System.currentTimeMillis();
            if (p != null && (now - lastNotify >= 150 || (total > 0 && done >= total))) {
                lastNotify = now;
                p.onProgress(done, total, Math.max(1L, now - started));
            }
        }
    }

    static void atomicReplace(File part, File dest) throws IOException {
        AtomicModelFile.replace(part, dest);
    }

    private static void validateFormat(File f) throws IOException {
        if (!DownloadValidator.isSupportedModel(f)) {
            throw new IOException("지원되지 않거나 손상된 모델 파일 형식입니다");
        }
    }

    public boolean remove(String id) {
        Model m = find(id);
        return remove(id, m != null ? m.type : AssetType.CHECKPOINT);
    }

    public boolean remove(String id, AssetType type) {
        validId(id);
        File f = fileFor(id, type);
        String path = f.getAbsolutePath();
        if (!ModelLeaseManager.shared().tryAcquireWrite(path)) return false;
        try {
            if (GenerationQueue.shared().isModelInUse(path) || NativeEngine.isGeneratingSafe()) return false;
            if (NativeEngine.isNativeLibraryLoaded() && !NativeEngine.nativeUnload()) return false;
            File p = new File(f.getPath() + ".part");
            File meta = new File(f.getPath() + ".part.meta");
            return (!f.exists() || f.delete()) && (!p.exists() || p.delete()) && (!meta.exists() || meta.delete());
        } finally {
            ModelLeaseManager.shared().releaseWrite(path);
        }
    }

    public static String hash(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] b = new byte[1024 * 1024];
            int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.US, "%02x", b));
        return s.toString();
    }
}
