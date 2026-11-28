package com.localcanvas.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Manages Webtoon / Storyboard projects: per-scene prompts, characters, models,
 * speech bubbles / SFX / text overlays, vertical scroll strip export with OOM guard, and JSON backup.
 */
public final class ProjectManager {
    private static final int MAX_STRIP_HEIGHT_PX = 16384;

    public static final class Scene {
        public final String id;
        public String title;
        public String prompt;
        public String negative;
        public String characterName;
        public String modelId;
        public String imagePath;
        public String dialogue;
        public String sfx;
        public float bubbleX = 0.5f;
        public float bubbleY = 0.18f;
        public float textSizeSp = 18f;
        public int spacingPx = 28;

        public Scene(String id, String title, String prompt) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.title = title != null ? title : "장면";
            this.prompt = prompt != null ? prompt : "";
            this.negative = "";
            this.characterName = "";
            this.modelId = "sd15";
            this.imagePath = "";
            this.dialogue = "";
            this.sfx = "";
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title);
                o.put("prompt", prompt);
                o.put("negative", negative);
                o.put("characterName", characterName);
                o.put("modelId", modelId);
                o.put("imagePath", imagePath);
                o.put("dialogue", dialogue);
                o.put("sfx", sfx);
                o.put("bubbleX", bubbleX);
                o.put("bubbleY", bubbleY);
                o.put("textSizeSp", textSizeSp);
                o.put("spacingPx", spacingPx);
            } catch (Exception e) {
                DiagnosticsCenter.log("PROJECT_ERR", "장면 직렬화 오류: " + e.getMessage());
            }
            return o;
        }

        public static Scene fromJson(JSONObject o) {
            Scene s = new Scene(o.optString("id"), o.optString("title", "장면"), o.optString("prompt", ""));
            s.negative = o.optString("negative", "");
            s.characterName = o.optString("characterName", "");
            s.modelId = o.optString("modelId", "sd15");
            s.imagePath = o.optString("imagePath", "");
            s.dialogue = o.optString("dialogue", "");
            s.sfx = o.optString("sfx", "");
            s.bubbleX = (float) o.optDouble("bubbleX", 0.5);
            s.bubbleY = (float) o.optDouble("bubbleY", 0.18);
            s.textSizeSp = (float) o.optDouble("textSizeSp", 18.0);
            s.spacingPx = o.optInt("spacingPx", 28);
            return s;
        }
    }

    private final File root;

    public ProjectManager(Context c) {
        root = new File(c.getFilesDir(), "projects");
        root.mkdirs();
    }

    public File create(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IOException("프로젝트 이름이 올바르지 않습니다");
        }
        File f = new File(root, id);
        if (!f.exists() && !f.mkdirs()) throw new IOException("프로젝트 폴더를 만들 수 없습니다");
        new File(f, "scenes").mkdirs();
        return f;
    }

    public List<File> list() {
        File[] a = root.listFiles(File::isDirectory);
        return a == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(a));
    }

    public File scene(File project, String name) throws IOException {
        File f = new File(new File(project, "scenes"), name + ".txt");
        if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) {
            throw new IOException("장면 폴더를 만들 수 없습니다");
        }
        return f;
    }

    public File root() { return root; }

    public List<Scene> loadScenes(File project) {
        List<Scene> out = new ArrayList<>();
        File manifest = new File(project, "storyboard.json");
        if (!manifest.isFile()) return out;
        try {
            String raw = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Scene.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            DiagnosticsCenter.log("PROJECT_ERR", "스토리보드 로드 실패: " + e.getMessage());
        }
        return out;
    }

    public void saveScenes(File project, List<Scene> scenes) throws IOException {
        JSONArray arr = new JSONArray();
        for (Scene s : scenes) arr.put(s.toJson());
        File manifest = new File(project, "storyboard.json");
        try {
            Files.write(manifest.toPath(), arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("스토리보드 JSON 직렬화 실패", e);
        }
    }

    public void moveScene(File project, int fromIndex, int toIndex) throws IOException {
        List<Scene> scenes = loadScenes(project);
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= scenes.size() || toIndex >= scenes.size()) return;
        Collections.swap(scenes, fromIndex, toIndex);
        saveScenes(project, scenes);
    }

    public void duplicateScene(File project, int index) throws IOException {
        List<Scene> scenes = loadScenes(project);
        if (index < 0 || index >= scenes.size()) return;
        Scene src = scenes.get(index);
        Scene copy = Scene.fromJson(src.toJson());
        Scene cloned = new Scene(UUID.randomUUID().toString(), src.title + " (복사)", copy.prompt);
        cloned.negative = copy.negative;
        cloned.characterName = copy.characterName;
        cloned.modelId = copy.modelId;
        cloned.imagePath = copy.imagePath;
        cloned.dialogue = copy.dialogue;
        cloned.sfx = copy.sfx;
        cloned.bubbleX = copy.bubbleX;
        cloned.bubbleY = copy.bubbleY;
        cloned.textSizeSp = copy.textSizeSp;
        cloned.spacingPx = copy.spacingPx;
        scenes.add(index + 1, cloned);
        saveScenes(project, scenes);
    }

    /** Renders a real vertical Webtoon strip PNG with OOM-safe height limit. */
    public File exportWebtoonStrip(File project, int targetWidth) throws IOException {
        List<Scene> scenes = loadScenes(project);
        if (scenes.isEmpty()) throw new IOException("내보낼 장면이 없습니다");
        int width = Math.max(400, Math.min(1440, targetWidth));
        List<Bitmap> panels = new ArrayList<>();
        long totalHeight = 0;
        try {
            for (Scene s : scenes) {
                Bitmap src = (s.imagePath != null && new File(s.imagePath).isFile())
                        ? GalleryRepository.decodeDownsampled(new File(s.imagePath), width, width * 2) : null;
                int panelH = src != null ? Math.max(200, (src.getHeight() * width) / Math.max(1, src.getWidth())) : (width * 3) / 4;
                if (totalHeight + panelH + Math.max(0, s.spacingPx) > MAX_STRIP_HEIGHT_PX) {
                    if (src != null) src.recycle();
                    throw new IOException("웹툰 세로 길이가 최대 한도(" + MAX_STRIP_HEIGHT_PX + "px)를 초과합니다. 장면 수를 나누어 내보내세요.");
                }
                Bitmap panel = Bitmap.createBitmap(width, panelH, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(panel);
                c.drawColor(Color.WHITE);
                if (src != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(src, width, panelH, true);
                    c.drawBitmap(scaled, 0, 0, null);
                    if (scaled != src) scaled.recycle();
                    src.recycle();
                } else {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(0xFF222233);
                    c.drawRect(0, 0, width, panelH, p);
                    p.setColor(0xFFAAAABD);
                    p.setTextSize(28f);
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText(s.title, width / 2f, panelH / 2f, p);
                }
                drawBubbleAndSfx(c, s, width, panelH);
                panels.add(panel);
                totalHeight += panelH + Math.max(0, s.spacingPx);
            }

            Bitmap strip = Bitmap.createBitmap(width, (int) Math.max(1L, totalHeight), Bitmap.Config.ARGB_8888);
            Canvas outCanvas = new Canvas(strip);
            outCanvas.drawColor(Color.WHITE);
            int y = 0;
            for (int i = 0; i < panels.size(); i++) {
                Bitmap p = panels.get(i);
                outCanvas.drawBitmap(p, 0, y, null);
                y += p.getHeight() + Math.max(0, scenes.get(i).spacingPx);
            }

            File outFile = new File(project, "webtoon_export_" + System.currentTimeMillis() + ".png");
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                if (!strip.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("PNG 인코딩 실패");
                }
            } finally {
                strip.recycle();
            }
            return outFile;
        } finally {
            for (Bitmap p : panels) {
                if (p != null && !p.isRecycled()) p.recycle();
            }
        }
    }

    private static void drawBubbleAndSfx(Canvas c, Scene s, int w, int h) {
        if (s.dialogue != null && !s.dialogue.trim().isEmpty()) {
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.BLACK);
            textPaint.setTextSize(Math.max(20f, s.textSizeSp * 2f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            float cx = Math.max(0.15f, Math.min(0.85f, s.bubbleX)) * w;
            float cy = Math.max(0.12f, Math.min(0.88f, s.bubbleY)) * h;
            float tw = textPaint.measureText(s.dialogue.trim());
            float padX = 34f, padY = 24f;
            RectF oval = new RectF(cx - tw / 2f - padX, cy - textPaint.getTextSize() - padY,
                    cx + tw / 2f + padX, cy + padY);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.WHITE);
            c.drawRoundRect(oval, 36f, 36f, fill);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(4f);
            stroke.setColor(Color.BLACK);
            c.drawRoundRect(oval, 36f, 36f, stroke);
            c.drawText(s.dialogue.trim(), cx, cy, textPaint);
        }
        if (s.sfx != null && !s.sfx.trim().isEmpty()) {
            Paint sfxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sfxPaint.setColor(0xFFFF4D6D);
            sfxPaint.setTextSize(42f);
            sfxPaint.setFakeBoldText(true);
            c.drawText(s.sfx.trim(), w * 0.08f, h * 0.88f, sfxPaint);
        }
    }
}
