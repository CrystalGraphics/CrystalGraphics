package com.crystalgraphics.audio;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.audio.CgHostVolume;

/**
 * Every sound playing, on the frame thread: Unreal's active sounds. Each update weighs them (their gain, their class
 * chain, the sound mixes, the host's slider, whether they are in range), culls each concurrency group by its rule,
 * gives the voices to the loudest by priority ({@code GetSortedActiveWaveInstances}), and keeps a looping sound that
 * lost its voice or left its range virtual, its time running on, to take a voice back where it would be. A placed
 * sound with an attenuation fades, pans, dulls, sends to the reverb and shifts with doppler from the listener.
 *
 * <pre>{@code
 * CgActiveSounds sounds = new CgActiveSounds(64, sink);
 * long id = sounds.play(boom, CgSoundClass.SFX, 1f, 1f, false, Double.NaN);
 * sounds.at(id, x, y, z).attenuation(id, CgSoundAttenuation.DEFAULT);
 * sounds.update(dt, listener, paused, hostVolume);   // once a frame
 * }</pre>
 *
 * <ul>
 *   <li>Frame thread only. Ids stay valid until their sound ends; a stale id is ignored.</li>
 *   <li>Pooled: a play allocates nothing but the voice of a streamed sound.</li>
 *   <li>A one-shot that loses its voice, or starts out of range, stops; a loop goes virtual.</li>
 * </ul>
 */
public final class CgActiveSounds {

    /** What the active sounds drive: voices made and started, their gains and their stops. The engine's command queue. */
    public interface Sink {
        /** A voice for {@code sound} from {@code offset} seconds in. */
        CgAudioSource voice(CgSound sound, float pitch, boolean loop, double offset);

        /** Whether {@code voice} has ended on the audio thread. */
        boolean ended(CgAudioSource voice);

        /** Starts {@code voice} in {@code soundClass}'s submix at audio time {@code at}, or at once where NaN. */
        void start(CgAudioSource voice, float gain, CgSoundClass soundClass, double at);

        void gain(CgAudioSource voice, float gain);

        /** Pans {@code voice} to left and right gains, low-passes it and sends some of it to the reverb. */
        void spatial(CgAudioSource voice, float left, float right, float lowPassHz, float reverbSend);

        /** Scales {@code voice}'s pitch: doppler. */
        void pitch(CgAudioSource voice, float scale);

        void stop(CgAudioSource voice);
    }

    /** Whether a block stands between a sound and the listener: Unreal's occlusion trace. */
    @FunctionalInterface
    public interface Occluder {
        Occluder NONE = (sx, sy, sz, lx, ly, lz) -> false;

        boolean occluded(double sx, double sy, double sz, double lx, double ly, double lz);
    }

    public static final int MAX_SOUNDS = 512;
    private static final byte FREE = 0, NEW = 1, REAL = 2, VIRTUAL = 3;

