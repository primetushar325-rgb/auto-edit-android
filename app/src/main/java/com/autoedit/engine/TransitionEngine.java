package com.autoedit.engine;

import com.autoedit.model.TransitionType;

public class TransitionEngine {
    public float outgoingAlpha(TransitionType t, float progress){ if(t==TransitionType.NONE) return 1f; return 1f-progress; }
    public float incomingAlpha(TransitionType t, float progress){ if(t==TransitionType.NONE) return 1f; return progress; }
}
