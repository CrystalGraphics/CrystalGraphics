package com.crystalgraphics.mc.modern.platform.actor;

import com.crystalgraphics.mc.model.CgVanillaHumanoid;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
//? if <1.21.3 {
import net.minecraft.client.model.PlayerModel;
//?}
//? if >=1.21.10 {
/*import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.Arrays;
*///?}

/**
 * {@link CgVanillaPoses}' parts set on the game's humanoid models on 1.13.2 to 26.3, once the game has set a model up
 * and before it and its armour draw. Each loader's {@code VanillaPoseHook} calls {@link #posed}; {@link ActorsModern}
 * tells it which entity is drawn. The render thread's; allocates nothing.
 *
 * <ul>
 *   <li>To 1.21.1 the living renderer passes its entity; 1.21.3 to 1.21.8 draw from a state inside the dispatcher's draw
 *       of the entity, which {@link #drawing} names; from 1.21.10 every model is set up from its state after all are
 *       taken, so a posed entity's states are kept as they are taken ({@code extracted}).</li>
 *   <li>Armour copies the body's parts to 1.21.8 and sets itself up from the same state after, so it follows.</li>
 *   <li>To 1.21.1 a player's sleeves, trousers and jacket, and every humanoid's hat, are parts of their own the model
 *       copies from the limbs: copied again here. From 1.21.3 they hang from the limbs.</li>
 * </ul>
 */
@SuppressWarnings("rawtypes")
public final class VanillaPosesModern {

    private static final int STRIDE = CgVanillaHumanoid.STRIDE;
    private static final float[] PARTS = new float[CgVanillaHumanoid.PARTS.length * STRIDE];

    private VanillaPosesModern() {
    }

    /** {@code model}, just set up for entity {@code id}, takes the engine's pose where the engine poses it. */
    public static void posed(int id, Object model) {
        if (!(model instanceof HumanoidModel) || !CgVanillaPoses.parts(id, PARTS)) return;
        HumanoidModel h = (HumanoidModel) model;
        set(h.head, CgVanillaHumanoid.HEAD);
        set(h.body, CgVanillaHumanoid.BODY);
        set(h.rightArm, CgVanillaHumanoid.RIGHT_ARM);
        set(h.leftArm, CgVanillaHumanoid.LEFT_ARM);
        set(h.rightLeg, CgVanillaHumanoid.RIGHT_LEG);
        set(h.leftLeg, CgVanillaHumanoid.LEFT_LEG);
        //? if <1.21.3 {
        copy(h.head, h.hat);
        if (model instanceof PlayerModel) {
            PlayerModel p = (PlayerModel) model;
            copy(p.leftArm, p.leftSleeve);
            copy(p.rightArm, p.rightSleeve);
            copy(p.leftLeg, p.leftPants);
            copy(p.rightLeg, p.rightPants);
            copy(p.body, p.jacket);
        }
        //?}
    }

    private static void set(ModelPart part, int index) {
        int o = index * STRIDE;
        part.x = PARTS[o];
        part.y = PARTS[o + 1];
        part.z = PARTS[o + 2];
        part.xRot = PARTS[o + 3];
        part.yRot = PARTS[o + 4];
        part.zRot = PARTS[o + 5];
    }

    //? if <1.21.3 {
    private static void copy(ModelPart from, ModelPart to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
    }
    //?}

    //? if >=1.21.10 {
    /*/^* The states taken this pass from posed entities, and those entities' ids; a few, scanned. ^/
    private static EntityRenderState[] states = new EntityRenderState[8];
    private static int[] ids = new int[8];
    private static int marks;

    /^* The dispatcher took {@code entity}'s state; {@code newPass} where it is the first taken since a submit. ^/
    static void extracted(Entity entity, EntityRenderState state, boolean newPass) {
        if (newPass) {
            Arrays.fill(states, 0, marks, null);
            marks = 0;
        }
        if (!CgVanillaPoses.posing(entity.getId())) return;
        if (marks == states.length) {
            states = Arrays.copyOf(states, marks * 2);
            ids = Arrays.copyOf(ids, marks * 2);
        }
        states[marks] = state;
        ids[marks++] = entity.getId();
    }

    /^* {@code model} was just set up from {@code state}: posed where the state is a posed entity's. ^/
    public static void posedState(Object model, Object state) {
        for (int i = 0; i < marks; i++) {
            if (states[i] == state) {
                posed(ids[i], model);
                return;
            }
        }
    }
    *///?} else {
    /** The entity the dispatcher is drawing; -1 between draws. */
    private static int drawing = -1;

    /** The dispatcher starts drawing {@code entity}, or ends ({@code null}). */
    static void drawing(Entity entity) {
        drawing = entity == null ? -1 : entity.getId();
    }

    /** {@code model} was just set up for the entity the dispatcher is drawing. */
    public static void posedDrawn(Object model) {
        if (drawing >= 0) posed(drawing, model);
    }
    //?}
}
