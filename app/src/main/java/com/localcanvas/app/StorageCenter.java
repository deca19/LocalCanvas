package com.localcanvas.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reports app-private storage usage across categories, cleans caches/orphans,
 * detects duplicates, backs up/restores settings & studios, and migrates preferences.
 */
public final class StorageCenter {
    private static final int SCHEMA_VERSION = 15;
    private final Context c;

    public StorageCenter(Context x) {
        c = x.getApplicationContext();
        migrateIfNeeded();
    }

    private void migrateIfNeeded() {
        SharedPreferences meta = c.getSharedPreferences("localcanvas_meta", Context.MODE_PRIVATE);
        int current = meta.getInt("schema_version", 14);
        if (current < SCHEMA_VERSION) {
            meta.edit().putInt("schema_version", SCHEMA_VERSION).apply();
        }
    }

    public long freeBytes() {
        return new StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes();
    }

    public long directoryBytes(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] a = f.listFiles();
        if (a != null) for (File x : a) n += directoryBytes(x);
        return n;
    }

    public long modelsBytes() { return directoryBytes(new File(c.getFilesDir(), "models")); }
    public long lorasBytes() { return directoryBytes(new File(c.getFilesDir(), "loras")); }
    public long vaesBytes() { return directoryBytes(new File(c.getFilesDir(), "vaes")); }
    public long galleryBytes() { return directoryBytes(new File(c.getFilesDir(), "gallery")); }
    public long projectsBytes() { return directoryBytes(new File(c.getFilesDir(), "projects")); }
    public long cacheBytes() { return directoryBytes(c.getCacheDir()); }

    public int cleanCacheAndOrphans() {
        int removed = 0;
        removed += deleteChildren(c.getCacheDir());
        removed += AtomicModelFile.cleanupStaleArtifacts(new File(c.getFilesDir(), "models"));
        removed += AtomicModelFile.cleanupStaleArtifacts(new File(c.getFilesDir(), "loras"));
        return removed;
    }

    private static int deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) count += deleteChildren(f);
            if (f.delete()) count++;
        }
        return count;
    }

    public List<String> findDuplicateModels() {
        List<String> dupes = new ArrayList<>();
        File dir = new File(c.getFilesDir(), "models");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".model"));
        if (files == null) return dupes;
        Map<String, String> byHash = new HashMap<>();
        for (File f : files) {
            try {
                String h = ModelManager.hash(f);
                String prev = byHash.putIfAbsent(h, f.getName());
                if (prev != null) dupes.add(f.getName() + " == " + prev);
            } catch (Exception e) {
                DiagnosticsCenter.log("STORAGE_ERR", "해시 계산 실패(" + f.getName() + "): " + e.getMessage());
            }
        }
        return dupes;
    }

    public File exportBackup(CharacterStudio characters) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA_VERSION);
        root.put("timestamp", System.currentTimeMillis());
        root.put("settings", new JSONObject(c.getSharedPreferences("v14_settings", Context.MODE_PRIVATE).getAll()));
        root.put("prompts", new JSONObject(c.getSharedPreferences("prompt_studio", Context.MODE_PRIVATE).getAll()));
        root.put("characters", new JSONObject(characters.exportAllJson()));

        File backupDir = new File(c.getFilesDir(), "backups");
        backupDir.mkdirs();
        File out = new File(backupDir, "localcanvas_backup_" + System.currentTimeMillis() + ".json");
        Files.write(out.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    public boolean restoreLatestBackup(CharacterStudio characters) {
        File backupDir = new File(c.getFilesDir(), "backups");
        File[] files = backupDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return false;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        try {
            String raw = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(raw);
            if (root.has("settings")) {
                restorePrefs(c.getSharedPreferences("v14_settings", Context.MODE_PRIVATE), root.getJSONObject("settings"));
            }
            if (root.has("prompts")) {
                restorePrefs(c.getSharedPreferences("prompt_studio", Context.MODE_PRIVATE), root.getJSONObject("prompts"));
            }
            if (root.has("characters")) {
                characters.importAllJson(root.getJSONObject("characters").toString());
            }
            return true;
        } catch (Exception e) {
            DiagnosticsCenter.log("BACKUP_ERR", "백업 복원 실패: " + e.getMessage());
            return false;
        }
    }

    private static void restorePrefs(SharedPreferences prefs, JSONObject obj) {
        SharedPreferences.Editor ed = prefs.edit();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = obj.opt(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Double) ed.putFloat(k, ((Double) v).floatValue());
            else if (v instanceof String) ed.putString(k, (String) v);
        }
        ed.apply();
    }

    public String report() {
        long freeMb = freeBytes() / 1024 / 1024;
        String warn = freeMb < 2048 ? "\n⚠ 저장 공간 2GB 미만 경고!" : "";
        return "모델: " + modelsBytes() / 1024 / 1024 + " MB"
                + " · LoRA: " + lorasBytes() / 1024 / 1024 + " MB"
                + " · VAE: " + vaesBytes() / 1024 / 1024 + " MB\n"
                + "갤러리: " + galleryBytes() / 1024 / 1024 + " MB"
                + " · 프로젝트: " + projectsBytes() / 1024 / 1024 + " MB"
                + " · 캐시: " + cacheBytes() / 1024 / 1024 + " MB\n"
                + "사용 가능: " + freeMb + " MB" + warn;
    }
}
