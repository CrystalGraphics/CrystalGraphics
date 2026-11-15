package com.crystalgraphics.animation.sync;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * Something that plays an asset's time: a sequence player, a blend space player. Unreal's {@code FAnimNode_AssetPlayerBase}
 * and its relevancy interface. It records itself into its instance's {@link CgAnimSync} during the update; the sync
 * ticks it after the walk, leader first.
 *
 * <pre>{@code
 * // in a player's update, at the weight it is relevant at
 * sync.add(this, weight, group);
 * // the sync, after the walk
 * player.tick(ctx, canUseMarkerSync);
 * }</pre>
 *
 * <ul>
 *   <li>A state machine's {@code time_remaining} reads its relevant player's {@link #currentAssetLength} and
 *       {@link #currentAssetTimePlayRateAdjusted}; its automatic rule reads {@link #playLength} and
 *       {@link #accumulatedTime}.</li>
 *   <li>A blend space's times are normalised: its play length is 1.</li>
 * </ul>
 */
public interface CgAnimAssetPlayer {

    /**
     * {@code TickAssetPlayer}: advances its time by the context, leading or following.
     *
     * @param canUseMarkerSync whether its group syncs it by markers ({@code bCanUseMarkerSync})
     */
    void tick(CgAnimTickContext ctx, boolean canUseMarkerSync);

    /** {@code GetUniqueMarkerNames}: the sync marker names it plays, each once, or null for none. */
    @Nullable
    String[] uniqueMarkers();

    /** What it plays now: a group resets a member whose asset changed. */
    @Nullable
    Object asset();

    /** Its place among its markers, kept between frames. */
    CgMarkerTickRecord markerRecord();

    /** {@code GetCachedBlendWeight}: its weight in this frame's update. */
    float weight();

    /** Its asset's play length: a clip's seconds, a blend space's 1. */
    float playLength();

    /** {@code GetAccumulatedTime}: its time, in its play length's units. */
    float accumulatedTime();

    /** Its time before its last tick ({@code FDeltaTimeRecord::GetPrevious}). */
    float previousTime();

    /** Its last tick's move ({@code FDeltaTimeRecord::Delta}). */
    float lastDelta();

    boolean looping();

    float playRate();

    /** {@code GetCurrentAssetLength}: a clip's length; a blend space's heaviest sample's. */
    float currentAssetLength();

    /** {@code GetCurrentAssetTimePlayRateAdjusted}: its time in seconds, counted from the end when playing backwards. */
    float currentAssetTimePlayRateAdjusted();

    /**
     * Each clip it samples after its tick, at its time and its share of {@code weight}: what the curves sum. A sequence
     * player gives one; a blend space each relevant sample.
     */
    void forEachSample(float weight, SampleSink sink);

    /** Hears a clip a player samples this frame. */
    @FunctionalInterface
    interface SampleSink {
        void sample(CgAnimSequence clip, float time, float weight);
    }
}
