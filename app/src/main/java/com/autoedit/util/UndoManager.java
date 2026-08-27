package com.autoedit.util;

import java.util.*;

public class UndoManager<T> {
    private final ArrayDeque<T> undo=new ArrayDeque<>(), redo=new ArrayDeque<>(); private final int max=50;
    public void push(T state){ undo.push(state); redo.clear(); while(undo.size()>max) undo.removeLast(); }
    public T undo(T current){ if(undo.isEmpty()) return current; redo.push(current); return undo.pop(); }
    public T redo(T current){ if(redo.isEmpty()) return current; undo.push(current); return redo.pop(); }
}