    private final int maxVoices;
    private final Sink sink;
    private final byte[] state = new byte[MAX_SOUNDS];
    private final int[] generation = new int[MAX_SOUNDS];
    private final CgSound[] sound = new CgSound[MAX_SOUNDS];
    private final CgSoundClass[] soundClass = new CgSoundClass[MAX_SOUNDS];
    private final float[] gain = new float[MAX_SOUNDS], pitch = new float[MAX_SOUNDS], priority = new float[MAX_SOUNDS];
    private final boolean[] loop = new boolean[MAX_SOUNDS], placed = new boolean[MAX_SOUNDS];
    private final double[] x = new double[MAX_SOUNDS], y = new double[MAX_SOUNDS], z = new double[MAX_SOUNDS];
    private final double[] time = new double[MAX_SOUNDS], startAt = new double[MAX_SOUNDS];
    private final CgSoundAttenuation[] attenuation = new CgSoundAttenuation[MAX_SOUNDS];
    /** Where each was last update, for its velocity. */
    private final double[] lastX = new double[MAX_SOUNDS], lastY = new double[MAX_SOUNDS], lastZ = new double[MAX_SOUNDS];
    private final boolean[] moved = new boolean[MAX_SOUNDS];
    /** Occlusion, moved linearly from where it was to where it goes ({@code FDynamicParameter}). */
    private final boolean[] checked = new boolean[MAX_SOUNDS];
    private final double[] lastCheck = new double[MAX_SOUNDS];
    private final float[] occHz = new float[MAX_SOUNDS], occHzFrom = new float[MAX_SOUNDS], occHzTo = new float[MAX_SOUNDS];
    private final float[] occVol = new float[MAX_SOUNDS], occVolFrom = new float[MAX_SOUNDS], occVolTo = new float[MAX_SOUNDS];
    private final float[] occElapsed = new float[MAX_SOUNDS], occDuration = new float[MAX_SOUNDS];
    /** What was last posted of each voice's place: its gains, low-pass, send and pitch. */
    private final float[] sentL = new float[MAX_SOUNDS], sentR = new float[MAX_SOUNDS], sentHz = new float[MAX_SOUNDS];
    private final float[] sentSend = new float[MAX_SOUNDS], sentPitch = new float[MAX_SOUNDS];
    private final float[] lr = new float[2];
    /** A fade toward a level and then a stop ({@code UAudioComponent::FadeOut}): its multiplier, level and rate. */
    private final float[] fade = new float[MAX_SOUNDS], fadeTo = new float[MAX_SOUNDS], fadeRate = new float[MAX_SOUNDS];
    private Occluder occluder = Occluder.NONE;
    private final CgSoundConcurrency[] group = new CgSoundConcurrency[MAX_SOUNDS];
    private final Object[] owner = new Object[MAX_SOUNDS];
    private final long[] order = new long[MAX_SOUNDS];
    private final CgAudioSource[] voice = new CgAudioSource[MAX_SOUNDS];
    private final float[] volume = new float[MAX_SOUNDS], posted = new float[MAX_SOUNDS], weighted = new float[MAX_SOUNDS];
    private final double[] distance = new double[MAX_SOUNDS];
    private final boolean[] grouped = new boolean[MAX_SOUNDS], chosen = new boolean[MAX_SOUNDS];
    private final int[] members = new int[MAX_SOUNDS], ranked = new int[MAX_SOUNDS];
    /** At most this many sound mixes pushed at once. */
    public static final int MAX_MIXES = 16;
    private final CgSoundMix[] mixes = new CgSoundMix[MAX_MIXES];
    private final float[] mixWeight = new float[MAX_MIXES];
    private final boolean[] mixPopping = new boolean[MAX_MIXES];
    private int mixCount;
    /** Each host category's duck now: every pushed mix's, faded by its weight, multiplied. */
    private final float[] hostDuck = new float[CgHostVolume.Category.values().length];
    private long plays;
    private int refused, culled;

    public CgActiveSounds(int maxVoices, Sink sink) {
        this.maxVoices = maxVoices;
        this.sink = sink;
        // From 1, so no id is 0, the refused play's.
        Arrays.fill(generation, 1);
        Arrays.fill(hostDuck, 1f);
    }

    /**
     * Starts {@code s} in {@code cls} at {@code gain} and {@code pitch}, looping or not, at audio time {@code at} (NaN:
     * at once). Its id, or 0 where every slot is taken.
     */
    public long play(CgSound s, CgSoundClass cls, float gain, float pitch, boolean loop, double at) {
        for (int i = 0; i < MAX_SOUNDS; i++) {
            if (state[i] != FREE) continue;
            state[i] = NEW;
            sound[i] = s;
            soundClass[i] = cls;
            this.gain[i] = gain;
            this.pitch[i] = pitch;
            this.loop[i] = loop;
            placed[i] = false;
            attenuation[i] = null;
            moved[i] = false;
            checked[i] = false;
            occHz[i] = occHzTo[i] = CgSoundAttenuation.MAX_FILTER_HZ;
            occVol[i] = occVolTo[i] = 1f;
            occDuration[i] = 0;
            fade[i] = fadeTo[i] = 1f;
            fadeRate[i] = 0f;
            priority[i] = 1f;
            group[i] = null;
            owner[i] = null;
            time[i] = 0;
            startAt[i] = at;
            order[i] = ++plays;
            return (long) generation[i] << 32 | i;
        }
        refused++;
        return 0;
    }

    /** The slot {@code id} names, or -1 for one that ended. */
    private int slot(long id) {
        int i = (int) id;
        return id != 0 && i >= 0 && i < MAX_SOUNDS && state[i] != FREE && generation[i] == (int) (id >>> 32) ? i : -1;
    }

    public boolean playing(long id) {
        return slot(id) >= 0;
    }

