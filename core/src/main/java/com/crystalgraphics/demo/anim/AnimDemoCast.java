package com.crystalgraphics.demo.anim;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
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
        double x, y, z;
        float yaw;

        private Body(AnimDemoCast cast) {
            this.cast = cast;
            this.component = CgScene.client().skeletal(cast.mesh).materials(cast.materials::apply).customData(0, 0f, 0f, 0f, 0f);
            this.instance = component.anim();
            component.spawn();
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
        }
    }

    final String name;
    /** Casts sharing their clip files: the clip browser moves them together. */
    final String family;
    final CgSkeletalMesh mesh;
    final List<Clip> clips;
    /** Mesh to body: its feet on the floor, facing +z. */
    final Matrix4f fit;
    private final IntFunction<CgMaterial> materials;

    AnimDemoCast(String name, String family, CgSkeletalMesh mesh, List<Clip> clips, Matrix4f fit, IntFunction<CgMaterial> materials) {
        this.name = name;
        this.family = family;
        this.mesh = mesh;
        this.clips = List.copyOf(clips);
        this.fit = fit;
        this.materials = materials;
    }

    Body spawn() {
        return new Body(this);
    }

    /** The first clip whose name contains {@code part}, ignoring case; null for none. */
    @Nullable
    Clip clip(String part) {
        String p = part.toLowerCase(Locale.ROOT);
        for (Clip c : clips) if (c.name.toLowerCase(Locale.ROOT).contains(p)) return c;
        return null;
    }
}
