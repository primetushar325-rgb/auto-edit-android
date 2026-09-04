package com.autoedit.model;

/** Transition library categories (tabs). Order controls tab order. */
public enum TransitionCategory {
    RECENT("Recently Used"),
    FAVORITES("Favorites"),
    TRENDING("Trending"),
    BASIC("Basic"),
    CLASSIC("Classic"),
    CAMERA("Camera"),
    THREE_D("3D"),
    BLUR("Blur"),
    GLITCH("Glitch"),
    FLASH("Flash"),
    MASK("Mask"),
    SHAPE("Shape"),
    SLIDE("Slide"),
    CINEMATIC("Cinematic"),
    LIQUID("Liquid"),
    DYNAMIC("Dynamic"),
    GALLERY("Gallery"),
    SOCIAL("Social"),
    PHOTO("Photo");

    public final String label;
    TransitionCategory(String label) { this.label = label; }
}
