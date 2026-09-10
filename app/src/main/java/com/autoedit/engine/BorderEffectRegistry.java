package com.autoedit.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Border / Frame preset library — ≥20 real procedural effects.
 * Offline-first, no assets, lightweight.
 *
 * Categories mirror spec: All / Neon / Glow / Electric / Cinematic / Color / Light / Special
 * Internal categories: NEON (8), DUAL (5), GLOW (6), ELECTRIC (5), CINEMATIC (5), FLOW (4), SPECIAL (6)
 * Total 39 presets — each renders via BorderRenderer (Canvas/Gradient/blur/animated path).
 *
 * "Neon Flow" blue+green moving glow is included as neon_flow.
 * Every preset is real — no Coming Soon — procedural Canvas/vector/gradient/animated.
 */
public class BorderEffectRegistry {

    public static class Preset {
        public final String id;
        public final String name;
        public final String category; // Neon, Dual, Glow, Electric, Cinematic, Flow, Special
        public final String displayCategory; // for UI filter buckets
        public final int primaryColor;
        public final int secondaryColor;
        public final float defaultThickness; // dp
        public final float defaultGlow;
        public final float defaultSpeed;
        public final String desc;

        Preset(String id, String name, String cat, String displayCat, int prim, int sec, float thick, float glow, float speed, String desc) {
            this.id=id; this.name=name; this.category=cat; this.displayCategory=displayCat;
            this.primaryColor=prim; this.secondaryColor=sec;
            this.defaultThickness=thick; this.defaultGlow=glow; this.defaultSpeed=speed; this.desc=desc;
        }
    }

