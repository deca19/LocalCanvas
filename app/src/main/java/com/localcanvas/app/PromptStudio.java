package com.localcanvas.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Offline Prompt Studio: categorized tag DB, Korean->English tag translation,
 * duplicate removal, conflict detection, weight adjustment, presets, and history.
 * Zero paid APIs required.
 */
public final class PromptStudio {
    private final SharedPreferences prefs;

    public static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String, String> KO_TO_EN = new LinkedHashMap<>();
    private static final String[][] CONFLICTS = {
        {"day", "night"}, {"solo", "multiple girls"}, {"solo", "2girls"},
        {"monochrome", "colorful"}, {"outdoors", "indoors"},
        {"short hair", "long hair"}, {"smile", "crying"}
    };

    static {
        CATEGORIES.put("품질", new String[]{"masterpiece", "best quality", "highly detailed", "ultra-detailed", "sharp focus", "highres"});
        CATEGORIES.put("인물", new String[]{"1girl", "1boy", "solo", "2girls", "portrait", "upper body", "full body", "cowboy shot"});
        CATEGORIES.put("의상", new String[]{"school uniform", "casual wear", "hoodie", "dress", "jacket", "armor", "cyberpunk outfit", "traditional clothes"});
        CATEGORIES.put("표정", new String[]{"smile", "gentle smile", "serious", "blush", "looking at viewer", "closed eyes", "surprised", "crying"});
        CATEGORIES.put("자세", new String[]{"standing", "sitting", "walking", "running", "dynamic pose", "arms behind back", "leaning forward"});
        CATEGORIES.put("배경", new String[]{"outdoors", "indoors", "cityscape", "night sky", "forest", "cafe", "classroom", "cherry blossoms", "day", "night"});
        CATEGORIES.put("조명", new String[]{"cinematic lighting", "soft lighting", "rim light", "volumetric lighting", "sunset", "neon lights", "backlighting"});
        CATEGORIES.put("구도/화풍", new String[]{"anime style", "webtoon style", "watercolor", "oil painting", "cel shading", "depth of field", "wide shot", "close-up", "colorful", "monochrome"});

        CATEGORIES.put("자연", new String[]{"mountain", "river", "lake", "ocean", "waterfall", "meadow", "desert", "snow", "rain", "fog", "clouds", "stars", "moon", "sunrise", "autumn leaves", "pine trees", "wildflowers", "island", "valley", "cliff"});
        CATEGORIES.put("건축", new String[]{"library", "museum", "train station", "garden", "castle", "bridge", "market", "street", "rooftop", "greenhouse", "observatory", "workshop", "laboratory", "wooden house", "stone wall", "window", "courtyard"});
        CATEGORIES.put("색상", new String[]{"pastel colors", "warm colors", "cool colors", "muted colors", "vibrant colors", "blue palette", "green palette", "golden tones", "earth tones", "high contrast", "low contrast"});
        CATEGORIES.put("머리", new String[]{"black hair", "brown hair", "silver hair", "white hair", "blonde hair", "blue hair", "red hair", "curly hair", "straight hair", "wavy hair", "ponytail", "braid", "bangs", "side part", "long hair", "short hair"});
        CATEGORIES.put("카메라", new String[]{"eye level", "low angle", "high angle", "bird's-eye view", "side view", "back view", "three-quarter view", "symmetrical composition", "rule of thirds", "panorama", "macro", "foreground", "background", "bokeh"});
        CATEGORIES.put("소품", new String[]{"book", "umbrella", "backpack", "camera", "headphones", "notebook", "flower bouquet", "lantern", "teacup", "bicycle", "scarf", "glasses", "hat", "clock", "robot", "telescope"});
        CATEGORIES.put("재질", new String[]{"wood", "glass", "metal", "ceramic", "paper texture", "fabric", "stone", "brushed metal", "matte finish", "glossy finish", "soft texture", "grainy texture"});
        KO_TO_EN.put("소녀", "1girl");
        KO_TO_EN.put("소년", "1boy");
        KO_TO_EN.put("단독", "solo");
        KO_TO_EN.put("미소", "smile");
        KO_TO_EN.put("웃음", "gentle smile");
        KO_TO_EN.put("긴머리", "long hair");
        KO_TO_EN.put("단발", "short hair");
        KO_TO_EN.put("교복", "school uniform");
        KO_TO_EN.put("드레스", "dress");
        KO_TO_EN.put("후드", "hoodie");
        KO_TO_EN.put("야외", "outdoors");
        KO_TO_EN.put("실내", "indoors");
        KO_TO_EN.put("도시", "cityscape");
        KO_TO_EN.put("밤하늘", "night sky");
        KO_TO_EN.put("밤", "night");
        KO_TO_EN.put("낮", "day");
        KO_TO_EN.put("노을", "sunset");
        KO_TO_EN.put("벚꽃", "cherry blossoms");
        KO_TO_EN.put("카페", "cafe");
        KO_TO_EN.put("교실", "classroom");
        KO_TO_EN.put("애니", "anime style");
        KO_TO_EN.put("웹툰", "webtoon style");
        KO_TO_EN.put("수채화", "watercolor");
        KO_TO_EN.put("고화질", "masterpiece, best quality");
        KO_TO_EN.put("역광", "backlighting");
        KO_TO_EN.put("전신", "full body");
        KO_TO_EN.put("상반신", "upper body");
        KO_TO_EN.put("클로즈업", "close-up");
    }

