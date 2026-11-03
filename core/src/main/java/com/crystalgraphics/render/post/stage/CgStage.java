package com.crystalgraphics.render.post.stage;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.fog.CgHeightFog;
import com.crystalgraphics.render.post.volume.CgPostBlendable;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.render.stage.CgRenderStage;

/**
 * A stage placed over the host's world: a game level's post volumes and height fog, shown until closed. The host places
 * one while it wants the game's arena look, as the game's persistent level holds them for a whole fight; a move never
 * places one.
 *
 * <pre>{@code
 * static final CgAssetRef<CgStageDefinition> AREA02 = CgVfxAssets.ref(CgStageKinds.STAGE, "kakarot_kit:Area02");
 * AREA02.request(CgLoadPriority.MOVE);                        // when the fight is coming: loaded, compiled, warmed
 * CgStage stage = CgStage.place(AREA02.get(), x, y, z);      // the fight's ground at (x, y, z): the stage's anchor there
 * stage.weight(0.5f);                                         // halfway faded in
 * stage.close();                                              // the fight is over
 * }</pre>
 *
 * <ul>
 *   <li>Place it on the client's render thread, from a definition that is ready: its post materials' programs are
 *       built ahead by the stage's preparer, and a stage placed before then draws them late.</li>
 *   <li>It keeps the definition it was placed with: a reload of the file reaches the next place.</li>
 *   <li>Its volumes blend with every other volume by priority, a move's included; its fog is the one layer it has.</li>
 * </ul>
 */
public final class CgStage implements AutoCloseable {

    private final List<CgPostVolume> volumes = new ArrayList<>();
    private final List<CgPostMaterial> materials = new ArrayList<>();
    private final List<CgPostBlendable> blendables = new ArrayList<>();
    private final List<CgRenderStage.Registration> registrations = new ArrayList<>();
    @Nullable
    private final CgHeightFog fog;
    private float weight = 1f;
    private boolean closed;

    private CgStage(CgStageDefinition definition, double x, double y, double z) {
        CgPostStack stack = CgPostStack.get();
        for (CgStageDefinition.Volume v : definition.volumes()) {
            CgPostVolume volume = stack.volume(v.priority(), v.settings());
            if (!v.unbound()) volume.at(x + v.x(), y + v.y(), z + v.z()).radius(v.radius()).blend(v.blend());
            volumes.add(volume);
            for (CgPostBlendable b : v.blendables()) {
                if (b.material() == null) continue;
                CgPostMaterial post = b.draw(v.priority());
                materials.add(post);
                blendables.add(b);
                registrations.add(stack.add(post));
            }
        }
        CgStageDefinition.Fog f = definition.fog();
        if (f != null) {
            fog = new CgHeightFog().density(f.density()).heightFalloff(f.falloff()).height(y + f.height())
                    .colour(f.red(), f.green(), f.blue()).maxOpacity(f.maxOpacity()).startDistance(f.start())
                    .cutoffDistance(f.cutoff());
            registrations.add(fog.show());
        } else {
            fog = null;
        }
        weigh();
    }

    /** Shows {@code definition} with its anchor at the host's {@code (x, y, z)}, until {@link #close}. */
    public static CgStage place(CgStageDefinition definition, double x, double y, double z) {
        return new CgStage(definition, x, y, z);
    }

    /** How much of it shows, 0 to 1: its volumes', post materials' and fog's weight. 1 when placed. */
    public CgStage weight(float weight) {
        this.weight = Math.max(0f, Math.min(1f, weight));
        weigh();
        return this;
    }

    public float weight() {
        return weight;
    }

    private void weigh() {
        for (CgPostVolume v : volumes) v.weight(weight);
        for (int i = 0; i < materials.size(); i++) {
            CgPostBlendable b = blendables.get(i);
            materials.get(i).weight(b.enabled() ? b.weight() * weight : 0f);
        }
        if (fog != null) fog.weight(weight);
    }

    /** Takes it off the world. Twice is harmless. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (CgPostVolume v : volumes) v.close();
        for (CgRenderStage.Registration r : registrations) r.close();
    }
}