    /** Places {@code id} in the world: heard as its {@link #attenuation} says. */
    public CgActiveSounds at(long id, double px, double py, double pz) {
        int i = slot(id);
        if (i >= 0) {
            placed[i] = true;
            x[i] = px;
            y[i] = py;
            z[i] = pz;
        }
        return this;
    }

    /** How {@code id} fades and pans with distance once placed; null, it is heard everywhere at full volume. */
    public CgActiveSounds attenuation(long id, @Nullable CgSoundAttenuation a) {
        int i = slot(id);
        if (i >= 0) attenuation[i] = a;
        return this;
    }

    /**
     * Fades {@code id} to {@code level} over {@code seconds}, then stops it: Unreal's {@code UAudioComponent::FadeOut},
     * a looping cue sound's end. At once where {@code seconds} is 0.
     */
    public void fadeOut(long id, float seconds, float level) {
        int i = slot(id);
        if (i < 0) return;
        if (seconds <= 0) {
            end(i);
            return;
        }
        fadeTo[i] = level;
        fadeRate[i] = (fade[i] - level) / seconds;
    }

    /** What occlusion asks: the engine's traces a ray through the client's blocks. */
    public void occluder(Occluder o) {
        occluder = o;
    }

    /** Its priority: weighed by its volume for a voice, and by {@link CgSoundConcurrency.Rule#STOP_LOWEST_PRIORITY}. */
    public CgActiveSounds priority(long id, float p) {
        int i = slot(id);
        if (i >= 0) priority[i] = p;
        return this;
    }

    public CgActiveSounds group(long id, @Nullable CgSoundConcurrency g) {
        int i = slot(id);
        if (i >= 0) group[i] = g;
        return this;
    }

    /** Whose it is, for a group limited to its owner. */
    public CgActiveSounds owner(long id, @Nullable Object o) {
        int i = slot(id);
        if (i >= 0) owner[i] = o;
        return this;
    }

    public CgActiveSounds gain(long id, float g) {
        int i = slot(id);
        if (i >= 0) gain[i] = g;
        return this;
    }

    public void stop(long id) {
        int i = slot(id);
        if (i >= 0) end(i);
    }

    /** Its voice now; null while it is virtual or not yet started. */
    @Nullable
    public CgAudioSource voice(long id) {
        int i = slot(id);
        return i >= 0 ? voice[i] : null;
    }

    /** Whether it holds no voice now, its time running on. */
    public boolean virtual(long id) {
        int i = slot(id);
        return i >= 0 && state[i] == VIRTUAL;
    }

    /** Where {@code id} is around {@code listener}, as {@link CgAudioListener#azimuth}; NaN where it is not placed. */
    public double azimuth(long id, CgAudioListener listener) {
        int i = slot(id);
        return i >= 0 && placed[i] ? listener.azimuth(x[i], y[i], z[i]) : Double.NaN;
    }

    /** Its occlusion low-pass now, in hertz: 20 kHz while nothing stands between it and the listener. */
    public float occlusionHz(long id) {
        int i = slot(id);
        return i >= 0 ? occHz[i] : CgSoundAttenuation.MAX_FILTER_HZ;
    }

    /** Starts {@code id} {@code seconds} in, before its first update gives it a voice: a lasting sound joined late. */
    public CgActiveSounds startOffset(long id, double seconds) {
        int i = slot(id);
        if (i >= 0 && state[i] == NEW) time[i] = Math.max(0, seconds);
        return this;
    }

    /** The newest sound still playing, or 0: for a test that did not start it itself. */
    long latest() {
        int best = -1;
        for (int i = 0; i < MAX_SOUNDS; i++) if (state[i] != FREE && (best < 0 || order[i] > order[best])) best = i;
        return best < 0 ? 0 : (long) generation[best] << 32 | best;
    }

    /** How many sounds are playing, voiced or virtual. */
    int count() {
        int n = 0;
        for (int i = 0; i < MAX_SOUNDS; i++) if (state[i] != FREE) n++;
        return n;
    }

    /** The audio time it was asked to start at; NaN for at once, or once it ended. */
    public double startsAt(long id) {
        int i = slot(id);
        return i >= 0 ? startAt[i] : Double.NaN;
    }

    /** Seconds of it played, its loops counted. */
    public double time(long id) {
        int i = slot(id);
        return i >= 0 ? time[i] : 0;
    }

