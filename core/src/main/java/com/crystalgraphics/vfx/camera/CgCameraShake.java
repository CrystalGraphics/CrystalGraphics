package com.crystalgraphics.vfx.camera;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;

/**
 * A camera shake, defined once and played wherever it happens: what an explosion, a recoil or a rumbling charge feels
 * like, felt by how far the camera stands from it. Every playing shake is summed into the host's camera on the trauma
 * model (Eiserloh, "Juicing Your Cameras With Math"), so hits stack and settle on their own. Immutable and shared; the
 * presets are in {@link CgCameraShakes}.
 *
 * <pre>{@code
 * // A blast: felt fully within its radius and not at all past five, arriving with its shock front.
 * static final CgCameraShake BLAST = CgCameraShake.builder()
 *         .trauma(1f).punch(1.5f).tremor(0.85f, 4f).kick(0.14f, 0.5f)
 *         .radii(1f, 5f).arrives(2.6f, 0.8f, CgEasings.OUT_CUBIC)
 *         .build();
 * BLAST.play(x, y, z, blastRadius);                  // radii and the front in units of blastRadius
 *
 * CgCameraShakes.RECOIL.play(x, y, z, -aimX, -aimY, -aimZ, 1f);   // shoved back along the aim
 *
 * // Held: its trauma times a level set every tick while it lasts.
 * CgCameraShake.Held charge = CgCameraShakes.RUMBLE.hold();
 * charge.at(x, y, z).level(progress * progress);
 * charge.close();                                    // or stop setting it: it lapses within a quarter second
 * }</pre>
 *
 * <p>A shake is up to four parts, each felt by the same radii:</p>
 * <ul>
 *   <li><b>trauma</b> 0..1, decaying {@link #DECAY} a second: the camera turns and moves by trauma squared through
 *       noise, a slow lurch under a fast rattle. Trauma 1 turns it about ten degrees; 0.3 a tenth of that.</li>
 *   <li><b>punch</b>: shoved away from the point (or along the direction played with) on a spring that swings back past
 *       rest. A punch of 1 turns it five degrees and moves it a third of a block at its peak.</li>
 *   <li><b>tremor</b>: trauma held from the start and tapering to none over its seconds, outliving whatever played it.</li>
 *   <li><b>kick</b>: the field of view widened at once (0.1 is 10%), easing back over its seconds.</li>
 * </ul>
 *
 * <ul>
 *   <li>A held shake uses its trauma alone, and stops when its level is not set for a quarter second.</li>
 *   <li>A shake that {@link Builder#arrives arrives} is felt by each camera when its front reaches it, or when the
 *       front's time is up for a camera beyond it.</li>
 *   <li>The player's Screen Effects and FOV Effects scale it, then CrystalGraphics' comfort settings, which can only
 *       lower it. Nothing moves where the host fills no camera slot.</li>
 *   <li>Play and hold on the render thread; playing allocates nothing.</li>
 * </ul>
 */
public final class CgCameraShake {

    /** Trauma lost a second: a full shake settles in a little over a second. */
    public static final float DECAY = CgShakeModel.DECAY;

    final float trauma, punch, tremor, tremorSeconds, kick, kickSeconds, inner, outer, falloff, reach, arrival;
    final CgEasing ease;

    private CgCameraShake(Builder b) {
        trauma = b.trauma;
        punch = b.punch;
        tremor = b.tremor;
        tremorSeconds = b.tremorSeconds;
        kick = b.kick;
        kickSeconds = b.kickSeconds;
        inner = b.inner;
        outer = b.outer;
        falloff = b.falloff;
        reach = b.reach;
        arrival = b.arrival;
        ease = b.ease;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder starting from this shake: how a preset is varied. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.trauma = trauma;
        b.punch = punch;
        b.tremor = tremor;
        b.tremorSeconds = tremorSeconds;
        b.kick = kick;
        b.kickSeconds = kickSeconds;
        b.inner = inner;
        b.outer = outer;
        b.falloff = falloff;
        b.reach = reach;
        b.arrival = arrival;
        b.ease = ease;
        return b;
    }

    /** Plays it at the point, its radii in blocks. */
    public void play(double x, double y, double z) {
        play(x, y, z, 1f);
    }

