package com.autoedit.project;

import android.content.*;import com.autoedit.model.*;import com.autoedit.engine.FormulaEngine;import org.json.*;

public class ProjectStore {
    private static final String PREF="auto_edit_projects", KEY="current";
    private final Context ctx; private final FormulaEngine formulas=new FormulaEngine();
    public ProjectStore(Context c){ ctx=c.getApplicationContext(); }

    public void save(EditProject p){
        try{
            JSONObject o=new JSONObject();
            o.put("name",p.name); o.put("fps",p.fps); o.put("width",p.width); o.put("height",p.height);
            o.put("aspect",p.aspectRatio.name()); o.put("exportPreset",p.exportPreset.name()); o.put("fitMode",p.fitMode.name()); o.put("audio",p.audioUri);
            JSONArray arr=new JSONArray(); for(TimelineClip c:p.clips) arr.put(c.toJson()); o.put("clips",arr);
            ctx.getSharedPreferences(PREF,0).edit().putString(KEY,o.toString()).apply();
        }catch(Exception ignored){}
    }

    public EditProject load(){
        String s=ctx.getSharedPreferences(PREF,0).getString(KEY,null); EditProject p=new EditProject(); if(s==null) return p;
        try{
            JSONObject o=new JSONObject(s); p.name=o.optString("name",p.name); p.fps=o.optInt("fps",30); p.width=o.optInt("width",1920); p.height=o.optInt("height",1080);
            p.aspectRatio=AspectRatio.valueOf(o.optString("aspect",AspectRatio.R9_16.name()));
            p.exportPreset=ExportPreset.valueOf(o.optString("exportPreset",ExportPreset.PORTRAIT_9_16.name()));
            p.fitMode=FitMode.valueOf(o.optString("fitMode",FitMode.FILL.name()));
            p.audioUri=o.optString("audio",null); if("null".equals(p.audioUri) || "".equals(p.audioUri)) p.audioUri=null;
            JSONArray arr=o.optJSONArray("clips");
            if(arr!=null) for(int i=0;i<arr.length();i++){
                JSONObject c=arr.getJSONObject(i); TimelineClip clip=new TimelineClip(c.getString("uri"),c.optInt("index",i+1),formulas.byId(c.optString("formula","17")));
                if(c.has("durationMs")) clip.setDurationMs(c.optLong("durationMs",5000)); else clip.setDurationSeconds((float)c.optDouble("duration",5)); clip.transition=TransitionType.valueOf(c.optString("transition",TransitionType.CROSS_DISSOLVE.name()));
                clip.transitionDurationSec=(float)c.optDouble("transitionDuration",.5); clip.effect=EffectType.valueOf(c.optString("effect",EffectType.NONE.name())); clip.effectIntensity=(float)c.optDouble("effectIntensity",.6); p.clips.add(clip);
            }
        }catch(Exception ignored){}
        p.renumber(); return p;
    }
}
