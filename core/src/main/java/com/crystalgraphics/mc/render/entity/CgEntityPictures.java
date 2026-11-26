package com.crystalgraphics.mc.render.entity;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.platform.CgService;

/** The slot each era fills to draw a {@link CgEntityPicture}; the entity itself is the picture's source, resolved. */
public final class CgEntityPictures {

    /** What an era answers, render thread. */
    public interface Host {

        /** Why this node draws no entity pictures; null where it does. */
        @Nullable
        String absent();

        /**
         * {@code entity} posed, {@code scale} units a block, its bounding box centred across the box {@code x, y, w, h} of
         * the context's units and its middle {@code centreY} units below the box's top; clipped to the box where the game
         * clips its own. {@code ageTicks} is the picture's age, which a walking pose's cycle follows. A non-living
         * entity draws nothing: the inventory's drawing is for living ones.
         */
        void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float ageTicks, float scale, float x, float y,
                  float w, float h, float centreY);

        /**
         * Where this version's inventory puts its player's middle in a box {@code h} units tall, from the box's top:
         * standing 3 units above its bottom to 1.20.1, its middle 1/16 block below the box's to 1.20.2 and after.
         */
        float inventoryCentreY(float h, float entityHeight, float scale);

        /**
         * Where this version's inventory measures the pointer from, in a box {@code h} units tall, from its top: the
         * player's head, 50 units over its feet, to 1.20.1; the box's middle after.
         */
        float inventoryLookY(float h);

        Host NONE = new Host() {
            @Override public String absent() { return "no entity pictures on this node"; }
            @Override public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float ageTicks, float scale,
                                       float x, float y, float w, float h, float centreY) { }
            @Override public float inventoryCentreY(float h, float entityHeight, float scale) { return h / 2; }
            @Override public float inventoryLookY(float h) { return h / 2; }
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:entity_pictures", Host.NONE);

    private CgEntityPictures() {
    }
}
