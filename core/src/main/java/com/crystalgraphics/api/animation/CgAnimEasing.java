package com.crystalgraphics.api.animation;

/**
 * How a section's weight rises over its ease-in and falls over its ease-out: Unreal's built-in easing curves
 * ({@code UMovieSceneBuiltInEasingFunction::Evaluate}, MovieSceneEasingCurves.cpp:55-100). A section eases with
 * {@link #CUBIC_IN_OUT} unless told otherwise, as Unreal's sections do (MovieSceneSection.cpp:50-57).
 */
public enum CgAnimEasing {

    LINEAR {
        @Override
        public float evaluate(float t) {
            return t;
        }
    },

    /** Unreal's {@code CubicInOut}: a cube up to half way, its mirror after. */
    CUBIC_IN_OUT {
        @Override
        public float evaluate(float t) {
            float in = t * 2f, out = (t - 0.5f) * 2f;
            return in < 1f ? 0.5f * in * in * in : 0.5f + 0.5f * (1f - (1f - out) * (1f - out) * (1f - out));
        }
    },

    /** Unreal's {@code HermiteCubicInOut}: smoothstep. */
    HERMITE_CUBIC_IN_OUT {
        @Override
        public float evaluate(float t) {
            float x = Math.max(0f, Math.min(1f, t));
            return x * x * (3f - 2f * x);
        }
    };

    /** The curve at {@code t}, 0 to 1. */
    public abstract float evaluate(float t);
}