    /** Fades {@code mix} in; pushed again while it fades out, it fades back in. */
    public void push(CgSoundMix mix) {
        for (int j = 0; j < mixCount; j++) {
            if (mixes[j] == mix) {
                mixPopping[j] = false;
                return;
            }
        }
        if (mixCount == MAX_MIXES) throw new IllegalStateException("More than " + MAX_MIXES + " sound mixes at once");
        mixes[mixCount] = mix;
        mixWeight[mixCount] = mix.fadeIn() > 0 ? 0f : 1f;
        mixPopping[mixCount++] = false;
    }

    /** Fades {@code mix} out, then drops it. */
    public void pop(CgSoundMix mix) {
        for (int j = 0; j < mixCount; j++) if (mixes[j] == mix) mixPopping[j] = true;
    }

    /** The host's own sounds of {@code category} are lowered to this now, by the pushed mixes; 1 for none. */
    public float hostDuck(CgHostVolume.Category category) {
        return hostDuck[category.ordinal()];
    }

    /** Plays refused for want of a slot. */
    public int refused() {
        return refused;
    }

    /** Sounds stopped by their concurrency groups. */
    public int culled() {
        return culled;
    }

    private void end(int i) {
        if (voice[i] != null) sink.stop(voice[i]);
        voice[i] = null;
        sound[i] = null;
        owner[i] = null;
        group[i] = null;
        state[i] = FREE;
        generation[i]++;
    }

    /** Its class chain, its host slider and the mixes: the master slider is the mixer's main gain, not here. */
    private float classVolume(CgSoundClass c, CgHostVolume host) {
        float v = 1f;
        if (c.host() != null) v *= host.category(c.host());
        for (CgSoundClass k = c; k != null; k = k.parent()) v *= k.volume();
        return v * mixAdjust(c, true);
    }

    private float classPitch(CgSoundClass c) {
        float p = 1f;
        for (CgSoundClass k = c; k != null; k = k.parent()) p *= k.pitch();
        return p * mixAdjust(c, false);
    }

    /** The mixes' adjustment of {@code c}, each faded by its weight, multiplied. */
    private float mixAdjust(CgSoundClass c, boolean ofVolume) {
        float m = 1f;
        for (int j = 0; j < mixCount; j++) {
            float w = mixWeight[j];
            List<CgSoundMix.Adjuster> adjusters = mixes[j].adjusters();
            for (int k = 0; k < adjusters.size(); k++) {
                CgSoundMix.Adjuster a = adjusters.get(k);
                if (!c.within(a.soundClass())) continue;
                m *= 1 + ((ofVolume ? a.volume() : a.pitch()) - 1) * w;
            }
        }
        return m;
    }

