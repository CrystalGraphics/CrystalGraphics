package com.crystalgraphics.demo.anim;

import com.crystalgraphics.platform.input.CgKeyCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every cast in a row, each playing one of its own clips: the clip browser. Left and Right change the picked body's
 * clip, and every body sharing its clip files moves with it; Up and Down pick the body. Time is the demo's (Y slower,
 * Shift+Y faster, to a stop).
 */
final class ClipsStation implements AnimDemoStation {

    private static final double SPACING = 1.6;

    private final List<AnimDemoCast.Body> bodies = new ArrayList<>();
    private final List<Integer> clip = new ArrayList<>();
    private int selected;
    private boolean loopAll;

    @Override
    public String name() {
        return "Clips";
    }

    @Override
    public void build(AnimDemoSet set, AnimDemoCasts casts, double x, double y, double z) {
        List<AnimDemoCast> all = casts.all();
        double x0 = x - SPACING * (all.size() - 1) / 2;
        for (int i = 0; i < all.size(); i++) {
            bodies.add(all.get(i).spawn().at(x0 + i * SPACING, y, z, 0f));
            clip.add(0);
            play(i);
        }
        selected = Math.min(selected, Math.max(0, bodies.size() - 1));
    }

    @Override
    public float[] view() {
        return new float[]{0f, 1.3f, 5f, 0f, -5f};
    }

    private void play(int i) {
        AnimDemoCast.Body b = bodies.get(i);
        if (b.cast.clips.isEmpty()) return;
        AnimDemoCast.Clip c = b.cast.clips.get(Math.floorMod(clip.get(i), b.cast.clips.size()));
        b.instance.play(c.sequence(), c.loops() || loopAll);
    }

    @Override
    public boolean press(int key, boolean shift) {
        if (bodies.isEmpty()) return false;
        switch (key) {
            case CgKeyCodes.KEY_RIGHT, CgKeyCodes.KEY_LEFT -> {
                String family = bodies.get(selected).cast.family;
                int next = clip.get(selected) + (key == CgKeyCodes.KEY_RIGHT ? 1 : -1);
                for (int i = 0; i < bodies.size(); i++) {
                    if (!bodies.get(i).cast.family.equals(family)) continue;
                    clip.set(i, next);
                    play(i);
                }
            }
            case CgKeyCodes.KEY_UP -> selected = (selected + 1) % bodies.size();
            case CgKeyCodes.KEY_DOWN -> selected = Math.floorMod(selected - 1, bodies.size());
            case CgKeyCodes.KEY_GRAVE -> {
                for (int i = 0; i < bodies.size(); i++) play(i);
            }
            case CgKeyCodes.KEY_BACKSLASH -> {
                loopAll = !loopAll;
                for (int i = 0; i < bodies.size(); i++) play(i);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public String hud() {
        if (bodies.isEmpty()) return "\nNo character loaded";
        AnimDemoCast.Body b = bodies.get(selected);
        if (b.cast.clips.isEmpty()) return String.format(Locale.ROOT, "%nBody [Up/Down]: %s | no clips", b.cast.name);
        int i = Math.floorMod(clip.get(selected), b.cast.clips.size());
        AnimDemoCast.Clip c = b.cast.clips.get(i);
        return String.format(Locale.ROOT, "%nBody [Up/Down]: %s (%d / %d)%nClip [Left/Right]: %s (%d / %d), %s | Again [`]"
                        + "%n%.2f / %.2f s | Loop all [\\]: %s",
                b.cast.name, selected + 1, bodies.size(), c.name(), i + 1, b.cast.clips.size(), c.loops() ? "loops" : "once",
                b.instance.position(), c.sequence().duration(), loopAll ? "on" : "off");
    }

    @Override
    public void delete() {
        for (AnimDemoCast.Body b : bodies) b.remove();
        bodies.clear();
        clip.clear();
    }
}
