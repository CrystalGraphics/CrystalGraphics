package com.crystalgraphics.mc.render.entity;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgService;

/** The slot each era fills to draw a {@link CgEntityPicture}. */
public final class CgEntityPictures {

    /** What an era answers, render thread. */
    public interface Host {

        /** Why this node draws no entities; null where it does. */
        @Nullable
        String absent();

        /**
         * The game's entity {@code ref} names: the live one, or one made client-side and never added to the level.
         * {@code previous} is what this answered last for the same picture, kept while it still is that entity. Null for
         * none (a live entity that left, a type the game does not know).
         */
        @Nullable
        Object entity(CgEntityRef ref, @Nullable Object previous);

        /** Its height in blocks, for fitting it to a rect. */
        float height(Object entity);

        /**
         * {@code entity} posed, {@code scale} units a block, centred in the box {@code x, y, w, h} of the context's units,
         * as the inventory centres its player in its own.
         */
        void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float scale, float x, float y, float w, float h);

        Host NONE = new Host() {
            @Override public String absent() { return "no entity pictures on this node"; }
            @Override public Object entity(CgEntityRef ref, Object previous) { return null; }
            @Override public float height(Object entity) { return 1; }
            @Override public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float scale, float x, float y, float w, float h) { }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:entity_pictures", Host.NONE);

    private CgEntityPictures() {
    }
}