    /** Once a frame: the mixes faded, every sound weighed, the groups culled, the voices given. */
    public void update(double dt, CgAudioListener listener, boolean paused, CgHostVolume host) {
        double lx = listener.x(), ly = listener.y(), lz = listener.z();
        for (int j = mixCount - 1; j >= 0; j--) {
            CgSoundMix m = mixes[j];
            boolean popping = mixPopping[j];
            float fade = popping ? m.fadeOut() : m.fadeIn();
            float w = fade <= 0 ? (popping ? 0f : 1f) : mixWeight[j] + (float) (dt / fade) * (popping ? -1 : 1);
            if (popping && w <= 0) {
                mixCount--;
                System.arraycopy(mixes, j + 1, mixes, j, mixCount - j);
                System.arraycopy(mixWeight, j + 1, mixWeight, j, mixCount - j);
                System.arraycopy(mixPopping, j + 1, mixPopping, j, mixCount - j);
                mixes[mixCount] = null;
                continue;
            }
            mixWeight[j] = Math.min(1f, w);
        }
        // The host's categories, as the classes: each mix's duck faded by its weight.
        Arrays.fill(hostDuck, 1f);
        for (int j = 0; j < mixCount; j++) {
            List<CgSoundMix.HostDuck> ducks = mixes[j].hostDucks();
            for (int k = 0; k < ducks.size(); k++) {
                CgSoundMix.HostDuck d = ducks.get(k);
                hostDuck[d.category().ordinal()] *= 1 + (d.volume() - 1) * mixWeight[j];
            }
        }
        for (int i = 0; i < MAX_SOUNDS; i++) {
            grouped[i] = chosen[i] = false;
            if (state[i] == FREE) continue;
            if (state[i] == REAL && !loop[i] && sink.ended(voice[i])) {
                voice[i] = null;
                end(i);
                continue;
            }
            CgSoundClass c = soundClass[i];
            if ((state[i] == REAL || state[i] == VIRTUAL) && !(paused && c.pausesWithGame())) {
                time[i] += dt * pitch[i] * classPitch(c);
            }
            if (state[i] == VIRTUAL && !loop[i] && time[i] >= sound[i].seconds()) {
                end(i);
                continue;
            }
            double dx = x[i] - lx, dy = y[i] - ly, dz = z[i] - lz;
            distance[i] = placed[i] ? Math.sqrt(dx * dx + dy * dy + dz * dz) : 0;
            CgSoundAttenuation a = placed[i] ? attenuation[i] : null;
            if (a != null && a.occlusion()) occlude(i, a, lx, ly, lz, dt);
            if (fadeRate[i] > 0) {
                fade[i] = Math.max(fadeTo[i], fade[i] - (float) dt * fadeRate[i]);
                if (fade[i] <= fadeTo[i]) {
                    end(i);
                    continue;
                }
            }
            float distanceGain = a == null ? 1f : a.gain(distance[i]) * occVol[i];
            volume[i] = gain[i] * classVolume(c, host) * distanceGain * fade[i];
            boolean audible = a == null || distance[i] <= a.maxDistance();
            weighted[i] = audible ? priority[i] * volume[i] : -1;
        }
        cullGroups();
        // GetSortedActiveWaveInstances: the loudest by priority take the voices.
        int n = 0;
        for (int i = 0; i < MAX_SOUNDS; i++) {
            if (state[i] == FREE || weighted[i] < 0) continue;
            int k = n++;
            while (k > 0 && before(i, ranked[k - 1])) {
                ranked[k] = ranked[k - 1];
                k--;
            }
            ranked[k] = i;
        }
        for (int k = 0; k < Math.min(n, maxVoices); k++) chosen[ranked[k]] = true;
        for (int i = 0; i < MAX_SOUNDS; i++) {
            if (state[i] == FREE) continue;
            if (chosen[i]) {
                if (state[i] != REAL) {
                    double offset = loop[i] ? time[i] % Math.max(1e-9, sound[i].seconds()) : time[i];
                    voice[i] = sink.voice(sound[i], pitch[i] * classPitch(soundClass[i]), loop[i], offset);
                    System.out.println("DBG-AU voice " + sound[i].id() + " vol " + volume[i] + " dist " + distance[i] + " paused " + paused + " at " + startAt[i]);
                    sink.start(voice[i], volume[i], soundClass[i], state[i] == NEW ? startAt[i] : Double.NaN);
                    posted[i] = volume[i];
                    state[i] = REAL;
                    sentHz[i] = -1;
                    sentPitch[i] = 1f;
                } else if (Math.abs(volume[i] - posted[i]) > 1e-4f) {
                    sink.gain(voice[i], volume[i]);
                    posted[i] = volume[i];
                }
                if (placed[i] && attenuation[i] != null) place(i, attenuation[i], listener, dt);
            } else if (loop[i]) {
                // A loop below the line or out of range: virtual, its time running on (FAudioVirtualLoop).
                if (state[i] == REAL) sink.stop(voice[i]);
                voice[i] = null;
                state[i] = VIRTUAL;
            } else {
                end(i);
            }
        }
        for (int i = 0; i < MAX_SOUNDS; i++) {
            if (state[i] == FREE || !placed[i]) continue;
            lastX[i] = x[i];
            lastY[i] = y[i];
            lastZ[i] = z[i];
            moved[i] = true;
        }
    }

