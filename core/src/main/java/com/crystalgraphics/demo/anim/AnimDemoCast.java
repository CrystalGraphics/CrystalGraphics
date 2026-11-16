package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.render.post.CgOutline;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * One character of the animation demo, loaded once: its mesh, its clips, how it stands (feet on the floor, facing +z)
 * and what each slot is drawn in. A station {@link #spawn}s as many bodies of it as it needs.
 *
 * <pre>{@code
 * AnimDemoCast.Body body = cast.spawn().at(x, y, z, 0f);
 * body.instance.play(cast.clip("walk").sequence(), true);
 * }</pre>
 */
final class AnimDemoCast {

    /** A clip: its name, its sequence, and whether its file loops it (a GeckoLib clip says; a glTF clip loops). */
    record Clip(String name, CgAnimSequence sequence, boolean loops) {
    }

    /** One spawned body: its component and the instance posing it, where it stands and its turn from facing +z. */
    static final class Body {
        final AnimDemoCast cast;
        final CgSkeletalMeshComponent component;
        final CgAnimInstance instance;
        private final Matrix4f place = new Matrix4f();
        @Nullable
        private final CgOutline.Hold outline;
        double x, y, z;
        float yaw;

        private Body(AnimDemoCast cast) {
            this.cast = cast;
            this.component = CgScene.client().skeletal(cast.mesh).materials(cast.materials::apply).customData(0, 0f, 0f, 0f, 0f)
                    .grade(cast.grade);
            if (cast.stencil > 0) component.customDepth(cast.stencil);
            this.outline = cast.stencil > 0 ? CgOutline.hold(CgScene.client(), cast.stencil, cast.outlineLook) : null;
            this.instance = component.anim();
            component.spawn();
            cast.live.add(this);
        }

        /** Stands it at ({@code x}, {@code y}, {@code z}), turned {@code yaw} radians from facing +z about its feet. */
        Body at(double x, double y, double z, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            component.at(x, y, z).transform(place.rotationY(yaw).mul(cast.fit));
            return this;
        }

        void remove() {
            component.remove();
            if (outline != null) outline.close();
            cast.live.remove(this);
        }
    }

    final String name;
    /** Casts sharing their clip files: the clip browser moves them together. */
    final String family;
    final CgSkeletalMesh mesh;
    final List<Clip> clips;
    /** Mesh to body: its feet on the floor, facing +z. */
    final Matrix4f fit;
    final IntFunction<CgMaterial> materials;
    /** The custom stencil its bodies write, outlined by the engine's shared outline; 0 for none. */
    final int stencil;
    /** That outline's look, a kit's material on the outline shader; null for the engine's. */
    @Nullable
    final CgMaterial outlineLook;
    /** Its kit's look, grading its pixels alone; null for the frame's. */
    @Nullable
    final CgPostSettings grade;
    /** Every body of every cast now standing, shared by the casts: what the demo's time steps and records. */
    private final List<Body> live;

    AnimDemoCast(String name, String family, CgSkeletalMesh mesh, List<Clip> clips, Matrix4f fit, IntFunction<CgMaterial> materials,
                 int stencil, @Nullable CgMaterial outlineLook, @Nullable CgPostSettings grade, List<Body> live) {
        this.name = name;
        this.family = family;
        this.mesh = mesh;
        this.clips = List.copyOf(clips);
        this.fit = fit;
        this.materials = materials;
        this.stencil = stencil;
        this.outlineLook = outlineLook;
        this.grade = grade;
        this.live = live;
    }

    Body spawn() {
        return new Body(this);
    }

    /** This character drawn from {@code mesh}: its own mesh with bones added ({@code withVirtualBones}). */
    AnimDemoCast withMesh(CgSkeletalMesh mesh) {
        return new AnimDemoCast(name, family, mesh, clips, fit, materials, stencil, outlineLook, grade, live);
    }

    /** The first clip whose name contains {@code part}, ignoring case; null for none. */
    @Nullable
    Clip clip(String part) {
        String p = part.toLowerCase(Locale.ROOT);
        for (Clip c : clips) if (c.name.toLowerCase(Locale.ROOT).contains(p)) return c;
        return null;
    }
}