    public PromptStudio(Context c) {
        prefs = c.getSharedPreferences("prompt_studio", Context.MODE_PRIVATE);
    }

    public List<String> suggestions(String prefix) {
        List<String> out = new ArrayList<>();
        String q = prefix == null ? "" : prefix.trim().toLowerCase(Locale.US);
        for (Map.Entry<String, String> ko : KO_TO_EN.entrySet()) {
            if (q.isEmpty() || ko.getKey().contains(q) || ko.getValue().contains(q)) {
                if (!out.contains(ko.getValue())) out.add(ko.getValue());
            }
        }
        for (String[] arr : CATEGORIES.values()) {
            for (String t : arr) {
                if ((q.isEmpty() || t.contains(q)) && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    public String translateKoreanInput(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String[] tokens = input.split("[,\\n]+");
        List<String> out = new ArrayList<>();
        for (String raw : tokens) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String mapped = KO_TO_EN.get(t);
            if (mapped != null) {
                out.add(mapped);
            } else {
                String replaced = t;
                for (Map.Entry<String, String> e : KO_TO_EN.entrySet()) {
                    if (replaced.contains(e.getKey())) {
                        replaced = replaced.replace(e.getKey(), e.getValue());
                    }
                }
                out.add(replaced);
            }
        }
        return deduplicate(String.join(", ", out));
    }

    public String deduplicate(String prompt) {
        if (prompt == null) return "";
        Set<String> seen = new LinkedHashSet<>();
        for (String part : prompt.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        return String.join(", ", seen);
    }

    public List<String> detectConflicts(String prompt) {
        List<String> found = new ArrayList<>();
        if (prompt == null) return found;
        String lower = prompt.toLowerCase(Locale.US);
        for (String[] pair : CONFLICTS) {
            if (lower.contains(pair[0]) && lower.contains(pair[1])) {
                found.add(pair[0] + " ↔ " + pair[1]);
            }
        }
        return found;
    }

    public String applyWeight(String tag, float weight) {
        String clean = tag == null ? "" : tag.trim();
        if (clean.isEmpty()) return "";
        if (Math.abs(weight - 1.0f) < 0.05f) return clean;
        return String.format(Locale.US, "(%s:%.2f)", clean, weight);
    }

    public void save(String name, String prompt, String negative) {
        if (name == null || name.trim().isEmpty()) return;
        prefs.edit().putString("p." + name.trim(), prompt).putString("n." + name.trim(), negative).apply();
    }

    public String prompt(String name) { return prefs.getString("p." + name, ""); }
    public String negative(String name) { return prefs.getString("n." + name, ""); }

    public Set<String> names() {
        Set<String> s = new TreeSet<>();
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith("p.")) s.add(k.substring(2));
        }
        return s;
    }

    public void pushHistory(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(prefs.getString("_history", "[]"));
            JSONArray next = new JSONArray();
            next.put(prompt.trim());
            for (int i = 0; i < arr.length() && next.length() < 25; i++) {
                String v = arr.optString(i);
                if (!v.equals(prompt.trim())) next.put(v);
            }
            prefs.edit().putString("_history", next.toString()).apply();
        } catch (Exception ignored) {}
    }

    public List<String> history() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("_history", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    public String randomTags(int count) {
        List<String> all = new ArrayList<>();
        for (String[] arr : CATEGORIES.values()) all.addAll(Arrays.asList(arr));
        Collections.shuffle(all);
        return String.join(", ", all.subList(0, Math.max(0, Math.min(count, all.size()))));
    }
}
