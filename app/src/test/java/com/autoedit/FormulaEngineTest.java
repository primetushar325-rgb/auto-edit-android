package com.autoedit;

import com.autoedit.engine.FormulaEngine;import com.autoedit.model.*;import org.junit.Test;import static org.junit.Assert.*;

public class FormulaEngineTest {
 @Test public void hasTwentyFormulas(){ assertTrue(new FormulaEngine().all().size()>=20); }
 @Test public void topBottomInterpolates(){ FormulaEngine e=new FormulaEngine(); Formula f=e.byId("01"); KeyframeState a=e.stateAt(f,0); KeyframeState b=e.stateAt(f,1); assertTrue(a.y < b.y); }
 @Test public void projectDurationForThousandImages(){ EditProject p=new EditProject(); FormulaEngine e=new FormulaEngine(); for(int i=0;i<1000;i++) p.clips.add(new TimelineClip("content://image/"+i,i+1,e.defaultFormula())); assertEquals(5000f,p.totalDurationSec(),0.01f); assertEquals(150000,p.totalFrames()); }
 @Test public void criticalMixedDurationProject(){ EditProject p=new EditProject(); FormulaEngine e=new FormulaEngine(); for(int i=0;i<6;i++) p.clips.add(new TimelineClip("content://image/"+i,i+1,e.defaultFormula())); for(int i=0;i<6;i++) p.clips.get(i).setDurationMs((i+3)*1000L); assertEquals(33f,p.totalDurationSec(),0.01f); p.clips.get(0).setDurationMs(8000); assertEquals(38f,p.totalDurationSec(),0.01f); }
 @Test public void applyFiveSecondsToAllBatchMath(){ EditProject p=new EditProject(); FormulaEngine e=new FormulaEngine(); for(int i=0;i<6;i++){ TimelineClip c=new TimelineClip("content://image/"+i,i+1,e.defaultFormula()); c.setDurationMs((i+3)*1000L); p.clips.add(c); } for(TimelineClip c:p.clips) c.setDurationMs(5000L); assertEquals(30f,p.totalDurationSec(),0.01f); }
 @Test public void noneFormulaIsStatic(){ FormulaEngine e=new FormulaEngine(); Formula f=e.byId("00"); KeyframeState a=e.stateAt(f,0); KeyframeState b=e.stateAt(f,1); assertEquals(a.scale,b.scale,0.001f); assertEquals(a.x,b.x,0.001f); assertEquals(a.y,b.y,0.001f); }
}