    private static final List<Preset> ALL;
    static {
        List<Preset> l = new ArrayList<>();
        // ── NEON 8 ──
        l.add(new Preset("neon_blue",   "Neon Blue",   "Neon", "Neon", 0xFF00D4FF, 0xFF00A8FF, 8f, 10f, 0.65f, "Bright cyan scanline glow"));
        l.add(new Preset("neon_pink",   "Neon Pink",   "Neon", "Neon", 0xFFFF2E97, 0xFFFF6EC7, 8f, 10f, 0.65f, "Magenta neon tube"));
        l.add(new Preset("neon_green",  "Neon Green",  "Neon", "Neon", 0xFF00FF88, 0xFF00CC66, 8f, 10f, 0.65f, "Acid green glow"));
        l.add(new Preset("neon_purple", "Neon Purple", "Neon", "Neon", 0xFF9D00FF, 0xFFD946EF, 8f, 10f, 0.65f, "Violet electric"));
        l.add(new Preset("neon_cyan",   "Neon Cyan",   "Neon", "Neon", 0xFF00FFFF, 0xFF22D3EE, 8f, 10f, 0.65f, "Ice cyan"));
        l.add(new Preset("neon_orange", "Neon Orange", "Neon", "Neon", 0xFFFF6B00, 0xFFFFA500, 8f, 10f, 0.65f, "Amber pulse"));
        l.add(new Preset("neon_red",    "Neon Red",    "Neon", "Neon", 0xFFFF0033, 0xFFFF3366, 8f, 10f, 0.65f, "Laser red"));
        l.add(new Preset("neon_flow",   "Neon Flow",   "Neon", "Neon", 0xFF00D4FF, 0xFF00FF88, 8f, 12f, 0.75f, "Blue+green moving glow — signature"));
        // ── DUAL 5 ──
        l.add(new Preset("dual_sunset",    "Sunset Dual",   "Dual","Color", 0xFFFF6B35, 0xFF9D00FF, 7f, 8f, 0.60f, "Orange ↔ purple sweep"));
        l.add(new Preset("dual_ocean",     "Ocean Dual",    "Dual","Color", 0xFF0077BE, 0xFF00E5CC, 7f, 8f, 0.60f, "Deep blue ↔ teal"));
        l.add(new Preset("dual_fire_ice",  "Fire & Ice",    "Dual","Color", 0xFFFF3B30, 0xFF007AFF, 7f, 8f, 0.60f, "Red ↔ blue gradient"));
        l.add(new Preset("dual_lime_purple","Lime Purple", "Dual","Color", 0xFFAAFF00, 0xFF9D00FF, 7f, 8f, 0.60f, "Lime ↔ violet"));
        l.add(new Preset("dual_gold_cyan", "Gold Cyan",     "Dual","Color", 0xFFFFD700, 0xFF00FFFF, 7f, 8f, 0.60f, "Gold ↔ cyan metallic"));
        // ── GLOW 6 ──
        l.add(new Preset("glow_pulse_blue",  "Pulse Blue",    "Glow","Glow", 0xFF3B82F6, 0xFF60A5FA, 9f, 14f, 0.55f, "Breathing blue halo"));
        l.add(new Preset("glow_pulse_pink",  "Pulse Pink",    "Glow","Glow", 0xFFEC4899, 0xFFF472B6, 9f, 14f, 0.55f, "Soft pink pulse"));
        l.add(new Preset("glow_breath_gold", "Golden Breath", "Glow","Light",0xFFFFC84D, 0xFFFFE08A, 10f, 16f, 0.45f, "Warm golden glow"));
        l.add(new Preset("glow_shimmer_white","Shimmer",      "Glow","Light",0xFFFFFFFF, 0xFFE0F2FF, 8f, 12f, 0.70f, "White shimmer sweep"));
        l.add(new Preset("glow_soft_amber",  "Soft Amber",    "Glow","Glow", 0xFFFFB84D, 0xFFFFE4B5, 8f, 13f, 0.50f, "Amber soft light"));
        l.add(new Preset("glow_rainbow_pulse","Rainbow Pulse","Glow","Color",0xFFFF0080, 0xFF00FFFF, 8f, 12f, 0.60f, "Rainbow pulse"));
        // ── ELECTRIC 5 ──
        l.add(new Preset("electric_blue",    "Electric Blue", "Electric","Electric",0xFF00BFFF, 0xFF0080FF, 6f, 9f, 0.90f, "Crackling blue bolts"));
        l.add(new Preset("electric_purple",  "Electric Purple","Electric","Electric",0xFF9D00FF, 0xFF6A00F5, 6f, 9f, 0.90f, "Violet thunder"));
        l.add(new Preset("electric_cyan",    "Electric Cyan", "Electric","Electric",0xFF00FFFF, 0xFF00CCFF, 6f, 9f, 0.90f, "Cyan discharge"));
        l.add(new Preset("electric_thunder", "Thunder White", "Electric","Electric",0xFFFFFFFF, 0xFFB0E0FF, 6f, 10f, 0.85f, "White lightning"));
        l.add(new Preset("electric_plasma",  "Plasma",        "Electric","Electric",0xFFFF00FF, 0xFF00FFFF, 6f, 10f, 0.95f, "Plasma flicker"));
        // ── CINEMATIC 5 ──
        l.add(new Preset("cinematic_black",  "Letterbox",     "Cinematic","Cinematic",0xFF000000, 0xFF1A1A1A, 4f, 0f, 0f, "Classic black bars"));
        l.add(new Preset("cinematic_gold",   "Golden Frame",  "Cinematic","Cinematic",0xFFFFD700, 0xFFB8860B, 5f, 4f, 0f, "Thin gold luxury"));
        l.add(new Preset("cinematic_white",  "Minimal White", "Cinematic","Cinematic",0xFFFFFFFF, 0xFFE8E8E8, 4f, 2f, 0f, "Clean white edge"));
        l.add(new Preset("cinematic_vintage","Vintage Brown", "Cinematic","Cinematic",0xFF8B4513, 0xFFD2691E, 10f, 3f, 0f, "Aged film border"));
        l.add(new Preset("cinematic_scope",  "Scope",         "Cinematic","Cinematic",0xFF0A0A0A, 0xFF333333, 12f, 0f, 0f, "2.39:1 scope bars"));
        // ── FLOW 4 ──
        l.add(new Preset("flow_rainbow",  "Rainbow Flow", "Flow","Color", 0xFFFF0000, 0xFF00FF00, 7f, 8f, 0.65f, "Full spectrum sweep"));
        l.add(new Preset("flow_sunset",   "Sunset Flow",  "Flow","Color", 0xFFFF4500, 0xFFFFD700, 7f, 8f, 0.65f, "Sunset gradient flow"));
        l.add(new Preset("flow_ocean",    "Ocean Tide",   "Flow","Color", 0xFF0077BE, 0xFF00CED1, 7f, 8f, 0.60f, "Ocean tide cycle"));
        l.add(new Preset("flow_neon_cycle","Neon Cycle", "Flow","Color", 0xFF00FF00, 0xFFFF00FF, 7f, 8f, 0.70f, "Neon color cycle"));
        // ── SPECIAL 6 ──
        l.add(new Preset("special_stars",       "Starfield",   "Special","Special",0xFFFFFFFF, 0xFFFFD700, 5f, 6f, 0.40f, "Twinkling star border"));
        l.add(new Preset("special_vignette",    "Vignette",    "Special","Special",0xFF000000, 0xFF333333, 14f, 18f, 0f, "Soft dark vignette"));
        l.add(new Preset("special_scanline",    "Scanline",    "Special","Special",0xFF00FF00, 0xFF003300, 4f, 2f, 0.80f, "CRT scanlines"));
        l.add(new Preset("special_bevel",       "Bevel",       "Special","Special",0xFF8B8B8B, 0xFFE0E0E0, 9f, 3f, 0f, "3D beveled frame"));
        l.add(new Preset("special_neon_corners","Neon Corners","Special","Special",0xFF00FFFF, 0xFFFF00FF, 6f, 10f, 0.70f, "Glowing corner brackets"));
        l.add(new Preset("special_double_line", "Double Line", "Special","Special",0xFFFFFFFF, 0xFF00D4FF, 5f, 4f, 0f, "Double stroke frame"));
        ALL = Collections.unmodifiableList(l);
    }

