package com.autoedit.model;

/**
 * One saved prompt entry for the Prompt Library.
 *
 * Infrastructure-ready schema (Part 13): name, description, optional preview
 * image, optional associated formula id, optional action label. The library
 * ships EMPTY — prompts are added later without touching the Home screen
 * layout again.
 */
public class PromptItem {
    public String id;
    public String name;
    public String description;
    public String previewUri;   // optional
    public String formulaId;    // optional associated formula (built-in or custom)
    public String action;       // optional action label (future)

    public PromptItem() {}
}
