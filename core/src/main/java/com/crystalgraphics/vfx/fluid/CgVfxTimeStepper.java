package com.crystalgraphics.vfx.fluid;

/**
 * How many fixed steps a fluid takes this frame and what time is left over: NVIDIA Flow's {@code TimeStepper}
 * (BSD-3-Clause, {@code flow/source/nvflowext/Grid.cpp}). The left-over time is what the frame draws the fluid on by,
 * Flow's {@code interpolateTimeSteps}: the density carried that much further, so slow motion and a frame rate above the
 * step rate move every frame.
 *
 * <pre>{@code
 * CgVfxTimeStepper clock = new CgVfxTimeStepper();      // Flow's TimeStepper_init: 60 steps a second, 1 a frame
 * clock.configure(60f, 2);                              // 60 a second, at most 2 a frame
 * int steps = clock.update(frameSeconds);               // the effect's seconds this frame, its time scale applied
 * float stepSeconds = clock.fixedDt();                  // each step's length
 * float draw = clock.timeError();                       // seconds past the last step, 0 to under fixedDt()
 * }</pre>
 *
 * <ul>
 *   <li>Time owed past {@link #maxTimeError()}, four steps, is dropped: a long hitch never makes the fluid race.</li>
 *   <li>More steps owed than {@link #maxSteps()} carry over to the next frame, within that cap.</li>
 * </ul>
 */
public final class CgVfxTimeStepper {

    private float fixedDt = 1f / 60f, maxTimeError = 4f / 60f, timeError;
    private int maxSteps = 1, numSteps;

    /** Flow's {@code TimeStepper_configure}. */
    public CgVfxTimeStepper configure(float stepsPerSecond, int maxStepsPerFrame) {
        fixedDt = 1f / stepsPerSecond;
        maxTimeError = 4f / stepsPerSecond;
        maxSteps = maxStepsPerFrame;
        return this;
    }

    /** Flow's {@code TimeStepper_update}: the whole steps {@code dt} more seconds make, keeping the rest. */
    public int update(float dt) {
        timeError += dt;
        numSteps = (int) Math.floor(timeError / fixedDt);
        if (numSteps < 0) numSteps = 0;
        if (numSteps > maxSteps) numSteps = maxSteps;
        timeError -= fixedDt * numSteps;
        if (timeError > maxTimeError) timeError = maxTimeError;
        return numSteps;
    }

    public float fixedDt() {
        return fixedDt;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public float maxTimeError() {
        return maxTimeError;
    }

    /** Seconds owed past the steps taken: what the frame draws the fluid on by, never below 0. */
    public float timeError() {
        return Math.max(timeError, 0f);
    }

    /** The steps the last {@link #update} made. */
    public int numSteps() {
        return numSteps;
    }

    /** Starts again with nothing owed. */
    public void reset() {
        timeError = 0f;
        numSteps = 0;
    }
}
