package com.localcanvas.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages LoRA files, metadata, categories, trigger words, favorites, and active weights.
 */
public final class LoraManager {
    public static final class LoraEntry {
        public final String id;
        public final String name;
        public final String category; // Character, Style, Clothing, Concept
        public final String architecture; // SD1.5, SDXL, FLUX
        public final String triggerWords;
        public final File file;
        public boolean enabled;
        public float weight;
        public boolean favorite;

        LoraEntry(String id, String name, String cat, String arch, String triggers,
                  File file, boolean enabled, float weight, boolean favorite) {
            this.id = id;
            this.name = name;
            this.category = cat;
            this.architecture = arch;
            this.triggerWords = triggers;
            this.file = file;
            this.enabled = enabled;
            this.weight = weight;
            this.favorite = favorite;
        }

        public boolean isInstalled() {
            return file != null && file.isFile() && file.length() > 0;
        }
    }

    private final Context context;
    private final File root;
    private final SharedPreferences prefs;

    public LoraManager(Context c) {
        this.context = c.getApplicationContext();
        this.root = new File(context.getFilesDir(), "loras");
        this.root.mkdirs();
        this.prefs = context.getSharedPreferences("lora_registry", Context.MODE_PRIVATE);
    }

    public File directory() { return root; }

    public List<LoraEntry> list(String query, String categoryFilter, boolean favoritesOnly) {
        List<LoraEntry> out = new ArrayList<>();
        File[] files = root.listFiles((d, n) -> n.endsWith(".safetensors") || n.endsWith(".lora"));
        if (files == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (File f : files) {
            String id = f.getName();
            JSONObject meta = readMeta(id);
            String name = meta.optString("name", id);
            String cat = meta.optString("category", "Style");
            String arch = meta.optString("architecture", "SD 1.5");
            String triggers = meta.optString("triggers", "");
            boolean enabled = meta.optBoolean("enabled", false);
            float weight = (float) meta.optDouble("weight", 0.8);
            boolean fav = meta.optBoolean("favorite", false);

            if (favoritesOnly && !fav) continue;
            if (categoryFilter != null && !categoryFilter.equals("All") && !categoryFilter.equalsIgnoreCase(cat)) continue;
            if (!q.isEmpty() && !name.toLowerCase(Locale.US).contains(q)
                    && !triggers.toLowerCase(Locale.US).contains(q)) continue;

            out.add(new LoraEntry(id, name, cat, arch, triggers, f, enabled, weight, fav));
        }
        return out;
    }

    public List<LoraEntry> activeLoras() {
        List<LoraEntry> out = new ArrayList<>();
        for (LoraEntry e : list(null, "All", false)) {
            if (e.enabled && e.isInstalled()) out.add(e);
        }
        return out;
    }

    public File importLora(Uri uri, String id, String name, String category, String arch, String triggers) throws IOException {
        ModelManager.validId(id);
        String fileName = id.endsWith(".safetensors") ? id : id + ".safetensors";
        File dest = new File(root, fileName);
        File part = new File(root, fileName + ".import.part");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException("파일을 열 수 없습니다");
            byte[] buf = new byte[512 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (!DownloadValidator.isSupportedModel(part)) {
            part.delete();
            throw new IOException("유효한 SafeTensors/GGUF LoRA 파일이 아닙니다");
        }
        AtomicModelFile.replace(part, dest);
        saveMeta(fileName, name, category, arch, triggers, false, 0.8f, false);
        return dest;
    }

    /** Downloads a verified HTTPS LoRA into the LoRA registry without routing it through checkpoints. */
    public File download(String id, String url, String expectedSha256, ModelManager.Progress progress,
                         AtomicBoolean cancel, String name, String category, String arch, String triggers) throws Exception {
        ModelManager.validId(id);
        if (url == null || !url.toLowerCase(Locale.US).startsWith("https://")) {
            throw new IOException("LoRA 자동 설치에는 검증된 HTTPS 주소가 필요합니다");
        }
        File dest = new File(root, id + ".safetensors");
        File part = new File(root, id + ".safetensors.part");
        if (!ModelLeaseManager.shared().tryAcquireWrite(dest.getAbsolutePath())) {
            throw new IOException("이미 설치 중이거나 사용 중인 LoRA입니다");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("LoRA 다운로드 HTTP " + code);
            long total = conn.getContentLengthLong();
            if (total > 0 && root.getUsableSpace() < total + 32L * 1024L * 1024L) {
                throw new IOException("LoRA 설치에 필요한 저장 공간이 부족합니다");
            }
            long started = System.currentTimeMillis();
            long done = 0;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(part))) {
                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel != null && cancel.get()) throw new CancellationException("LoRA 다운로드가 취소되었습니다");
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total, Math.max(1L, System.currentTimeMillis() - started));
                }
            }
            if (!part.isFile() || part.length() == 0) throw new IOException("빈 LoRA 파일입니다");
            if (total > 0 && part.length() != total) throw new IOException("LoRA 다운로드가 완전히 끝나지 않았습니다");
            if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                    && !expectedSha256.equalsIgnoreCase(sha256(part))) {
                throw new IOException("LoRA SHA-256 무결성 검사 실패");
            }
            if (!DownloadValidator.isSupportedModel(part)) throw new IOException("손상되었거나 지원되지 않는 LoRA 파일입니다");
            AtomicModelFile.replace(part, dest);
            saveMeta(dest.getName(), name, category, arch, triggers, false, 0.8f, false);
            return dest;
        } finally {
            if (conn != null) conn.disconnect();
            if (part.isFile()) part.delete();
            ModelLeaseManager.shared().releaseWrite(dest.getAbsolutePath());
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : md.digest()) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }

    public void updateState(String id, boolean enabled, float weight, boolean favorite) {
        JSONObject meta = readMeta(id);
        try {
            meta.put("enabled", enabled);
            meta.put("weight", Math.max(-2.0f, Math.min(2.0f, weight)));
            meta.put("favorite", favorite);
            prefs.edit().putString(id, meta.toString()).apply();
        } catch (Exception ignored) {}
    }

    public boolean delete(String id) {
        File f = new File(root, id);
        if (ModelLeaseManager.shared().isBusy(f.getAbsolutePath())) return false;
        prefs.edit().remove(id).apply();
        return !f.exists() || f.delete();
    }

    public String buildTriggerString() {
        List<String> parts = new ArrayList<>();
        for (LoraEntry e : activeLoras()) {
            if (e.triggerWords != null && !e.triggerWords.trim().isEmpty()) {
                parts.add(e.triggerWords.trim());
            }
        }
        return String.join(", ", parts);
    }

    private void saveMeta(String id, String name, String cat, String arch, String triggers,
                          boolean enabled, float weight, boolean fav) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name != null && !name.isEmpty() ? name : id);
            o.put("category", cat != null ? cat : "Style");
            o.put("architecture", arch != null ? arch : "SD 1.5");
            o.put("triggers", triggers != null ? triggers : "");
            o.put("enabled", enabled);
            o.put("weight", weight);
            o.put("favorite", fav);
            prefs.edit().putString(id, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONObject readMeta(String id) {
        String raw = prefs.getString(id, null);
        if (raw == null) return new JSONObject();
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }
}
