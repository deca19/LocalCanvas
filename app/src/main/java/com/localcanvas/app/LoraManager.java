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