    public static List<Preset> all() { return ALL; }

    public static Preset byId(String id) {
        if (id==null) return null;
        for (Preset p: ALL) if (p.id.equals(id)) return p;
        return null;
    }

    public static List<Preset> byCategory(String cat) {
        if (cat==null || cat.equalsIgnoreCase("All")) return ALL;
        List<Preset> out=new ArrayList<>();
        String lc=cat.toLowerCase();
        for (Preset p: ALL) {
            if (p.displayCategory.equalsIgnoreCase(cat) || p.category.equalsIgnoreCase(cat)) out.add(p);
            else if (lc.equals("color") && (p.category.equals("Dual")||p.category.equals("Flow")||p.id.contains("rainbow"))) out.add(p);
            else if (lc.equals("light") && (p.category.equals("Glow") && p.displayCategory.equals("Light"))) out.add(p);
        }
        // Special handling for UI buckets that map to multiple internal categories
        if (lc.equals("neon")) { out.clear(); for(Preset p:ALL) if(p.category.equals("Neon")) out.add(p); }
        else if (lc.equals("glow")) { out.clear(); for(Preset p:ALL) if(p.category.equals("Glow")) out.add(p); }
        else if (lc.equals("electric")) { out.clear(); for(Preset p:ALL) if(p.category.equals("Electric")) out.add(p); }
        else if (lc.equals("cinematic")) { out.clear(); for(Preset p:ALL) if(p.category.equals("Cinematic")) out.add(p); }
        else if (lc.equals("special")) { out.clear(); for(Preset p:ALL) if(p.category.equals("Special")) out.add(p); }
        return out;
    }

    public static List<Preset> search(String q) {
        if (q==null || q.trim().isEmpty()) return ALL;
        String lc=q.toLowerCase();
        List<Preset> out=new ArrayList<>();
        for(Preset p:ALL) if(p.id.contains(lc)||p.name.toLowerCase().contains(lc)||p.category.toLowerCase().contains(lc)||p.desc.toLowerCase().contains(lc)) out.add(p);
        return out;
    }

    /** UI tabs in order */
    public static String[] categories() { return new String[]{"All","Neon","Glow","Electric","Cinematic","Color","Light","Special"}; }
}