    /** Plays it at the point, its radii and front in units of {@code scale} blocks: an effect's own size. */
    public void play(double x, double y, double z, float scale) {
        CgShakeRuntime.play(this, x, y, z, Double.NaN, 0.0, 0.0, scale);
    }

    /** As {@link #play(double, double, double, float)}, its punch shoving along {@code (dx, dy, dz)}. */
    public void play(double x, double y, double z, double dx, double dy, double dz, float scale) {
        CgShakeRuntime.play(this, x, y, z, dx, dy, dz, scale);
    }

    /** A held shake: nothing until its level is set, then its trauma times that level while it keeps being set. */
    public Held hold() {
        return new Held(this, CgShakeRuntime.MODEL);
    }

    /** The decaying trauma now, 0..1, without what is held. */
    public static float trauma() {
        return CgShakeRuntime.MODEL.trauma();
    }

    /** Whether anything has shaken: from then shakes own the host camera's offset, and write it every frame. */
    public static boolean active() {
        return CgShakeRuntime.installed();
    }

    public static final class Builder {

        private float trauma, punch, tremor, tremorSeconds, kick, kickSeconds, inner = 1f, outer = 5f, falloff = 1f;
        private float reach, arrival;
        private CgEasing ease = CgEasings.LINEAR;

        private Builder() {
        }

        /** Trauma added at once, 0..1. */
        public Builder trauma(float trauma) {
            this.trauma = trauma;
            return this;
        }

        /** The shove's strength, 1 at full. */
        public Builder punch(float punch) {
            this.punch = punch;
            return this;
        }

        /** Trauma held from the start, tapering to none over {@code seconds}. */
        public Builder tremor(float trauma, float seconds) {
            this.tremor = trauma;
            this.tremorSeconds = seconds;
            return this;
        }

        /** The field of view widened by {@code amount} (0.1 is 10%) at once, easing back over {@code seconds}. */
        public Builder kick(float amount, float seconds) {
            this.kick = amount;
            this.kickSeconds = seconds;
            return this;
        }

        /** Felt fully within {@code inner} and not at all past {@code outer}, in units of the scale it is played at. */
        public Builder radii(float inner, float outer) {
            this.inner = inner;
            this.outer = outer;
            return this;
        }

        /** The fade between the radii raised to {@code exponent}: above 1 falls away faster past the inner radius. */
        public Builder falloff(float exponent) {
            this.falloff = exponent;
            return this;
        }

        /**
         * Felt when a front racing out from the point reaches the camera: {@code reach} (in units of the scale) after
         * {@code seconds}, eased by {@code ease}. Match it to the effect's visible shock front.
         */
        public Builder arrives(float reach, float seconds, CgEasing ease) {
            this.reach = reach;
            this.arrival = seconds;
            this.ease = ease;
            return this;
        }

        /** Felt everywhere at once: the default. */
        public Builder atOnce() {
            this.arrival = 0f;
            return this;
        }

        public CgCameraShake build() {
            return new CgCameraShake(this);
        }
    }

    /** A held shake from {@link #hold()}: a place, a scale, and a level its owner sets every tick. */
    public static final class Held {

        private final CgCameraShake shake;
        private final CgShakeModel model;
        double x, y, z, touched;
        float level, inner, outer;
        boolean closed;

        Held(CgCameraShake shake, CgShakeModel model) {
            this.shake = shake;
            this.model = model;
            scale(1f);
        }

        public CgCameraShake shake() {
            return shake;
        }

        public Held at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** Its radii in units of {@code scale} blocks. */
        public Held scale(float scale) {
            inner = shake.inner * scale;
            outer = Math.max(shake.outer * scale, inner + 1e-3f);
            return this;
        }

        /** Its trauma times {@code level} (0..1) while held. Call it every tick: unset for a quarter second, it lapses. */
        public Held level(float level) {
            CgShakeRuntime.install();
            this.level = level * shake.trauma;
            this.touched = model.now();
            this.closed = false;
            model.hold(this);
            return this;
        }

        /** Stops it now. */
        public void close() {
            closed = true;
        }
    }
}
