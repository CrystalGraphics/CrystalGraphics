package com.crystalgraphics.audio;

/**
 * At most so many sounds of a group playing at once, the rest resolved by a rule: Unreal's {@code USoundConcurrency}.
 * The brawl's hit sparks are one group, so their bursts never crowd out what else plays.
 *
 * <pre>{@code
 * static final CgSoundConcurrency SPARKS = new CgSoundConcurrency(6, CgSoundConcurrency.Rule.STOP_OLDEST);
 * long id = audio.play(spark, CgSoundClass.SFX, 0.6f);
 * audio.group(id, SPARKS);
 * }</pre>
 *
 * <ul>
 *   <li>{@link Rule#PREVENT_NEW} refuses a new sound; {@link Rule#STOP_OLDEST} and {@link Rule#STOP_FARTHEST} stop one
 *       to make room; {@link Rule#STOP_QUIETEST} and {@link Rule#STOP_LOWEST_PRIORITY} stop the least at the next
 *       update.</li>
 *   <li>{@link #limitToOwner}: the count is per owner, each fighter its own six.</li>
 * </ul>
 */
public final class CgSoundConcurrency {

    /** {@code EMaxConcurrentResolutionRule}. */
    public enum Rule { PREVENT_NEW, STOP_OLDEST, STOP_FARTHEST, STOP_QUIETEST, STOP_LOWEST_PRIORITY }

    private final int maxCount;
    private final Rule rule;
    private boolean limitToOwner;

    public CgSoundConcurrency(int maxCount, Rule rule) {
        if (maxCount < 1) throw new IllegalArgumentException("a group holds at least one sound, not " + maxCount);
        this.maxCount = maxCount;
        this.rule = rule;
    }

    public CgSoundConcurrency limitToOwner(boolean limit) {
        limitToOwner = limit;
        return this;
    }

    public int maxCount() {
        return maxCount;
    }

    public Rule rule() {
        return rule;
    }

    public boolean limitToOwner() {
        return limitToOwner;
    }
}
