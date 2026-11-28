package com.localcanvas.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stores rich character profiles (appearance, LoRA binding, reference image, seed)
 * with backward compatibility for V14 pipe-delimited records and JSON import/export.
 */
public final class CharacterStudio {
    public static final class CharacterProfile {
        public final String name, hair, hairColor, eyeColor, clothing, expression, pose, background;
        public final String description, refImagePath, linkedLoraId;
        public final float loraWeight;
        public final long fixedSeed;

        public CharacterProfile(String n, String h, String hc, String e, String c, String x, String p, String b) {
            this(n, "", h, hc, e, c, x, p, b, "", "", 0.8f, -1L);
        }

        public CharacterProfile(String name, String description, String hair, String hairColor,
                                String eyeColor, String clothing, String expression, String pose,
                                String background, String refImagePath, String linkedLoraId,
                                float loraWeight, long fixedSeed) {
            this.name = name;
            this.description = description != null ? description : "";
            this.hair = hair != null ? hair : "";
            this.hairColor = hairColor != null ? hairColor : "";
            this.eyeColor = eyeColor != null ? eyeColor : "";
            this.clothing = clothing != null ? clothing : "";
            this.expression = expression != null ? expression : "";
            this.pose = pose != null ? pose : "";
            this.background = background != null ? background : "";
            this.refImagePath = refImagePath != null ? refImagePath : "";
            this.linkedLoraId = linkedLoraId != null ? linkedLoraId : "";
            this.loraWeight = loraWeight;
            this.fixedSeed = fixedSeed;
        }

        public String prompt() {
            List<String> parts = new ArrayList<>();
            if (!name.isEmpty()) parts.add(name);
            if (!description.isEmpty()) parts.add(description);
            if (!hair.isEmpty()) parts.add(hair);
            if (!hairColor.isEmpty()) parts.add(hairColor + " hair");
            if (!eyeColor.isEmpty()) parts.add(eyeColor + " eyes");
            if (!clothing.isEmpty()) parts.add(clothing);
            if (!expression.isEmpty()) parts.add(expression);
            if (!pose.isEmpty()) parts.add(pose);
            if (!background.isEmpty()) parts.add(background);
            return String.join(", ", parts);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("description", description);
                o.put("hair", hair);
                o.put("hairColor", hairColor);
                o.put("eyeColor", eyeColor);
                o.put("clothing", clothing);
                o.put("expression", expression);
                o.put("pose", pose);
                o.put("background", background);
                o.put("refImagePath", refImagePath);
                o.put("linkedLoraId", linkedLoraId);
                o.put("loraWeight", loraWeight);
                o.put("fixedSeed", fixedSeed);
            } catch (Exception ignored) {}
            return o;
        }
    }

    private final SharedPreferences prefs;

    public CharacterStudio(Context c) {
        prefs = c.getSharedPreferences("characters", Context.MODE_PRIVATE);
    }

    public void save(CharacterProfile x) {
        if (x == null || x.name == null || x.name.trim().isEmpty()) return;
        prefs.edit().putString(x.name.trim(), x.toJson().toString()).apply();
    }

    public CharacterProfile get(String n) {
        String z = prefs.getString(n, null);
        if (z == null) return null;
        if (z.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(z);
                return new CharacterProfile(
                        n,
                        o.optString("description", ""),
                        o.optString("hair", ""),
                        o.optString("hairColor", ""),
                        o.optString("eyeColor", ""),
                        o.optString("clothing", ""),
                        o.optString("expression", ""),
                        o.optString("pose", ""),
                        o.optString("background", ""),
                        o.optString("refImagePath", ""),
                        o.optString("linkedLoraId", ""),
                        (float) o.optDouble("loraWeight", 0.8),
                        o.optLong("fixedSeed", -1L)
                );
            } catch (Exception ignored) {}
        }
        String[] a = z.split("\\|", -1);
        if (a.length != 7) return null;
        return new CharacterProfile(n, a[0], a[1], a[2], a[3], a[4], a[5], a[6]);
    }

    public Set<String> names() { return new TreeSet<>(prefs.getAll().keySet()); }
    public void delete(String n) { prefs.edit().remove(n).apply(); }

    public void duplicate(String sourceName, String newName) {
        CharacterProfile src = get(sourceName);
        if (src == null || newName == null || newName.trim().isEmpty()) return;
        save(new CharacterProfile(newName.trim(), src.description, src.hair, src.hairColor,
                src.eyeColor, src.clothing, src.expression, src.pose, src.background,
                src.refImagePath, src.linkedLoraId, src.loraWeight, src.fixedSeed));
    }

    public String combinePrompts(List<String> characterNames) {
        List<String> out = new ArrayList<>();
        for (String name : characterNames) {
            CharacterProfile p = get(name);
            if (p != null) out.add("(" + p.prompt() + ")");
        }
        return String.join(", ", out);
    }

    public String exportAllJson() {
        JSONObject root = new JSONObject();
        for (String n : names()) {
            CharacterProfile p = get(n);
            if (p != null) {
                try { root.put(n, p.toJson()); } catch (Exception ignored) {}
            }
        }
        return root.toString();
    }

    public int importAllJson(String json) {
        int count = 0;
        try {
            JSONObject root = new JSONObject(json);
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject o = root.getJSONObject(k);
                save(new CharacterProfile(k, o.optString("description", ""), o.optString("hair", ""),
                        o.optString("hairColor", ""), o.optString("eyeColor", ""), o.optString("clothing", ""),
                        o.optString("expression", ""), o.optString("pose", ""), o.optString("background", ""),
                        o.optString("refImagePath", ""), o.optString("linkedLoraId", ""),
                        (float) o.optDouble("loraWeight", 0.8), o.optLong("fixedSeed", -1L)));
                count++;
            }
        } catch (Exception ignored) {}
        return count;
    }
}
