package com.autoedit.update;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote update configuration (version.json) published by GitHub Releases.
 *
 *  - latestVersionCode / latestVersionName : newest published build
 *  - minimumSupportedVersionCode           : versions BELOW this are blocked
 *    by the mandatory update screen
 *  - downloadUrl                           : stable APK asset URL (comes from
 *    the JSON, never hardcoded per-version)
 *  - releaseNotes                          : "What's new" bullets
 */
public class VersionConfig {
    public int latestVersionCode;
    public String latestVersionName;
    public int minimumSupportedVersionCode;
    public String downloadUrl;
    public List<String> releaseNotes = new ArrayList<>();

    public static VersionConfig parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        VersionConfig c = new VersionConfig();
        c.latestVersionCode = o.getInt("latestVersionCode");
        c.latestVersionName = o.optString("latestVersionName", String.valueOf(c.latestVersionCode));
        c.minimumSupportedVersionCode = o.optInt("minimumSupportedVersionCode", c.latestVersionCode);
        c.downloadUrl = o.getString("downloadUrl");
        org.json.JSONArray notes = o.optJSONArray("releaseNotes");
        if (notes != null) for (int i = 0; i < notes.length(); i++) c.releaseNotes.add(notes.getString(i));
        return c;
    }
}
