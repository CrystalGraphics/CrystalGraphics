package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.look.CgVfxFlipbook;

/**
 * How an emitter's particles play the frames of their renderers' sheets: Niagara's Sub UV Animation module, Cascade's
 * SubUV and SubUVMovie. The renderer's {@code sub_image} says where the frames are; this says which one a particle shows.
 * An emitter without one shows frame 0, as Unreal's do.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("puffs").subUv(CgVfxSubUvAnimation.linear())...       // first to last over each life
 * CgVfxEmitter.builder("flames").subUv(CgVfxSubUvAnimation.loop(24f).randomStart())...
 * }</pre>
 *
 * <pre>{@code
 * // Each particle's own frame, a curve over life: Cascade's SubUV in Linear interpolation
 * CgVfxEmitter.builder("burst").subUv(CgVfxSubUvAnimation.direct())
 *         .drawn(CgVfxDrawnChannel.SPRITE, CgVfxDrawnChannel.SUB_IMAGE, CgVfxDrawnChannel.over(frames))...
 * }</pre>
 *
 * <ul>
 *   <li>A loop and random run on the particle's age in seconds; linear on its life.</li>
 *   <li>Direct with no sub-image channel shows frame 0.</li>
 * </ul>
 *
 * @param mode        how the frame is picked
 * @param rate        a loop's frames a second, or random's changes a second
 * @param startsRandom each particle's loop at a frame of its own
 */
public record CgVfxSubUvAnimation(CgVfxFlipbook.Mode mode, float rate, boolean startsRandom) {

    /** First to last over each particle's life. */
    public static CgVfxSubUvAnimation linear() {
        return new CgVfxSubUvAnimation(CgVfxFlipbook.Mode.LINEAR, 0f, false);
    }

    /** Round the frames at {@code framesPerSecond}. */
    public static CgVfxSubUvAnimation loop(float framesPerSecond) {
        return new CgVfxSubUvAnimation(CgVfxFlipbook.Mode.LOOP, framesPerSecond, false);
    }

    /** A whole frame at random, drawn again {@code changesPerSecond}; 0 keeps the first. */
    public static CgVfxSubUvAnimation random(float changesPerSecond) {
        return new CgVfxSubUvAnimation(CgVfxFlipbook.Mode.RANDOM, changesPerSecond, false);
    }

    /** The frame each particle carries, its {@code CgVfxDrawnChannel.SUB_IMAGE}. */
    public static CgVfxSubUvAnimation direct() {
        return new CgVfxSubUvAnimation(CgVfxFlipbook.Mode.DIRECT, 0f, false);
    }

    /** This, each particle's loop starting at a frame of its own. */
    public CgVfxSubUvAnimation randomStart() {
        return new CgVfxSubUvAnimation(mode, rate, true);
    }

    /** {@code sheet}'s frames played as this says. */
    public CgVfxFlipbook play(CgVfxFlipbook sheet) {
        CgVfxFlipbook f = switch (mode) {
            case LINEAR -> sheet.linear();
            case LOOP -> sheet.loop(rate);
            case RANDOM -> sheet.random(rate);
            case DIRECT -> sheet.direct();
        };
        return startsRandom ? f.randomStart() : f;
    }
}
