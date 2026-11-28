package com.localcanvas.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;

/**
 * Searches public Hugging Face and Civitai (SFW-filtered) model catalogs.
 * Never fabricates results when offline; returns explicit error messages instead.
 */
public final class ModelStoreRepository {
    public static final class StoreItem {
        public final String source; // "HuggingFace" or "Civitai"
        public final String id;
        public final String name;
        public final String architecture;
        public final String kind; // "Checkpoint", "LoRA", "VAE", "ControlNet"
        public final String license;
        public final String downloadUrl;
        public final long sizeBytes;
        public final long estimatedRamBytes;
        public final String description;

        public StoreItem(String src, String id, String name, String arch, String kind,
                         String lic, String url, long size, long ram, String desc) {
            this.source = src;
            this.id = id;
            this.name = name;
            this.architecture = arch;
            this.kind = kind;
            this.license = lic;
            this.downloadUrl = url;
            this.sizeBytes = size;
            this.estimatedRamBytes = ram;
            this.description = desc;
        }
    }

    public List<StoreItem> searchHuggingFace(String query, String kindFilter) throws Exception {
        String q = URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8.name());
        String filter = "diffusers";
        if ("LoRA".equalsIgnoreCase(kindFilter)) filter = "lora";
        String urlStr = "https://huggingface.co/api/models?search=" + q + "&filter=" + filter + "&limit=15&sort=downloads&direction=-1";
        String body = httpGet(urlStr);
        JSONArray arr = new JSONArray(body);
        List<StoreItem> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            JSONArray tags = o.optJSONArray("tags");
            String arch = detectArch(id, tags);
            String kind = (tags != null && tags.toString().toLowerCase(Locale.US).contains("lora")) ? "LoRA" : "Checkpoint";
            String license = "HuggingFace (" + o.optString("license", "원문 확인") + ")";
            long estRam = arch.contains("XL") ? 6500L * 1024 * 1024 : 3800L * 1024 * 1024;
            HfFile file = resolveSingleFile(id);
            out.add(new StoreItem("HuggingFace", id.replace("/", "_") + (file != null ? "_" + file.name.hashCode() : ""), id, arch, kind, license,
                    file != null ? file.url : null, file != null ? file.size : 0L, estRam,
                    "Downloads: " + o.optLong("downloads", 0) + " · Likes: " + o.optLong("likes", 0)));
        }
        return out;
    }

    /**
     * Returns a direct file only when the repository exposes one plausibly
     * usable single-file checkpoint. A repository HTML page is never used as
     * a download URL: diffusers component repositories remain manual/import
     * only until an explicit compatible file is selected.
     */
    private HfFile resolveSingleFile(String repoId) {
        try {
            JSONObject meta = new JSONObject(httpGet("https://huggingface.co/api/models/" + repoId + "?blobs=true&files_metadata=true"));
            JSONArray siblings = meta.optJSONArray("siblings");
            if (siblings == null) return null;
            List<HfFile> candidates = new ArrayList<>();
            for (int i = 0; i < siblings.length(); i++) {
                JSONObject s = siblings.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("rfilename", "");
                String lower = name.toLowerCase(Locale.US);
                if (name.contains("/") || !(lower.endsWith(".safetensors") || lower.endsWith(".gguf") || lower.endsWith(".ckpt"))) continue;
                long size = s.optLong("size", 0L);
                if (size < 100L * 1024L * 1024L) continue;
                candidates.add(new HfFile(name, size,
                        "https://huggingface.co/" + repoId + "/resolve/main/" + name + "?download=true"));
            }
            candidates.sort(Comparator.comparingLong((HfFile f) -> f.size).reversed());
            return candidates.size() == 1 ? candidates.get(0) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class HfFile {
        final String name, url; final long size;
        HfFile(String n, long s, String u) { name = n; size = s; url = u; }
    }

    public List<StoreItem> searchCivitaiSfw(String query, String kindFilter) throws Exception {
        String q = URLEncoder.encode(query == null ? "" : query.trim(), StandardCharsets.UTF_8.name());
        String types = "";
        if ("Checkpoint".equalsIgnoreCase(kindFilter)) types = "&types=Checkpoint";
        else if ("LoRA".equalsIgnoreCase(kindFilter)) types = "&types=LORA";
        else if ("VAE".equalsIgnoreCase(kindFilter)) types = "&types=VAE";
        else if ("ControlNet".equalsIgnoreCase(kindFilter)) types = "&types=Controlnet";

        // Strict SFW filter: nsfw=false
        String urlStr = "https://civitai.com/api/v1/models?limit=15&nsfw=false&query=" + q + types;
        String body = httpGet(urlStr);
        JSONObject root = new JSONObject(body);
        JSONArray items = root.optJSONArray("items");
        List<StoreItem> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.optBoolean("nsfw", false)) continue;
            String name = item.optString("name", "Unknown");
            String type = item.optString("type", "Checkpoint");
            JSONArray versions = item.optJSONArray("modelVersions");
            if (versions == null || versions.length() == 0) continue;
            JSONObject v0 = versions.getJSONObject(0);
            String baseModel = v0.optString("baseModel", "SD 1.5");
            JSONArray files = v0.optJSONArray("files");
            String dlUrl = null;
            long sizeKb = 0;
            if (files != null && files.length() > 0) {
                JSONObject f0 = files.getJSONObject(0);
                dlUrl = f0.optString("downloadUrl", null);
                sizeKb = (long) f0.optDouble("sizeKB", 0);
            }
            long sizeBytes = sizeKb * 1024L;
            long estRam = baseModel.toUpperCase(Locale.US).contains("XL") ? 6500L * 1024 * 1024 : 3800L * 1024 * 1024;
            out.add(new StoreItem("Civitai", "civitai_" + item.optLong("id"), name, baseModel, type,
                    "Civitai License", dlUrl, sizeBytes, estRam,
                    "Version: " + v0.optString("name", "v1")));
        }
        return out;
    }

    private static String detectArch(String id, JSONArray tags) {
        String s = (id + " " + (tags != null ? tags.toString() : "")).toLowerCase(Locale.US);
        if (s.contains("flux")) return "FLUX";
        if (s.contains("sdxl") || s.contains("-xl")) return "SDXL";
        if (s.contains("sd3")) return "SD3.5";
        return "SD 1.5";
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "LocalCanvas-Android/1.6");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }
}
