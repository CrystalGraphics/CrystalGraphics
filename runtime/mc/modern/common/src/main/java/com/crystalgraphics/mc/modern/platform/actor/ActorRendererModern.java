package com.crystalgraphics.mc.modern.platform.actor;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;
//? if >=1.17 {
import net.minecraft.client.renderer.entity.EntityRendererProvider;
//?} else {
/*import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
*///?}
//? if >=1.21.10 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//? if >=26.1 {
/^import net.minecraft.client.renderer.state.level.CameraRenderState;
^///?} else {
import net.minecraft.client.renderer.state.CameraRenderState;
//?}
import net.minecraft.world.phys.Vec3;
*///?} elif >=1.21.3 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
*///?} elif >=1.16 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
//?} elif >=1.15 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
*///?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * The renderer an entity with an actor is drawn through instead of its own: everything its own renderer draws but its
 * model. Its name tag and leash are the base renderer's, asked of the real one (a player's keeps its score line); its
 * shadow, fire and hitbox are the dispatcher's, from the real one's state or radius. One per real renderer, made the
 * first time an actor's entity reaches it and dropped when the host rebuilds its renderers.
 *
 * <ul>
 *   <li>Before 1.21.3 the entity is drawn from itself: the name tag asked of the real renderer, its shadow radius and
 *       strength copied from it each time ({@link #of}). 1.14.4 and before draw shadow and fire in the renderer's own
 *       {@code postRender}, which this inherits.</li>
 *   <li>1.21.3 to 1.21.8 draw from a state the renderer extracts: the real one extracts it, so the name it decided on
 *       and its shadow are its own.</li>
 *   <li>From 1.21.10 the state is taken first and submitted later: only submitting comes here.</li>
 * </ul>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ActorRendererModern extends EntityRenderer {

    /** By the renderer each stands in for; weak, as the host drops its renderers on a resource reload. */
    private static final Map<EntityRenderer, ActorRendererModern> MADE = new WeakHashMap<>();
    //? if >=1.17 {
    private static EntityRendererProvider.Context context;
    //?} else {
    /*private static EntityRenderDispatcher context;
    *///?}
    /** The renderer one being made stands in for: the base constructor asks for a state before {@link #real} is set. */
    private static EntityRenderer making;

    private final EntityRenderer real;
    private final HostEntityRenderer host;

    //? if >=1.17 {
    private ActorRendererModern(EntityRendererProvider.Context context, EntityRenderer real) {
    //?} else {
    /*private ActorRendererModern(EntityRenderDispatcher context, EntityRenderer real) {
    *///?}
        super(context);
        this.real = real;
        this.host = (HostEntityRenderer) real;
    }

    /** The renderer an actor's entity is drawn through in place of {@code real}. The render thread's. */
    static EntityRenderer of(EntityRenderer real) {
        if (real instanceof ActorRendererModern) return real;
        ActorRendererModern made = MADE.get(real);
        if (made == null) {
            making = real;
            try {
                made = new ActorRendererModern(context, real);
            } finally {
                making = null;
            }
            MADE.put(real, made);
        }
        //? if <1.21.3 {
        made.shadowRadius = made.host.crystalgraphics$shadowRadius();
        //?}
        //? if <1.21.4 {
        made.shadowStrength = made.host.crystalgraphics$shadowStrength();
        //?}
        return made;
    }

    /** Each {@code EntityRenderer} constructed: the host's context for them, which ours are made with too. */
    //? if >=1.17 {
    public static void constructed(EntityRendererProvider.Context with) {
    //?} else {
    /*public static void constructed(EntityRenderDispatcher with) {
    *///?}
        if (with == context) return;
        context = with;
        MADE.clear();
    }

    //? if >=1.21.10 {
    /*@Override
    public EntityRenderState createRenderState() {
        return (real != null ? real : making).createRenderState();
    }

    @Override
    public void extractRenderState(Entity entity, EntityRenderState state, float partialTick) {
        real.extractRenderState(entity, state, partialTick);
    }

    @Override
    public Vec3 getRenderOffset(EntityRenderState state) {
        return real.getRenderOffset(state);
    }

    //? if >=26.1 {
    /^@Override
    protected void submitNameDisplay(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera) {
        host.crystalgraphics$submitNameTag(state, poseStack, nodes, camera);
    }
    ^///?} else {
    @Override
    protected void submitNameTag(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera) {
        host.crystalgraphics$submitNameTag(state, poseStack, nodes, camera);
    }
    //?}
    *///?} elif >=1.21.3 {
    /*@Override
    public EntityRenderState createRenderState() {
        return (real != null ? real : making).createRenderState();
    }

    @Override
    public void extractRenderState(Entity entity, EntityRenderState state, float partialTick) {
        real.extractRenderState(entity, state, partialTick);
    }

    @Override
    public Vec3 getRenderOffset(EntityRenderState state) {
        return real.getRenderOffset(state);
    }

    @Override
    protected float getShadowRadius(EntityRenderState state) {
        return host.crystalgraphics$shadowRadius(state);
    }

    @Override
    protected void renderNameTag(EntityRenderState state, Component name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        host.crystalgraphics$renderNameTag(state, name, poseStack, buffers, light);
    }
    *///?} elif >=1.15 {
    @Override
    public ResourceLocation getTextureLocation(Entity entity) {
        return real.getTextureLocation(entity);
    }

    @Override
    public Vec3 getRenderOffset(Entity entity, float partialTick) {
        return real.getRenderOffset(entity, partialTick);
    }

    @Override
    protected boolean shouldShowName(Entity entity) {
        return host.crystalgraphics$shouldShowName(entity);
    }
    //?} else {
    /*@Override
    protected ResourceLocation getTextureLocation(Entity entity) {
        return null;
    }

    @Override
    protected void renderName(Entity entity, double x, double y, double z) {
        host.crystalgraphics$renderName(entity, x, y, z);
    }
    *///?}

    //? if >=1.20.6 <1.21.3 {
    /*@Override
    protected void renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light, float partialTick) {
        host.crystalgraphics$renderNameTag(entity, name, poseStack, buffers, light, partialTick);
    }
    *///?} elif >=1.16 <1.21.3 {
    @Override
    protected void renderNameTag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        host.crystalgraphics$renderNameTag(entity, name, poseStack, buffers, light);
    }
    //?} elif >=1.15 <1.21.3 {
    /*@Override
    protected void renderNameTag(Entity entity, String name, PoseStack poseStack, MultiBufferSource buffers, int light) {
        host.crystalgraphics$renderNameTag(entity, name, poseStack, buffers, light);
    }
    *///?}

    //? if >=1.21.4 <1.21.10 {
    /*@Override
    protected float getShadowStrength(EntityRenderState state) {
        return host.crystalgraphics$shadowStrength(state);
    }
    *///?}
}
