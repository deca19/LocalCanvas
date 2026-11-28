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
        public String version;
        public String fileName;
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
            this.version = "";
            this.fileName = "";
            this.sizeBytes = size;
            this.estimatedRamBytes = ram;
            this.description = desc;
        }

        public StoreItem(String src, String id, String name, String arch, String kind,
                         String lic, String url, long size, long ram, String desc,
                         String version, String fileName) {
            this(src, id, name, arch, kind, lic, url, size, ram, desc);
            this.version = version != null ? version : "";
            this.fileName = fileName != null ? fileName : "";
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
            if (id.isEmpty() || o.optBoolean("private", false) || o.optBoolean("gated", false)) continue;
            if (!id.equals("stable-diffusion-v1-5/stable-diffusion-v1-5") && !id.equals("stabilityai/stable-diffusion-xl-base-1.0") && !id.equals("madebyollin/sdxl-vae-fp16-fix") && !id.equals("stabilityai/sd-vae-ft-mse-original")) continue;
            JSONArray tags = o.optJSONArray("tags");
            String arch = detectArch(id, tags);
            String kind = (tags != null && tags.toString().toLowerCase(Locale.US).contains("lora")) ? "LoRA" : "Checkpoint";
            String license = "HuggingFace (" + o.optString("license", "원문 확인") + ")";
            long estRam = arch.contains("XL") ? 6500L * 1024 * 1024 : 3800L * 1024 * 1024;
            for (HfFile file : resolveFiles(id)) {
                out.add(new StoreItem("HuggingFace", id.replace("/", "_") + "_" + file.name.hashCode(),
                        id + " · " + file.name, arch, kind, license, file.url, file.size, estRam,
                        "Downloads: " + o.optLong("downloads", 0) + " · Likes: " + o.optLong("likes", 0),
                        "main", file.name));
            }
        }
        return out;
    }

    /**
     * Returns a direct file only when the repository exposes one plausibly
     * usable single-file checkpoint. A repository HTML page is never used as
     * a download URL: diffusers component repositories remain manual/import
     * only until an explicit compatible file is selected.
     */
    private List<HfFile> resolveFiles(String repoId) {
        List<HfFile> out = new ArrayList<>();
        try {
            JSONObject meta = new JSONObject(httpGet("https://huggingface.co/api/models/" + repoId + "?blobs=true&files_metadata=true"));
            JSONArray siblings = meta.optJSONArray("siblings");
            if (siblings == null) return out;
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
            return candidates;
        } catch (Exception ignored) {
            return out;
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
            if (item.optBoolean("nsfw", true)) continue;
            String name = item.optString("name", "Unknown");
            String type = item.optString("type", "Checkpoint");
            JSONArray versions = item.optJSONArray("modelVersions");
            if (versions == null || versions.length() == 0) continue;
            for (int vi = 0; vi < versions.length(); vi++) {
                JSONObject version = versions.getJSONObject(vi);
                String baseModel = version.optString("baseModel", "Unknown");
                JSONArray files = version.optJSONArray("files");
                if (files == null) continue;
                for (int fi = 0; fi < files.length(); fi++) {
                    JSONObject file = files.getJSONObject(fi);
                    String dlUrl = file.optString("downloadUrl", null);
                    String fileName = file.optString("name", "file-" + fi);
                    if (dlUrl == null || !dlUrl.startsWith("https://")) continue;
                    long sizeBytes = (long) file.optDouble("sizeKB", 0) * 1024L;
                    long estRam = baseModel.toUpperCase(Locale.US).contains("XL") ? 6500L * 1024 * 1024 : 3800L * 1024 * 1024;
                    String versionName = version.optString("name", "v" + (vi + 1));
                    out.add(new StoreItem("Civitai", "civitai_" + item.optLong("id") + "_" + vi + "_" + fi,
                            name + " · " + versionName + " · " + fileName, baseModel, type,
                            "Civitai License", dlUrl, sizeBytes, estRam,
                            "Version: " + versionName, versionName, fileName));
                }
            }
        }
        return out;
    }

    private static String detectArch(String id, JSONArray tags) {
        String s = (id + " " + (tags != null ? tags.toString() : "")).toLowerCase(Locale.US);
        if (s.contains("flux")) return "FLUX";
        if (s.contains("sdxl") || s.contains("-xl")) return "SDXL";
        if (s.contains("sd3")) return "SD3.5";
        if (s.contains("sd15") || s.contains("sd-1.5") || s.contains("stable-diffusion-v1-5")) return "SD 1.5";
        if (s.contains("sd2") || s.contains("stable-diffusion-2")) return "SD 2.x";
        return "Unknown";
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
