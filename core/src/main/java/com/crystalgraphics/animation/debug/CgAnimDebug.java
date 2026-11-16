package com.crystalgraphics.animation.debug;

import java.util.List;
import java.util.Locale;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.montage.CgMontageInstance;
import com.crystalgraphics.animation.state.CgAnimStateMachineNode;
import com.crystalgraphics.animation.sync.CgAnimAssetPlayer;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.tag.CgTagCounts;

/**
 * What an animation instance is doing, as lines of text: its variables, its state machines' states, the clips it
 * played this frame and its montages. Mirrors Unreal's {@code showdebug animation} ({@code UAnimInstance::DisplayDebug}:
 * the graph's state, then each player's asset, weight and position over length, then each montage's weight).
 *
 * <pre>{@code
 * StringBuilder text = new StringBuilder();
 * CgAnimDebug.describe("Goku #42", body.anim(), CgEntityTags.client(42), text);
 * }</pre>
 *
 * <pre>{@code
 * // in game, over the entity looked at: /cgc anim debug
 * }</pre>
 *
 * <ul>
 *   <li>Read it after {@code POSE}, where the frame's players are recorded: the HUD stage is.</li>
 *   <li>It allocates as it formats: a tool's, never a frame path's.</li>
 * </ul>
 */
public final class CgAnimDebug {

    private CgAnimDebug() {
    }

    /** {@code anim}'s state under {@code heading}, with {@code tags} (none to leave them out), appended to {@code out}. */
    public static void describe(String heading, CgAnimInstance anim, CgTagCounts tags, StringBuilder out) {
        CgAnimGraphInstance g = anim.graph();
        out.append("Animation: ").append(heading).append('\n');
        out.append("  ").append(g.graph().id()).append(String.format(Locale.ROOT, "  dt %.3f  frame %d%n", g.delta(), g.frame()));

        List<CgAnimVariable<?>> variables = g.graph().variables();
        out.append("Variables: ").append(variables.size()).append('\n');
        for (CgAnimVariable<?> v : variables) out.append("  ").append(v.name()).append(" = ").append(value(g, v)).append('\n');

        if (tags.size() > 0) {
            out.append("Tags: ").append(tags.size()).append('\n');
            for (int i = 0; i < tags.size(); i++) {
                out.append("  ").append(tags.tag(i));
                if (tags.countAt(i) > 1) out.append(" x").append(tags.countAt(i));
                out.append('\n');
            }
        }

        out.append("State machines:\n");
        g.forEachRuntime(r -> {
            if (!(r instanceof CgAnimStateMachineNode.Machine m)) return;
            out.append(String.format(Locale.ROOT, "  %s: %s %.2f s", m.machineName(), m.currentState(), m.elapsed()));
            if (m.activeTransitions() > 0) out.append(" (blending ").append(m.activeTransitions()).append(')');
            out.append('\n');
        });

        out.append("Players: ").append(g.records()).append('\n');
        for (int i = 0; i < g.records(); i++) {
            CgAnimAssetPlayer p = g.recordPlayer(i);
            out.append(String.format(Locale.ROOT, "  %d) %s W %.0f%% P %.2f/%.2f%n", i, asset(p.asset()), g.recordWeight(i) * 100f,
                    p.accumulatedTime(), p.playLength()));
        }

        List<CgMontageInstance> montages = anim.montages().active();
        out.append("Montages: ").append(montages.size()).append('\n');
        for (int i = 0; i < montages.size(); i++) {
            CgMontageInstance m = montages.get(i);
            out.append(String.format(Locale.ROOT, "  %d) %s W %.2f %s, from %s%n", i, m.montage().group(), m.weight(),
                    m.playing() ? "playing" : m.stopped() ? "stopping" : "ending", m.source()));
        }
    }

    private static String value(CgAnimGraphInstance g, CgAnimVariable<?> v) {
        float f = g.value(v);
        return switch (v.type()) {
            case BOOL -> f != 0f ? "true" : "false";
            case INT -> Integer.toString((int) f);
            case FLOAT -> String.format(Locale.ROOT, "%.3f", f);
        };
    }

    private static String asset(Object asset) {
        return asset instanceof CgAnimSequence s ? s.name() : String.valueOf(asset);
    }
}
