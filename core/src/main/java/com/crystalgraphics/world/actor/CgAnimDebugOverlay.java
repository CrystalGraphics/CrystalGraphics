package com.crystalgraphics.world.actor;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.debug.CgAnimDebug;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.render.host.CgStageHost;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.tag.CgTagCounts;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntityTags;

/**
 * The animation of the entity the player looks at, over the HUD ({@link CgAnimDebug}): Unreal's
 * {@code showdebug animation} over its debug target. The entity looked at becomes the target and stays it, while it
 * lives, until another is looked at. Client, the render thread's.
 *
 * <pre>{@code
 * CgAnimDebugOverlay.toggle();     // what /cgc anim debug does
 * }</pre>
 *
 * <ul>
 *   <li>An entity with an actor shows its body's instance; one whose vanilla model the engine poses
 *       ({@link CgVanillaPoses}), that instance. Any other is named as having none.</li>
 *   <li>Its text is laid out again ten times a second, not every frame.</li>
 * </ul>
 */
public final class CgAnimDebugOverlay {

    private static final Logger LOGGER = LogManager.getLogger(CgAnimDebugOverlay.class);
    /** Over the HUD's own lines, under the subtitles. */
    private static final int ORDER = CgRenderStage.SCREEN_WIDGETS - 2;
    /** How far the look reaches, in blocks. */
    private static final double REACH = 48;
    private static final long REFRESH_NANOS = 100_000_000L;
    private static final int PX = 14;
    private static final CgTextStyle LOOK = CgTextStyle.builder("crystalgraphics:anim_debug").size(75f)
            .layer(CgTextLayer.outline("outline").width(12f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill").paint(0xFF7CFC9A))
            .build();

    private static boolean shown, registered, failed;
    private static int target = -1;
    private static long laidOut;
    @Nullable
    private static CgTextLayout layout;
    @Nullable
    private static CgFont font;
    private static final StringBuilder TEXT = new StringBuilder();
    private static final double[] POSE = new double[CgEntityQuery.POSE_LENGTH];
    private static final CgHit HIT = new CgHit();

    private CgAnimDebugOverlay() {
    }

    /** Shows it, or hides it; answers whether it now shows. */
    public static synchronized boolean toggle() {
        shown = !shown;
        target = -1;
        layout = null;
        if (shown && !registered) {
            registered = true;
            CgRenderStage.HUD.register(ORDER, CgAnimDebugOverlay::draw);
        }
        return shown;
    }

    private static void draw(CgStageFrame frame) {
        if (!shown || failed) return;
        try {
            long now = System.nanoTime();
            if (layout == null || now - laidOut > REFRESH_NANOS) {
                if (font == null) font = CgDefaultFont.regular(PX);
                if (font == null) return;
                pick();
                TEXT.setLength(0);
                describe(TEXT);
                layout = CgTextLayout.of(TEXT.toString(), font).build();
                laidOut = now;
            }
            frame.canvas().textDraw().style(LOOK).layout(layout).font(font).contained(false)
                    .at(frame.width() - layout.totalWidth() - 8f, 8f).submit();
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[crystalgraphics] the animation debug overlay failed; it stays off", e);
        }
    }

    /** The entity under the player's look, blocks stopping it, becomes the target. */
    private static void pick() {
        CgEntityQuery entities = CgEntityQueries.client();
        int player = entities.localPlayer();
        float partialTick = CgStageHost.frame(CgStageHost.WORLD_OPAQUE).partialTick();
        if (player < 0 || !entities.pose(player, partialTick, POSE)) return;
        double yaw = Math.toRadians(POSE[CgEntityQuery.YAW]), pitch = Math.toRadians(POSE[CgEntityQuery.PITCH]);
        double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
        boolean hit = CgCollisionWorld.client(partialTick).ray()
                .at(POSE[CgEntityQuery.X], POSE[CgEntityQuery.Y] + POSE[CgEntityQuery.EYE_HEIGHT], POSE[CgEntityQuery.Z])
                .along(dx, dy, dz, REACH).source(player).blocksToo().first(HIT);
        if (hit && HIT.kind() == CgHit.Kind.ENTITY) target = HIT.entityId();
        if (target >= 0 && !entities.pose(target, partialTick, POSE)) target = -1;
    }

    private static void describe(StringBuilder out) {
        if (target < 0) {
            out.append("Animation: look at an entity");
            return;
        }
        CgEntityActor actor = CgEntityActors.actor(target);
        CgSkeletalMeshComponent body = actor != null ? actor.body() : null;
        CgAnimInstance anim = body != null ? body.anim() : CgVanillaPoses.instance(target);
        CgTagCounts tags = CgEntityTags.client(target);
        if (anim == null) {
            out.append("Animation: entity #").append(target).append(" has none");
            return;
        }
        String heading = (actor != null ? actor.entity().type() + " #" : "vanilla pose #") + target;
        CgAnimDebug.describe(heading, anim, tags, out);
    }
}
