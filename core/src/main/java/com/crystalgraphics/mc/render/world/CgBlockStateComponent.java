package com.crystalgraphics.mc.render.world;

import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4fc;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;

/**
 * A block in a scene, not in the level: its model by Minecraft's block renderer and its block entity by its own (a
 * chest's lid, a banner's pattern), lit by the light at its position.
 *
 * <pre>{@code
 * CgBlockStateComponent chest = CgBlockStateComponent.of(CgScene.client(), CgBlockState.parse("chest[facing=south]"))
 *         .at(x, y, z)                 // its corner, as a block's position names it
 *         .spawn();
 * }</pre>
 *
 * <ul>
 *   <li>Throws as it first draws where the game has not got the block or a property, and is hidden.</li>
 *   <li>Draws nothing on 1.7.10, which has no block states.</li>
 * </ul>
 */
public final class CgBlockStateComponent extends CgHostDrawnComponent<CgBlockStateComponent> {

    private CgBlockState state;
    @Nullable private Object block;

    private CgBlockStateComponent(CgScene scene, CgBlockState state) {
        super(scene);
        this.state = Objects.requireNonNull(state, "state");
    }

    /** A block for {@code scene}, not yet spawned. Throws for a scene Minecraft does not draw. */
    public static CgBlockStateComponent of(CgScene scene, CgBlockState state) {
        return new CgBlockStateComponent(scene, state);
    }

    public CgBlockState block() {
        return state;
    }

    public CgBlockStateComponent block(CgBlockState state) {
        if (!Objects.equals(this.state, state)) {
            this.state = Objects.requireNonNull(state, "state");
            block = null;
        }
        return this;
    }

    @Override
    protected void hostDraw(Matrix4fc model) {
        CgHostWorld.Host host = CgPlatform.get(CgHostWorld.HOST);
        block = host.block(state, block);
        if (block != null) host.drawBlock(block, model);
    }

    @Override
    protected void hostRelease() {
        block = null;
    }
}
