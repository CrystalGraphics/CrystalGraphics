package com.crystalgraphics.mc.render.world;

import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4fc;

import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.component.CgHostDrawnComponent;

/**
 * An item stack in a scene, drawn by Minecraft's item renderer as it draws a held or dropped item: a mod's item model,
 * its custom renderer and its glint show as in the game.
 *
 * <pre>{@code
 * CgItemStackComponent sword = CgItemStackComponent.of(CgScene.client(), CgItemStack.of("mymod:blade", 1))
 *         .display(CgItemDisplay.THIRD_PERSON_RIGHT_HAND)   // the model's own hand transform
 *         .attach(body, "hand_r")                           // rides the socket, as any primitive
 *         .spawn();
 * sword.stack(sword.stack().withDamage(40));                // the next frame draws the new stack
 * }</pre>
 *
 * <ul>
 *   <li>Lit by Minecraft's light at its position, and by the engine's lights as Minecraft's entities are.</li>
 *   <li>An item the game does not know draws nothing.</li>
 * </ul>
 */
public final class CgItemStackComponent extends CgHostDrawnComponent<CgItemStackComponent> {

    private CgItemStack stack;
    private CgItemDisplay display = CgItemDisplay.NONE;
    @Nullable private Object hostStack;

    private CgItemStackComponent(CgScene scene, CgItemStack stack) {
        super(scene);
        this.stack = Objects.requireNonNull(stack, "stack");
    }

    /** A stack for {@code scene}, not yet spawned. Throws for a scene Minecraft does not draw. */
    public static CgItemStackComponent of(CgScene scene, CgItemStack stack) {
        return new CgItemStackComponent(scene, stack);
    }

    public CgItemStack stack() {
        return stack;
    }

    public CgItemStackComponent stack(CgItemStack stack) {
        if (!Objects.equals(this.stack, stack)) {
            this.stack = Objects.requireNonNull(stack, "stack");
            hostStack = null;
        }
        return this;
    }

    public CgItemDisplay display() {
        return display;
    }

    public CgItemStackComponent display(CgItemDisplay display) {
        this.display = Objects.requireNonNull(display, "display");
        return this;
    }

    @Override
    protected void hostDraw(Matrix4fc model) {
        CgHostWorld.Host host = CgPlatform.get(CgHostWorld.HOST);
        hostStack = host.item(stack, hostStack);
        if (hostStack != null) host.drawItem(hostStack, display, model);
    }

    @Override
    protected void hostRelease() {
        hostStack = null;
    }
}
