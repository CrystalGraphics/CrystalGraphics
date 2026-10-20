package com.crystalgraphics.vfx.render;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Effects that sort as one: their transparent draws in one group of the world renderer, at one place, their layers in
 * one order whatever their distances. A move made of many effects (CC2's clip, whose render layer numbers hold across
 * all of it) keeps its authored layering this way, as Unreal's components sharing a translucency sort priority do.
 *
 * <pre>{@code
 * CgVfxSortGroup move = new CgVfxSortGroup();                       // one per move played
 * VFX.play(new CgVfxSystemInstance(SPIRAL, x, y, z).sortGroup(move));
 * VFX.play(new CgVfxSystemInstance(SHOCKWAVE, x, y, z).sortGroup(move));     // its layers order against SPIRAL's by their hints
 * }</pre>
 *
 * <pre>{@code
 * move.at(casterX, casterY + 1, casterZ);                           // sorts at the caster, not at its members' middle
 * }</pre>
 *
 * <ul>
 *   <li>Members' layers rank by role, then {@code order} hint, then the member that joined first: a hint means the
 *       same in every member's look.</li>
 *   <li>At most 256 layers across every look its members play.</li>
 *   <li>GPU particles keep their system's group: what this orders is every other layer.</li>
 *   <li>Render thread; a member leaves when it dies.</li>
 * </ul>
 */
public final class CgVfxSortGroup {

    private final List<CgVfxEffect<?>> members = new ArrayList<>();
    private final List<CgVfxLook> looks = new ArrayList<>();
    private final Map<CgVfxLook, int[]> ranks = new IdentityHashMap<>();
    private boolean fixed;
    private double x, y, z;
    private long placedFor = Long.MIN_VALUE;

    /** Sorts at this point, in the world, from now on. */
    public CgVfxSortGroup at(double x, double y, double z) {
        this.fixed = true;
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** Sorts at the middle of its members' origins again, each frame: the default. */
    public CgVfxSortGroup atMembers() {
        fixed = false;
        placedFor = Long.MIN_VALUE;
        return this;
    }

    /** {@code effect} draws in it; a look new to the group ranks its layers among the others'. */
    public void join(CgVfxEffect<?> effect) {
        if (members.contains(effect)) return;
        members.add(effect);
        if (!ranks.containsKey(effect.look())) {
            looks.add(effect.look());
            rank();
        }
    }

    /** {@code effect} draws in it no more. */
    public void leave(CgVfxEffect<?> effect) {
        members.remove(effect);
    }

    /** How many effects draw in it. */
    public int members() {
        return members.size();
    }

    /** The world renderer's order for {@code placed}, a layer of {@code look}: its rank among every member's layers. */
    public int order(CgVfxLook look, CgVfxLook.Placed placed) {
        int[] r = ranks.get(look);
        return r == null ? placed.order() : r[placed.order()];
    }

    /** Where it sorts this frame ({@code frame} the caller's frame number): its point, else its members' middle. */
    public double x(long frame) {
        place(frame);
        return x;
    }

    public double y(long frame) {
        place(frame);
        return y;
    }

    public double z(long frame) {
        place(frame);
        return z;
    }

    private void place(long frame) {
        if (fixed || frame == placedFor || members.isEmpty()) return;
        placedFor = frame;
        double sx = 0, sy = 0, sz = 0;
        for (CgVfxEffect<?> m : members) {
            sx += m.originX();
            sy += m.originY();
            sz += m.originZ();
        }
        int n = members.size();
        x = sx / n;
        y = sy / n;
        z = sz / n;
    }

    /** Every look's layers by (role, hint, the look that joined first, its place in that look), numbered in turn. */
    private void rank() {
        List<long[]> keys = new ArrayList<>();
        for (int l = 0; l < looks.size(); l++) {
            List<CgVfxLook.Placed> placed = looks.get(l).placed();
            for (CgVfxLook.Placed p : placed) {
                CgVfxLayer layer = p.layer();
                keys.add(new long[] {layer.role().ordinal(), layer.order(), l, p.order()});
            }
        }
        if (keys.size() > 256) {
            throw new IllegalStateException("a sort group ranks at most 256 layers; its looks draw " + keys.size());
        }
        keys.sort((a, b) -> {
            for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return Long.compare(a[i], b[i]);
            return 0;
        });
        ranks.clear();
        for (int l = 0; l < looks.size(); l++) ranks.put(looks.get(l), new int[looks.get(l).placed().size()]);
        for (int i = 0; i < keys.size(); i++) {
            long[] k = keys.get(i);
            ranks.get(looks.get((int) k[2]))[(int) k[3]] = i;
        }
    }
}
