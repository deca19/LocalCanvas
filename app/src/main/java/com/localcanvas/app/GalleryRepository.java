package com.localcanvas.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asynchronous gallery repository with LruCache thumbnails, pagination,
 * PNG metadata inspection, favorites/tags, and MediaStore export.
 */
public final class GalleryRepository {
    public interface ThumbnailCallback {
        void onLoaded(File file, Bitmap thumb);
    }

    public static final class GalleryItem {
        public final File file;
        public final boolean favorite;
        public final String tags;
        public final Map<String, String> pngMeta;

        GalleryItem(File f, boolean fav, String tags, Map<String, String> meta) {
            this.file = f;
            this.favorite = fav;
            this.tags = tags;
            this.pngMeta = meta;
        }
    }

    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>(24 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private final Context context;
    private final File galleryDir;
    private final SharedPreferences prefs;

    public GalleryRepository(Context c) {
        this.context = c.getApplicationContext();
        this.galleryDir = new File(context.getFilesDir(), "gallery");
        this.galleryDir.mkdirs();
        this.prefs = context.getSharedPreferences("gallery_meta", Context.MODE_PRIVATE);
    }

    public File directory() { return galleryDir; }

    public List<GalleryItem> listPaged(String search, boolean favoritesOnly, int offset, int limit) {
        File[] files = galleryDir.listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".png"));
        List<GalleryItem> result = new ArrayList<>();
        if (files == null || files.length == 0) return result;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String q = search == null ? "" : search.trim().toLowerCase(Locale.US);

        List<GalleryItem> filtered = new ArrayList<>();
        for (File f : files) {
            String key = f.getName();
            boolean fav = prefs.getBoolean("fav." + key, false);
            String tags = prefs.getString("tag." + key, "");
            if (favoritesOnly && !fav) continue;
            Map<String, String> meta = ImageEditProcessor.readPngMetadata(f);
            if (!q.isEmpty()) {
                String haystack = (key + " " + tags + " " + meta.toString()).toLowerCase(Locale.US);
                if (!haystack.contains(q)) continue;
            }
            filtered.add(new GalleryItem(f, fav, tags, meta));
        }

        int start = Math.max(0, Math.min(offset, filtered.size()));
        int end = Math.min(filtered.size(), start + Math.max(1, limit));
        return filtered.subList(start, end);
    }

    public void loadThumbnailAsync(File file, int maxW, int maxH, ThumbnailCallback callback) {
        String key = file.getAbsolutePath() + "@" + maxW + "x" + maxH;
        Bitmap cached = THUMB_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            callback.onLoaded(file, cached);
            return;
        }
        TaskRuntime.decodeExecutor().submit(() -> {
            Bitmap bmp = decodeDownsampled(file, maxW, maxH);
            if (bmp != null) {
                THUMB_CACHE.put(key, bmp);
            }
            callback.onLoaded(file, bmp);
        });
    }

    public static Bitmap decodeDownsampled(File file, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / sample > maxW * 2 || o.outHeight / sample > maxH * 2) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, sample);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
    }

    public void setFavorite(File file, boolean fav) {
        prefs.edit().putBoolean("fav." + file.getName(), fav).apply();
    }

    public void setTags(File file, String tags) {
        prefs.edit().putString("tag." + file.getName(), tags != null ? tags.trim() : "").apply();
    }

    public boolean delete(File file) {
        prefs.edit().remove("fav." + file.getName()).remove("tag." + file.getName()).apply();
        return file.delete();
    }

    public Uri exportToMediaStore(File pngFile) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, pngFile.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalCanvas");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore 항목을 생성할 수 없습니다");
        try (FileInputStream in = new FileInputStream(pngFile);
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("MediaStore 출력 스트림 열기 실패");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return uri;
    }
}
