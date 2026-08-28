package com.autoedit.ui;

import android.content.Context;
import com.autoedit.model.Formula;

/**
 * Live preview card for a single MOTION. A motion is just a non-pattern
 * Formula, so this reuses {@link FormulaPreviewView}: the card loops the ONE
 * motion start->end (no pattern dots) using the same engine as preview/export.
 */
public class MotionPreviewView extends FormulaPreviewView {
    public MotionPreviewView(Context c) { super(c); }

    public void setMotion(Formula motion) { setFormula(motion); }
}
