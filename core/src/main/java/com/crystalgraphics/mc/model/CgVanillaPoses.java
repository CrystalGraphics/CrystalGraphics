package com.crystalgraphics.mc.model;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.CgAnimation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

import java.util.Arrays;

/**
 * The entities whose vanilla model the engine poses: each one's {@link CgAnimInstance} over
 * {@link CgVanillaHumanoid#MESH}, posed every frame in {@code POSE} with every other instance, and handed to the game
 * as part angles when it sets the entity's model up. The game still draws the model, its armour and what it holds.
 *
 * <pre>{@code
 * CgAnimInstance zombie = CgVanillaPoses.play(entity.getId());     // posed each frame from now on
 * zombie.play(clips.resolve("punch"), false);                      // a clip of CgVanillaHumanoid.SKELETON's bones
 * CgVanillaPoses.stop(entity.getId());                             // the game's own animation again
 * }</pre>
 *
 * <pre>{@code
 * // a host, once its renderer has set the entity's model up, before the model and its armour draw (render thread)
 * if (CgVanillaPoses.parts(entityId, parts)) for (int i = 0; i < 6; i++) set(model.part(i), parts, i * CgVanillaHumanoid.STRIDE);
 * }</pre>
 *
 * <p>Every era's host does this: after the living renderer's model setup to 1.21.8, after each submitted model's setup
 * from 1.21.10 (its armour's too), and after {@code ModelBiped}'s angles on 1.8.9 to 1.12.2 and 1.7.10. Forge 1.13.2 and
 * 1.14.3 load no mixins, so their models stay the game's.</p>
 *
 * <ul>
 *   <li>The frame thread's: {@code play}, {@code stop} and {@code parts} run where the game renders.</li>
 *   <li>Every call sets every part again: one model object draws every entity of its renderer, so a pose left on it
 *       would be the next entity's.</li>
 *   <li>A level change drops every instance ({@link CgAnimation}'s rule), and with it the entity's pose.</li>
 *   <li>A host that sets its models from {@link #parts} says so with {@link #hosted()}; where none has, {@link #play}
 *       logs once that the game draws the entity unposed.</li>
 * </ul>
 */
public final class CgVanillaPoses {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private static int[] ids = new int[4];
    private static CgAnimInstance[] instances = new CgAnimInstance[4];
    private static int count;
    private static boolean hosted, warned;

    private CgVanillaPoses() {
    }

    /** A host applies {@link #parts} in its model setup: called once as it starts. */
    public static void hosted() {
        hosted = true;
    }

    /** The instance entity {@code id}'s model is posed by, made and added to the client's animation if it had none. */
    public static CgAnimInstance play(int id) {
        if (!hosted && !warned) {
            warned = true;
            LOG.warn("[crystalgraphics] vanilla poses: this host sets no model from them; entity {} is drawn unposed", id);
        }
        int at = find(id);
        if (at >= 0 && instances[at].posed()) return instances[at];
        CgAnimInstance instance = CgAnimation.client().add(new CgAnimInstance(CgVanillaHumanoid.MESH));
        if (at >= 0) {
            instances[at] = instance;
            return instance;
        }
        if (count == ids.length) {
            ids = Arrays.copyOf(ids, count * 2);
            instances = Arrays.copyOf(instances, count * 2);
        }
        ids[count] = id;
        instances[count++] = instance;
        return instance;
    }

    /** Hands entity {@code id}'s model back to the game's own animation. */
    public static void stop(int id) {
        int at = find(id);
        if (at < 0) return;
        CgAnimation.client().remove(instances[at]);
        count--;
        ids[at] = ids[count];
        instances[at] = instances[count];
        instances[count] = null;
    }

    /** The instance posing entity {@code id}'s model; null where the engine poses it not. */
    @Nullable
    public static CgAnimInstance instance(int id) {
        int at = count == 0 ? -1 : find(id);
        return at < 0 ? null : instances[at];
    }

    /** Whether the engine poses entity {@code id}'s model: a host marks its render state as it is taken. */
    public static boolean posing(int id) {
        return count > 0 && find(id) >= 0;
    }

    /**
     * Entity {@code id}'s parts as the game sets them ({@link CgVanillaHumanoid#parts}) into {@code out}, of
     * {@link CgVanillaHumanoid#PARTS}{@code .length * STRIDE} floats; false where the engine poses it not. Allocates
     * nothing.
     */
    public static boolean parts(int id, float[] out) {
        if (count == 0) return false;
        int at = find(id);
        if (at < 0 || !instances[at].posed()) return false;
        CgVanillaHumanoid.parts(instances[at].pose(), out);
        return true;
    }

    private static int find(int id) {
        for (int i = 0; i < count; i++) if (ids[i] == id) return i;
        return -1;
    }
}
