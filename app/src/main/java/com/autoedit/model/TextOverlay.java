package com.autoedit.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ONE text track on the project (spec §47). Editing state only; drawn by the
 * shared {@code FrameComposer} so preview and export render identical text.
 */
public class TextOverlay {
    public String text = "AUTO EDIT";
    public float startSec = 0, endSec = 5, x = .5f, y = .5f, size = 56f, opacity = 1f;
    public int color = 0xffffffff, strokeColor = 0xff000000;
    public boolean bold = true, italic = false;
    public String animation = "Fade";

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("text", text);
        o.put("startSec", startSec); o.put("endSec", endSec);
        o.put("x", x); o.put("y", y); o.put("size", size); o.put("opacity", opacity);
        o.put("color", color); o.put("strokeColor", strokeColor);
        o.put("bold", bold); o.put("italic", italic); o.put("animation", animation);
        return o;
    }

    public static TextOverlay fromJson(JSONObject o) {
        TextOverlay t = new TextOverlay();
        if (o == null) return t;
        t.text = o.optString("text", t.text);
        t.startSec = (float) o.optDouble("startSec", t.startSec);
        t.endSec = (float) o.optDouble("endSec", t.endSec);
        t.x = (float) o.optDouble("x", t.x);
        t.y = (float) o.optDouble("y", t.y);
        t.size = (float) o.optDouble("size", t.size);
        t.opacity = (float) o.optDouble("opacity", t.opacity);
        t.color = o.optInt("color", t.color);
        t.strokeColor = o.optInt("strokeColor", t.strokeColor);
        t.bold = o.optBoolean("bold", t.bold);
        t.italic = o.optBoolean("italic", t.italic);
        t.animation = o.optString("animation", t.animation);
        return t;
    }
}
