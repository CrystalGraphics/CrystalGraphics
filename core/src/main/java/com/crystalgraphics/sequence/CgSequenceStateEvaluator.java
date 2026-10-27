package com.crystalgraphics.sequence;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * The state kinds' evaluator: each channel sampled at the sequence's time and written into its target's slot. Unreal's
 * property systems (transform, float and vector properties, material and collection parameters), one slot a property,
 * its components gathered and handed to the blend phase.
 */
final class CgSequenceStateEvaluator implements CgSequenceEvaluator {

    /** The kinds it evaluates, in the order the sequencer runs them. */
    static final List<String> KINDS = List.of("transform", "visibility", "parameter", "material", "property", "global");

    private static final String COMPONENTS = "xyzw", COLOUR = "rgba";

    /**
     * A section's channels resolved: for each, its blend channel (-1: not written), its component and its value at the
     * section's start (an {@code additive_from_base} section's base).
     */
    private record Bound(int[] channels, int[] components, float[] bases, boolean restores) {
    }

    private final String kind;
    private final CgSequenceBlender blender;
    private final float[] values = new float[4], base = new float[4];

    CgSequenceStateEvaluator(String kind, CgSequenceBlender blender) {
        this.kind = kind;
        this.blender = blender;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        if (section == null) return;
        String where = player.definition().id() + ": tracks." + track.id() + ".sections." + section.id();
        CgSequenceTarget target = target(player, track, section, where);
        List<CgSequenceChannel> channels = section.channels();
        int[] slots = new int[channels.size()], components = new int[channels.size()];
        float[] bases = new float[channels.size()];
        for (int c = 0; c < channels.size(); c++) {
            CgSequenceChannel ch = channels.get(c);
            String name = ch.name();
            // A section's weight is blending's, not a value.
            if (name.equals("weight")) {
                slots[c] = -1;
                continue;
            }
            if (ch.type() == CgSequenceChannel.Type.STRING || ch.type() == CgSequenceChannel.Type.EVENT
                    || ch.type() == CgSequenceChannel.Type.PARTICLE) {
                throw new CgAssetException(where + "." + name + ": a " + ch.type().name().toLowerCase(Locale.ROOT)
                        + " channel is not a value a " + kind + " track writes yet");
            }
            int dot = name.lastIndexOf('.'), component = 0;
            String slot = name;
            if (dot > 0 && name.length() == dot + 2) {
                char a = name.charAt(dot + 1);
                int i = Math.max(COMPONENTS.indexOf(a), COLOUR.indexOf(a));
                if (i >= 0) {
                    component = i;
                    slot = name.substring(0, dot);
                }
            }
            if (slot.equals("value")) slot = kind.equals("parameter") ? track.parameter() : track.property();
            else if (slot.indexOf('.') > 0) slot = slot.substring(slot.indexOf('.') + 1);
            if (slot == null) throw new CgAssetException(where + "." + name + ": a value with no " + (kind.equals("parameter") ? "parameter" : "property") + " on its track");
            int index = target.slot(slot);
            if (index < 0) throw new CgAssetException(where + "." + name + ": " + target + " has no " + slot);
            slots[c] = blender.channel(target, index);
            components[c] = component;
            int start = section.from() == CgSequenceSection.OPEN_START ? (ch.size() > 0 ? ch.tick(0) : 0) : section.from();
            bases[c] = ch.type() == CgSequenceChannel.Type.FLOAT ? ch.eval(start) : ch.integer(start);
        }
        player.data(track, section, new Bound(slots, components, bases, section.completion() != CgSequenceSection.Completion.KEEP_STATE));
    }

    private CgSequenceTarget target(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section, String where) {
        if (track.kind().equals("global")) {
            if (track.globals() == null) throw new CgAssetException(where + ": its collection " + track.collection() + " is not compiled");
            return blender.globals(track.globals());
        }
        CgSequenceTarget target = player.target(track);
        // A material track names its slot on itself where its binding names no material.
        if (target != null && track.kind().equals("material") && track.binding().part() != CgSequenceBinding.Part.MATERIAL
                && section.fields().get("slot") instanceof Map<?, ?> slot && slot.get("name") instanceof String name) {
            CgSequenceTarget part = target.part(CgSequenceBinding.Part.MATERIAL, name);
            if (part == null) throw new CgAssetException(where + ".slot: " + target + " has no material " + name);
            target = part;
        }
        if (target == null) throw new CgAssetException(where + ": " + track.binding().subject().name().toLowerCase(Locale.ROOT)
                + " is bound to nothing a " + track.kind() + " track can write");
        return target;
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section, float tick) {
        if (!(player.data(track, section) instanceof Bound b)) return;
        List<CgSequenceChannel> channels = section.channels();
        float weight = section.weight(tick);
        int channel = -1, mask = 0;
        for (int c = 0; c < channels.size(); c++) {
            CgSequenceChannel ch = channels.get(c);
            // No keys and no default: nothing to write, as Unreal's Evaluate returns false.
            if (b.channels[c] < 0 || !ch.hasValue()) continue;
            if (b.channels[c] != channel) {
                if (mask != 0) blender.add(channel, values, mask, section.blend(), weight, base, b.restores);
                channel = b.channels[c];
                mask = 0;
            }
            values[b.components[c]] = ch.type() == CgSequenceChannel.Type.FLOAT ? ch.eval(tick) : ch.integer((int) Math.floor(tick));
            base[b.components[c]] = b.bases[c];
            mask |= 1 << b.components[c];
        }
        if (mask != 0) blender.add(channel, values, mask, section.blend(), weight, base, b.restores);
    }
}
