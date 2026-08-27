package com.autoedit.engine;

import com.autoedit.model.*;
import java.util.*;

public class FormulaEngine {
    private final ArrayList<Formula> formulas = new ArrayList<>();
    public FormulaEngine(){ build(); }
    private void build(){
        add("00","None","Static",0,0,1.0f,0,0,1.0f);
        add("01","Top → Bottom","Vertical",0,-.10f,1.0f,0,.10f,1.08f);
        add("02","Right → Left","Horizontal",.12f,0,1.05f,-.12f,0,1.05f);
        add("03","Top → Bottom Zoom","Vertical Zoom",0,-.12f,1.0f,0,.12f,1.12f);
        add("04","Left → Right","Horizontal",-.12f,0,1.05f,.12f,0,1.05f);
        add("05","Bottom → Top","Vertical",0,.10f,1.0f,0,-.10f,1.08f);
        add("06","Zoom In","Zoom",0,0,1.0f,0,0,1.14f);
        add("07","Zoom Out","Zoom",0,0,1.14f,0,0,1.0f);
        add("08","Center → Left","Cinematic",0,0,1.08f,-.10f,0,1.08f);
        add("09","Center → Right","Cinematic",0,0,1.08f,.10f,0,1.08f);
        add("10","Diagonal TL → BR","Diagonal",-.08f,-.08f,1.08f,.08f,.08f,1.10f);
        add("11","Diagonal TR → BL","Diagonal",.08f,-.08f,1.08f,-.08f,.08f,1.10f);
        add("12","Diagonal BL → TR","Diagonal",-.08f,.08f,1.08f,.08f,-.08f,1.10f);
        add("13","Diagonal BR → TL","Diagonal",.08f,.08f,1.08f,-.08f,-.08f,1.10f);
        add("14","Slow Push In","Cinematic",0,0,1.02f,0,0,1.10f);
        add("15","Slow Pull Out","Cinematic",0,0,1.10f,0,0,1.02f);
        add("16","Pan + Zoom","Combo",-.10f,-.03f,1.03f,.10f,.03f,1.12f);
        add("17","Ken Burns","Classic",-.06f,.02f,1.04f,.06f,-.02f,1.12f);
        add("18","Soft Floating","Subtle",-.025f,-.015f,1.04f,.025f,.015f,1.06f);
        add("19","Focus Zoom","Focus",0,0,1.0f,0,0,1.18f);
        add("20","Random Cinematic","Random",0,0,1f,0,0,1.08f);
    }
    private void add(String id,String name,String dir,float sx,float sy,float ss,float ex,float ey,float es){ formulas.add(new Formula(id,name,dir,new KeyframeState(sx,sy,ss,0,1),new KeyframeState(ex,ey,es,0,1))); }
    public List<Formula> all(){ return Collections.unmodifiableList(formulas); }
    public Formula byId(String id){ for(Formula f:formulas) if(f.id.equals(id)) return cloneFormula(f); return cloneFormula(formulas.get(16)); }
    public Formula defaultFormula(){ return byId("17"); }
    public Formula randomFor(int index){ if(index<0) index=0; return byId(String.format(Locale.US, "%02d", (index*7 % 19)+1)); }
    public KeyframeState stateAt(Formula formula, float clipProgress){ float eased = formula.easing.apply(clipProgress); return KeyframeState.lerp(formula.start, formula.end, eased); }
    private Formula cloneFormula(Formula f){ Formula n = new Formula(f.id,f.name,f.direction,f.start.copy(),f.end.copy()); n.speed=f.speed; n.zoomAmount=f.zoomAmount; n.smoothness=f.smoothness; n.easing=f.easing; return n; }
}