    /** A voice's pan, low-pass, reverb send and doppler, posted where they moved. */
    private void place(int i, CgSoundAttenuation a, CgAudioListener listener, double dt) {
        if (a.spatialize()) {
            listener.pan(x[i], y[i], z[i], lr);
        } else {
            lr[0] = lr[1] = 1f;
        }
        float hz = Math.min(a.lowPassHz(distance[i]), occHz[i]);
        float s = a.reverbSend(distance[i]);
        if (sentHz[i] < 0 || Math.abs(lr[0] - sentL[i]) > 1e-3f || Math.abs(lr[1] - sentR[i]) > 1e-3f
                || Math.abs(hz - sentHz[i]) > 1f || Math.abs(s - sentSend[i]) > 1e-3f) {
            sink.spatial(voice[i], lr[0], lr[1], hz, s);
            sentL[i] = lr[0];
            sentR[i] = lr[1];
            sentHz[i] = hz;
            sentSend[i] = s;
        }
        if (a.doppler() != 0) {
            double vx = 0, vy = 0, vz = 0;
            if (moved[i] && dt > 0) {
                vx = (x[i] - lastX[i]) / dt;
                vy = (y[i] - lastY[i]) / dt;
                vz = (z[i] - lastZ[i]) / dt;
            }
            float p = listener.doppler(x[i], y[i], z[i], vx, vy, vz, a.doppler());
            if (Math.abs(p - sentPitch[i]) > 1e-4f) {
                sink.pitch(voice[i], p);
                sentPitch[i] = p;
            }
        }
    }

    /** {@code CheckOcclusion}: traced every interval of play, its filter and volume moved over the interpolation. */
    private void occlude(int i, CgSoundAttenuation a, double lx, double ly, double lz, double dt) {
        if (!checked[i] || time[i] - lastCheck[i] > a.occlusionInterval()) {
            boolean occluded = occluder.occluded(x[i], y[i], z[i], lx, ly, lz);
            lastCheck[i] = time[i];
            // The first check lands at once; later ones move over the interpolation time.
            float duration = checked[i] ? a.occlusionInterpolation() : 0f;
            checked[i] = true;
            occHzFrom[i] = occHz[i];
            occVolFrom[i] = occVol[i];
            occHzTo[i] = occluded ? Math.min(occHzTo[i], a.occlusionLowPass()) : CgSoundAttenuation.MAX_FILTER_HZ;
            occVolTo[i] = occluded ? Math.min(occVolTo[i], a.occlusionVolume()) : 1f;
            occElapsed[i] = 0;
            occDuration[i] = duration;
        }
        occElapsed[i] += (float) dt;
        float k = occDuration[i] <= 0 ? 1f : Math.min(1f, occElapsed[i] / occDuration[i]);
        occHz[i] = occHzFrom[i] + (occHzTo[i] - occHzFrom[i]) * k;
        occVol[i] = occVolFrom[i] + (occVolTo[i] - occVolFrom[i]) * k;
    }

    /** Whether sound {@code a} ranks above {@code b} for a voice: weightier, or older at a tie. */
    private boolean before(int a, int b) {
        return weighted[a] != weighted[b] ? weighted[a] > weighted[b] : order[a] < order[b];
    }

    /** Each concurrency group over its count, culled by its rule. */
    private void cullGroups() {
        for (int i = 0; i < MAX_SOUNDS; i++) {
            if (state[i] == FREE || group[i] == null || grouped[i]) continue;
            CgSoundConcurrency g = group[i];
            int n = 0;
            for (int j = i; j < MAX_SOUNDS; j++) {
                if (state[j] == FREE || group[j] != g || grouped[j]) continue;
                if (g.limitToOwner() && owner[j] != owner[i]) continue;
                grouped[j] = true;
                // Kept first: insertion by the rule's order, so the tail is what goes.
                int k = n++;
                while (k > 0 && keeps(g.rule(), j, members[k - 1])) {
                    members[k] = members[k - 1];
                    k--;
                }
                members[k] = j;
            }
            for (int k = g.maxCount(); k < n; k++) {
                culled++;
                end(members[k]);
            }
        }
    }

    /** Whether, under {@code rule}, sound {@code a} is kept before {@code b}. */
    private boolean keeps(CgSoundConcurrency.Rule rule, int a, int b) {
        switch (rule) {
            case PREVENT_NEW:
                return order[a] < order[b];
            case STOP_FARTHEST:
                if (distance[a] != distance[b]) return distance[a] < distance[b];
                return order[a] > order[b];
            case STOP_QUIETEST:
                // At equal volume a newer sound goes first, so a loop does not ping-pong.
                return volume[a] != volume[b] ? volume[a] > volume[b] : order[a] < order[b];
            case STOP_LOWEST_PRIORITY:
                return priority[a] != priority[b] ? priority[a] > priority[b] : order[a] > order[b];
            case STOP_OLDEST:
            default:
                return order[a] > order[b];
        }
    }
}
