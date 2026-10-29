package com.crystalgraphics.camera;

import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.world.CgAttachment;

/**
 * Whose camera it is: Gameplay Cameras' {@code FCameraEvaluationContext}. The host's own camera is a context at the
 * bottom of every viewer's stack; gameplay pushes one for a move's camera, a sequence one for its cut. The top one's
 * director picks the rig.
 *
 * <pre>{@code
 * CgCameraContext move = new CgCameraContext("mymod:ultimate")
 *         .director(CgCameraDirector.single(rig))
 *         .roles(role -> role.equals("caster") ? casterAttachment : targetAttachment);
 * move.initial().set(cameras.host());           // its own pose: what owning_context offsets read
 * cameras.push(move);                           // its rig blends in by its enter transition
 * cameras.remove(move);                         // and out by its exit transition
 * }</pre>
 *
 * <ul>
 *   <li>Its {@link #initial()} pose is its space: a rig's {@code owning_context} values are in it. Set it as it is
 *       pushed, or keep it on what it follows.</li>
 *   <li>A role the rig names and {@link #roles} does not answer is not followed: its weight is 0.</li>
 * </ul>
 */
public class CgCameraContext {

    private final String name;
    private final CgCameraPose initial = new CgCameraPose();
    private CgCameraDirector director = CgCameraDirector.single(null);
    private Function<String, CgAttachment> roles = role -> null;
    @Nullable
    private Object owner;
    private int flags;

    public CgCameraContext(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    /** Its own pose, in which a rig's context-space values are written: {@code GetInitialResult().CameraPose}. */
    public CgCameraPose initial() {
        return initial;
    }

    public CgCameraDirector director() {
        return director;
    }

    public CgCameraContext director(CgCameraDirector director) {
        this.director = Objects.requireNonNull(director, "director");
        return this;
    }

    /** What its rigs' roles ({@code caster}, {@code target}) attach to; null for one it does not have. */
    public CgCameraContext roles(Function<String, CgAttachment> roles) {
        this.roles = Objects.requireNonNull(roles, "roles");
        return this;
    }

    /** Whose camera it is, a sequence's play: what {@link CgCameraSystem#weightOf} answers for. */
    public CgCameraContext owner(@Nullable Object owner) {
        this.owner = owner;
        return this;
    }

    @Nullable
    public Object owner() {
        return owner;
    }

    /** What it asks of the viewer while it holds the view: {@code CgHostCamera.HIDE_HUD}, {@code HIDE_PLAYER}; 0. */
    public CgCameraContext flags(int flags) {
        this.flags = flags;
        return this;
    }

    public int flags() {
        return flags;
    }

    @Nullable
    public CgAttachment role(String name) {
        return roles.apply(name);
    }

    @Override
    public String toString() {
        return "CgCameraContext[" + name + "]";
    }
}
