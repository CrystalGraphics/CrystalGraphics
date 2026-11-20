package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgPaintChunk;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.api.canvas.CgCanvasLayer;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.text.CgTextAlign;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.i18n.CgKitStrings;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.text.style.CgTextStyle;

/**
 * One live use of a {@link CgWidgetTree}: UMG's {@code UUserWidget}. It holds what changes per use (a text block's
 * text, a widget's visibility or brush, what a sequence writes), lays the tree out by Slate's rules when one of those
 * changes, and paints its leaves onto a {@link CgCanvas}. It is a sequence target: a widget animation binds
 * the instance as its role and finds each part by name, as UMG's {@code FindRuntimeObject} does in the widget tree.
 *
 * <pre>{@code
 * CgUserWidget pop = BATTLE_DMG.loaded().newInstance();      // pooled: once per slot, not per hit
 * pop.text("Number_Center", "1,250");                         // relays out at the next paint
 * pop.visibility("Pnl_Lang_Critical", critical ? CgWidget.Visibility.VISIBLE : CgWidget.Visibility.COLLAPSED);
 * CgSequencer.widgets(world).play(CRITICAL_TEXT.loaded(), Map.of("number", pop));
 *
 * // each frame: the screen in UI units, as AddToViewport sizes it; every retainer box retained before any paints
 * float scale = CgUiScale.of(kit).scale(screenWidth, screenHeight);
 * pop.allot(screenWidth / scale, screenHeight / scale);
 * canvas.save().translate(x, y).scale(scale, scale);
 * pop.paint(canvas);
 * canvas.restore();
 * }</pre>
 *
 * <pre>{@code
 * // a hot path: look the index up once, then set by it
 * int number = tree.index("Number_Center");
 * pop.text(number, digits);
 * pop.reset();                                                // back to the designer's values, for the pool
 * }</pre>
 *
 * <ul>
 *   <li>A name the tree lacks fails, by name: a setter here, a track binding it at play.</li>
 *   <li>What a sequence writes is render-only (transform, opacity, colours, material parameters) and never relays
 *       out; text, visibility and brushes do.</li>
 *   <li>A nested user widget is reached by its name as a whole: a track on it writes that instance's own transform,
 *       opacity and colour, as its own sequences do; never a widget inside it.</li>
 *   <li>Its size is its root's desired size unless {@link #allot} gives one.</li>
 *   <li>A string id is read in the player's language at layout; a language change shows at the next relayout.</li>
 *   <li>One thread at a time; paint allocates nothing.</li>
 * </ul>
 */
public final class CgUserWidget implements CgSequenceTarget {

    private static final int TRANSLATION = 0;
    private static final int SCALE = 1;
    private static final int SHEAR = 2;
    private static final int ANGLE = 3;
    private static final int RENDER_OPACITY = 4;
    private static final int COLOUR = 5;
    private static final int CONTENT_COLOUR = 6;
    private static final int MATERIAL_PARAMETERS = 7;
    private static final int BLUR = 8;
    private static final int SLOTS = 9;
    private static final String TEXT_ID = ":text/";

    private final CgWidgetTree tree;
    private final int n;
    // Per widget, and the user widget itself at index n: SLOTS x 4 floats each, colours linear.
    private final float[] state;
    private final float[] designer;
    private final float selfPivotX;
    private final float selfPivotY;
    private final CgWidget.Visibility[] visibility;
    private final String[] text;
    private final CgTextStyle[] style;
    private final String[] shown;
    /** A text block's text as laid out at its last layout, and its x in its box per justification. */
    private final CgText[] laid;
    private final float[] laidInset;
    /** Per widget: its brush's material instance, the widget's own (UMG's dynamic material instance). */
    private final CgMaterialInstance[] materials;
    private final CgMaterialDefinition[] materialOf;
    private final boolean[] takesParameters;
    /** Per retainer box: the filter it was last drawn through, and what made it. */
    private final CgFilter[] filters;
    private final float[] filterKey;

    private final CgWidgetBrush[] brush;
    private final Part[] parts;
    private final CgUserWidget[] nested;

    private final float[] desired;
    private final float[] position;
    private final float[] size;
    private final Vector2f measured = new Vector2f();
    private float allottedWidth = Float.NaN;
    private float allottedHeight = Float.NaN;
    private boolean dirty = true;
    /** Per widget, itself at n: a change has moved it (render transform) or faded it, so it draws in a node of its own. */
    private final boolean[] moves;
    private final boolean[] fades;
    /** Per border: its content colour's alpha has changed, so its content draws in an effect node. */
    private final boolean[] contentFades;
    /** Per widget, itself at n: its nodes as last recorded, as the chunk numbered them. */
    private final int[] nodeOf;
    private final int[] effectOf;
    private final int[] contentEffectOf;
    /** Per widget: it or something under it draws in a node, so a replay's pose walk enters it. */
    private final boolean[] posed;
    /** Its paint, kept to replay while {@link #revision} and the scale baked under its root node hold. */
    private final CgPaintChunk kept = new CgPaintChunk();
    /** Bumped by every change a replay cannot show: layout, text, a brush, visibility, a colour, material parameters. */
    private long revision;
    private long keptRevision = -1;
    /** Refused chunks in a row at {@link #refusedAt}, and the repaint before which it takes none again. */
    private int refusals;
    private long refusedAt = -1;
    private long retryAt;
    private float keptScaleX;
    private float keptScaleY;
    /** No retainer box anywhere in it: a layer opens a pass, which a chunk refuses. */
    private final boolean chunkable;
    private long repaints;
    private final float[] affine = new float[6];
    /** Its animations, one entry each however often played. @see #playAnimation */
    private final List<Animation> animations = new ArrayList<>(2);
    @Nullable
    private CgWorld world;

    CgUserWidget(CgWidgetTree tree, @Nullable CgWidget as) {
        this.tree = tree;
        n = tree.size();
        state = new float[(n + 1) * SLOTS * 4];
        visibility = new CgWidget.Visibility[n];
        text = new String[n];
        style = new CgTextStyle[n];
        shown = new String[n];
        laid = new CgText[n];
        laidInset = new float[n];
        materials = new CgMaterialInstance[n];
        materialOf = new CgMaterialDefinition[n];
        takesParameters = new boolean[n];
        filters = new CgFilter[n];
        filterKey = new float[n * 5];
        brush = new CgWidgetBrush[n];
        parts = new Part[n];
        nested = new CgUserWidget[n];
        for (int i = 0; i < n; i++) {
            CgWidget w = tree.widget(i);
            designer(i, w);
            parts[i] = new Part(i);
            if (w.kind == CgWidget.Kind.USER_WIDGET) nested[i] = new CgUserWidget(w.widget, w);
        }
        if (as != null) {
            designer(n, as);
            selfPivotX = as.pivotX;
            selfPivotY = as.pivotY;
        } else {
            designer(n, null);
            selfPivotX = selfPivotY = 0.5f;
        }
        designer = state.clone();
        restore();
        desired = new float[n * 2];
        position = new float[n * 2];
        size = new float[n * 2];
        moves = new boolean[n + 1];
        fades = new boolean[n + 1];
        contentFades = new boolean[n];
        nodeOf = new int[n + 1];
        effectOf = new int[n + 1];
        contentEffectOf = new int[n];
        posed = new boolean[n];
        boolean plain = true;
        for (int i = 0; i < n; i++) {
            if (tree.widget(i).kind == CgWidget.Kind.RETAINER_BOX || nested[i] != null && !nested[i].chunkable) plain = false;
        }
        chunkable = plain;
    }

    /** Widget {@code i}'s designer values into its state; {@code w} null for an untransformed, white whole. */
    private void designer(int i, @Nullable CgWidget w) {
        int at = i * SLOTS * 4;
        float[] t = w != null ? w.transform : new float[]{0f, 0f, 1f, 1f, 0f, 0f, 0f};
        state[at + TRANSLATION * 4] = t[0];
        state[at + TRANSLATION * 4 + 1] = t[1];
        state[at + SCALE * 4] = t[2];
        state[at + SCALE * 4 + 1] = t[3];
        state[at + SHEAR * 4] = t[4];
        state[at + SHEAR * 4 + 1] = t[5];
        state[at + ANGLE * 4] = t[6];
        state[at + RENDER_OPACITY * 4] = w != null ? w.renderOpacity : 1f;
        putColour(at + COLOUR * 4, w != null ? w.colour : CgLinearColour.WHITE);
        putColour(at + CONTENT_COLOUR * 4, w != null ? w.contentColour : CgLinearColour.WHITE);
        state[at + BLUR * 4] = w != null ? w.blur : 0f;
    }

    private void restore() {
        for (int i = 0; i < n; i++) {
            CgWidget w = tree.widget(i);
            visibility[i] = w.visibility;
            text[i] = w.text;
            style[i] = w.style;
            brush[i] = w.brush;
            laid[i] = null;
        }
    }

    /** Its tree. */
    public CgWidgetTree tree() {
        return tree;
    }

    // ── Animations: UUserWidget::PlayAnimation and its kin ─────────────────────────────────────────────────────────

    /**
     * Plays {@code animation} on this widget, every role it names bound to this instance, on its world's widget
     * sequencer: UMG's {@code PlayAnimation}. Played again while it plays, it restarts from its start on the same
     * player, as UMG's {@code GetOrAddPlayer} reuses one per animation ({@code UserWidget.cpp:609-670}).
     *
     * <pre>{@code
     * widget.playAnimation(POP.loaded());                           // every hit: restarts, never stacks
     * widget.onAnimationFinished(FADE.loaded(), layer::remove...);  // once, before or after it plays
     * widget.playAnimation(FADE.loaded());
     * }</pre>
     *
     * <ul>
     *   <li>A finished animation keeps what it wrote and retires, as UMG's does: another on the same properties then
     *       writes them alone.</li>
     *   <li>Its world is its layer's ({@link CgScreenWidgetLayer}, {@link CgWorldWidgetLayer}), else the client's.</li>
     *   <li>Several animations play at once on different properties; two writing one property blend as the sequencer
     *       blends them.</li>
     * </ul>
     */
    public CgSequencePlayer playAnimation(CgSequenceDefinition animation) {
        return playAnimationTo(animation, 0, Double.NaN, 1f);
    }

    /**
     * Plays {@code animation} from {@code start} seconds, ending and holding at {@code end}, at {@code speed} times its
     * own: UMG's {@code PlayAnimationTo} ({@code UserWidget.cpp:674}, {@code PlayAnimationTimeRange} in later
     * versions). One animation keyed through several states then plays only the stretch between two of them.
     *
     * <pre>{@code
     * // a ramp keyed at each grade's point: play from wherever it is to the new grade's
     * double from = widget.isAnimationPlaying(RAMP) ? widget.animationCurrentTime(RAMP) : previous.at();
     * widget.playAnimationTo(RAMP, from, grade.at(), 1f);
     *
     * // the same, catching up: however far behind it is, it arrives in one step's time
     * widget.playAnimationTo(RAMP, from, grade.at(), (float) Math.max(1, (grade.at() - from) / STEP));
     * }</pre>
     *
     * <ul>
     *   <li>Each call sets the speed, as UMG's {@code PlayInternal} does: a replay at 1 is never left at an older speed.</li>
     *   <li>Plays forward: an {@code end} before {@code start} plays on to the animation's end, as UMG's does.</li>
     *   <li>NaN, or an {@code end} at or past the animation's end, plays to its end: {@link #playAnimation}.</li>
     *   <li>Reaching {@code end} is its finish: {@link #onAnimationFinished} runs, and it retires keeping that frame.</li>
     * </ul>
     */
    public CgSequencePlayer playAnimationTo(CgSequenceDefinition animation, double start, double end, float speed) {
        Animation a = animation(animation);
        if (a.player != null && !a.player.stopped()) return a.player.jumpTo(start).endAt(end).rate(speed);
        Map<String, CgSequenceTarget> roles = new HashMap<>();
        for (CgSequenceDefinition.Role role : animation.roles()) roles.put(role.name(), this);
        a.player = CgSequencer.widgets(world != null ? world : CgWorld.client()).play(animation, roles);
        a.player.onFinished(a::finished);
        if (start != 0) a.player.jumpTo(start);
        return a.player.endAt(end).rate(speed);
    }

    /**
     * Where {@code animation} is, in seconds, while it plays; 0 when it is not playing or has finished. UMG's
     * {@code GetAnimationCurrentTime} ({@code UserWidget.cpp:740}), which asks only its active players.
     */
    public double animationCurrentTime(CgSequenceDefinition animation) {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.definition == animation) return a.player != null && !a.player.stopped() ? a.player.seconds() : 0;
        }
        return 0;
    }

    /**
     * Stops {@code animation} mid-play, putting back what it wrote. Nothing for one not playing or finished. UMG's
     * {@code StopAnimation}.
     */
    public void stopAnimation(CgSequenceDefinition animation) {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.definition == animation && a.player != null) {
                a.player.stop();
                a.player = null;
            }
        }
    }

    /** Stops every animation playing on it: UMG's {@code StopAllAnimations}. */
    public void stopAllAnimations() {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.player != null) {
                a.player.stop();
                a.player = null;
            }
        }
    }

    /** Whether {@code animation} is playing and has not reached its end: UMG's {@code IsAnimationPlaying}. */
    public boolean isAnimationPlaying(CgSequenceDefinition animation) {
        for (int i = 0; i < animations.size(); i++) {
            Animation a = animations.get(i);
            if (a.definition == animation) return a.player != null && !a.player.stopped() && !a.player.finished();
        }
        return false;
    }

    /**
     * Runs {@code listener} each time {@code animation} reaches its end on this widget, its last frame applied: UMG's
     * {@code BindToAnimationFinished}. Bound once; it holds for every later play, and through {@link #reset()}.
     */
    public CgUserWidget onAnimationFinished(CgSequenceDefinition animation, Runnable listener) {
        animation(animation).listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** The world its animations play on: its layer's. */
    void world(@Nullable CgWorld world) {
        this.world = world;
    }

    private Animation animation(CgSequenceDefinition definition) {
        for (int i = 0; i < animations.size(); i++) if (animations.get(i).definition == definition) return animations.get(i);
        Animation a = new Animation(Objects.requireNonNull(definition, "animation"));
        animations.add(a);
        return a;
    }

    /** One animation of this widget: its player while it plays, and who hears it finish. */
    private static final class Animation {
        final CgSequenceDefinition definition;
        final List<Runnable> listeners = new ArrayList<>(1);
        @Nullable
        CgSequencePlayer player;

        Animation(CgSequenceDefinition definition) {
            this.definition = definition;
        }

        void finished() {
            for (int i = 0; i < listeners.size(); i++) listeners.get(i).run();
        }
    }

    /**
     * Puts every widget back to the designer's values, nested ones too, and stops its animations: for a pool, before
     * the next use. What it bound with {@link #onAnimationFinished} stays bound.
     */
    public void reset() {
        stopAllAnimations();
        System.arraycopy(designer, 0, state, 0, state.length);
        restore();
        for (CgUserWidget w : nested) if (w != null) w.reset();
        allottedWidth = allottedHeight = Float.NaN;
        dirty = true;
        revision++;
    }

    /** Lays it out at {@code width} x {@code height} widget units rather than its root's desired size. */
    public CgUserWidget allot(float width, float height) {
        if (width != allottedWidth || height != allottedHeight) {
            allottedWidth = width;
            allottedHeight = height;
            dirty = true;
        }
        return this;
    }

    /** The text block {@code name}'s text: a string id ({@code <ns>:text/<id>}) or the literal text. */
    public CgUserWidget text(String name, String value) {
        return text(require(name), value);
    }

    /** Widget {@code index}'s text. */
    public CgUserWidget text(int index, String value) {
        if (tree.widget(index).kind != CgWidget.Kind.TEXT_BLOCK) {
            throw new IllegalArgumentException(tree.widget(index).name + " is no text block");
        }
        if (!value.equals(text[index])) {
            text[index] = value;
            dirty = true;
        }
        return this;
    }

    /** The text block {@code name}'s style: UMG's {@code SetTextStyle}; relays out at the next paint. */
    public CgUserWidget style(String name, CgTextStyle value) {
        return style(require(name), value);
    }

    public CgUserWidget style(int index, CgTextStyle value) {
        if (tree.widget(index).kind != CgWidget.Kind.TEXT_BLOCK) {
            throw new IllegalArgumentException(tree.widget(index).name + " is no text block");
        }
        if (style[index] != value) {
            style[index] = value;
            dirty = true;
        }
        return this;
    }

    /**
     * Widget {@code name}'s colour and opacity, multiplied into what it draws: UMG's {@code SetColorAndOpacity} on an
     * image, a text block or a user widget. Never relays out; an animation keying it starts from this.
     *
     * <pre>{@code
     * counter.colourAndOpacity("Banner", CgLinearColour.argb(0xFFFF8410));   // the banner dyed the grade's orange
     * }</pre>
     */
    public CgUserWidget colourAndOpacity(String name, CgLinearColour colour) {
        Objects.requireNonNull(colour, "colour");
        set4(require(name), COLOUR, colour.r(), colour.g(), colour.b(), colour.a());
        return this;
    }

    /**
     * Widget {@code name}'s material parameters: four values its brush's UI material reads as its {@code vec4}
     * property {@code _Parameters}, on an instance this widget owns, as a dynamic material instance's are in UMG. Zero
     * until set; an animation keys them as {@code material_parameters} and starts from these. Never relays out.
     *
     * <pre>{@code
     * counter.materialParameters("Streak", 0.5f, 1.2f, 0f, 0f);   // its shader: float energy = _Parameters.x;
     * }</pre>
     */
    public CgUserWidget materialParameters(String name, float x, float y, float z, float w) {
        set4(require(name), MATERIAL_PARAMETERS, x, y, z, w);
        return this;
    }

    /**
     * Retainer box {@code name}'s blur: a Gaussian sigma in units over what it holds, as CSS's {@code filter: blur()};
     * 0 for none. An animation keys it as {@code blur} and starts from this. Never relays out.
     *
     * <pre>{@code
     * counter.blur("NumberBlur", 4f);   // the count, soft
     * }</pre>
     *
     * Blurring runs a compute pass: GL 4.3 and up, or a device.
     */
    public CgUserWidget blur(String name, float sigma) {
        int i = require(name);
        if (tree.widget(i).kind != CgWidget.Kind.RETAINER_BOX) {
            throw new IllegalArgumentException(name + " is no retainer box: only one blurs what it holds");
        }
        set4(i, BLUR, sigma, 0f, 0f, 0f);
        return this;
    }

    /** Widget {@code index}'s render translation, in units: UMG's {@code SetRenderTranslation}. Never relays out. */
    public CgUserWidget translation(int index, float x, float y) {
        set4(index, TRANSLATION, x, y, 0f, 0f);
        return this;
    }

    public CgUserWidget visibility(String name, CgWidget.Visibility value) {
        return visibility(require(name), value);
    }

    public CgUserWidget visibility(int index, CgWidget.Visibility value) {
        if (visibility[index] != value) {
            visibility[index] = value;
            dirty = true;
        }
        return this;
    }

    /** An image's brush or a border's background: UMG's {@code SetBrush}. */
    public CgUserWidget brush(String name, CgWidgetBrush value) {
        return brush(require(name), value);
    }

    public CgUserWidget brush(int index, CgWidgetBrush value) {
        if (!value.equals(brush[index])) {
            brush[index] = value;
            dirty = true;
        }
        return this;
    }

    /** The user widget {@code name}'s instance, nested in this one. */
    public CgUserWidget nested(String name) {
        CgUserWidget w = nested[require(name)];
        if (w == null) throw new IllegalArgumentException(name + " is no user widget");
        return w;
    }

    public String text(int index) {
        return text[index];
    }

    public CgWidget.Visibility visibility(int index) {
        return visibility[index];
    }

    /** Widget {@code index}'s colour and opacity as a sequence leaves it, render opacity not multiplied in. */
    public CgLinearColour colour(int index) {
        int at = index * SLOTS * 4 + COLOUR * 4;
        return new CgLinearColour(state[at], state[at + 1], state[at + 2], state[at + 3]);
    }

    /** Widget {@code index}'s render transform into {@code out}: translation x, y, scale x, y, shear x, y, angle. */
    public float[] transform(int index, float[] out) {
        int at = index * SLOTS * 4;
        out[0] = state[at];
        out[1] = state[at + 1];
        out[2] = state[at + SCALE * 4];
        out[3] = state[at + SCALE * 4 + 1];
        out[4] = state[at + SHEAR * 4];
        out[5] = state[at + SHEAR * 4 + 1];
        out[6] = state[at + ANGLE * 4];
        return out;
    }

    /** Widget {@code index}'s material parameters as a sequence leaves them, into {@code out}: x, y, z, w. */
    public float[] materialParameters(int index, float[] out) {
        System.arraycopy(state, index * SLOTS * 4 + MATERIAL_PARAMETERS * 4, out, 0, 4);
        return out;
    }

    /** The filter retainer box {@code name} was last drawn through: its blur or effect, then its tint. Null before. */
    CgFilter retainerFilter(String name) {
        return filters[require(name)];
    }

    /** Widget {@code index}'s box in its parent after the last layout, into {@code out}: x, y, width, height. */
    public float[] box(int index, float[] out) {
        out[0] = position[index * 2];
        out[1] = position[index * 2 + 1];
        out[2] = size[index * 2];
        out[3] = size[index * 2 + 1];
        return out;
    }

    /**
     * Lays it out if anything it reads changed, measuring its text; its size into {@code out}: the allotted, else its
     * root's desired.
     */
    public Vector2f layout(Vector2f out) {
        layoutIfDirty();
        return out.set(rootWidth(), rootHeight());
    }

    /**
     * Paints it onto {@code canvas} with its box at the canvas's origin now, in widget units: lays out if needed, then
     * each visible leaf in tree and z order. The canvas's transform places and scales it.
     *
     * <pre>{@code
     * canvas.save().translate(x, y).scale(scale, scale);
     * widget.paint(canvas);
     * canvas.restore();
     * }</pre>
     */
    public void paint(CgCanvas canvas) {
        layoutIfDirty();
        long key = deepRevision();
        canvas.transform(affine);
        // What stays baked under its root node: an axis-aligned scale (CgCanvas.movable)
        boolean aligned = affine[1] == 0f && affine[2] == 0f && affine[0] > 0f && affine[3] > 0f;
        float sx = aligned ? affine[0] : 1f, sy = aligned ? affine[3] : 1f;
        if (key == keptRevision && sx == keptScaleX && sy == keptScaleY && canvas.replay(kept)) {
            canvas.save();
            poseAt(canvas, kept);
            canvas.restore();
            return;
        }
        repaints++;
        if (key != refusedAt) refusals = 0;
        if (!chunkable || refusals > 0 && repaints < retryAt || canvas.chunking()) {
            paintAt(canvas, 1f, 1f, 1f, 1f);
            return;
        }
        canvas.beginChunk();
        paintAt(canvas, 1f, 1f, 1f, 1f);
        CgPaintChunk.Refusal why = canvas.endChunk(kept);
        keptRevision = why == CgPaintChunk.Refusal.NONE ? key : -1;
        keptScaleX = sx;
        keptScaleY = sy;
        // What a widget refuses is transient: glyphs generating, an SVG's cells rasterised at their first use. Tried
        // again at once, then less often, up to every 64th paint
        if (why == CgPaintChunk.Refusal.NONE) {
            refusals = 0;
        } else {
            refusedAt = key;
            retryAt = repaints + (1L << Math.min(refusals++, 6));
        }
    }

    /** How often it has been painted rather than replayed: an animation that only moves and fades it adds none. */
    public long repaints() {
        return repaints;
    }

    /** Paints it with its box at the canvas's origin, under a parent's tint (linear). */
    private void paintAt(CgCanvas c, float r, float g, float b, float a) {
        layoutIfDirty();
        int self = n * SLOTS * 4;
        c.save();
        // Its root is a node, so a replay places it wherever it is drawn
        renderAffine(self, selfPivotX * rootWidth(), selfPivotY * rootHeight());
        nodeOf[n] = c.movable(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        // SObjectWidget is a compound widget: the user widget's colour and opacity tints its content.
        float own = state[self + RENDER_OPACITY * 4] * state[self + COLOUR * 4 + 3];
        if (fades[n]) effectOf[n] = c.fader(own);
        else a *= own;
        int k = self + COLOUR * 4;
        if (n > 0) paint(c, 0, r * state[k], g * state[k + 1], b * state[k + 2], a);
        c.restore();
    }

    /** Paints widget {@code i} and what it holds; whether any of it draws in a node. */
    private boolean paint(CgCanvas c, int i, float r, float g, float b, float a) {
        if (!visibility[i].draws()) return posed[i] = false;
        CgWidget w = tree.widget(i);
        int at = i * SLOTS * 4;
        float width = size[i * 2], height = size[i * 2 + 1];
        c.save();
        c.translate(position[i * 2], position[i * 2 + 1]);
        if (w.kind == CgWidget.Kind.USER_WIDGET) {
            nested[i].paintAt(c, r, g, b, a);
            c.restore();
            return posed[i] = true;
        }
        boolean identity = renderAffine(at, w.pivotX * width, w.pivotY * height);
        if (moves[i]) nodeOf[i] = c.movable(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        else if (!identity) c.concat(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        // SWidget::Paint: its render opacity into what it paints itself and its children with; a leaf's colour too
        int k = at + COLOUR * 4;
        float own = state[at + RENDER_OPACITY * 4] * (leaf(w) ? state[k + 3] : 1f);
        if (fades[i]) effectOf[i] = c.fader(own);
        else a *= own;
        boolean nodes = moves[i] || fades[i];
        switch (w.kind) {
            case IMAGE -> {
                CgWidgetBrush br = brush[i];
                if (br.draws()) {
                    // SImage: style tint x colour and opacity x brush tint.
                    CgLinearColour t = br.tint();
                    image(c, i, br, width, height, r * state[k] * t.r(), g * state[k + 1] * t.g(),
                            b * state[k + 2] * t.b(), a * t.a());
                }
            }
            case TEXT_BLOCK -> text(c, i, w, width, r * state[k], g * state[k + 1], b * state[k + 2], a);
            case BORDER -> {
                CgWidgetBrush br = brush[i];
                if (br.draws()) {
                    // SBorder: brush tint x style tint x brush colour.
                    CgLinearColour t = br.tint(), bc = w.brushColour;
                    image(c, i, br, width, height, r * t.r() * bc.r(), g * t.g() * bc.g(), b * t.b() * bc.b(),
                            a * t.a() * bc.a());
                }
                // SCompoundWidget: its content colour into its content's style.
                int cc = at + CONTENT_COLOUR * 4;
                if (contentFades[i]) {
                    c.save();
                    contentEffectOf[i] = c.fader(state[cc + 3]);
                    paintChildren(c, i, r * state[cc], g * state[cc + 1], b * state[cc + 2], a);
                    c.restore();
                    nodes = true;
                } else {
                    nodes |= paintChildren(c, i, r * state[cc], g * state[cc + 1], b * state[cc + 2], a * state[cc + 3]);
                }
            }
            case SCALE_BOX -> {
                // FGeometry::MakeChild's layout scale: everything inside is laid out and drawn in its scaled space.
                c.scale(w.userScale, w.userScale);
                nodes |= paintChildren(c, i, r, g, b, a);
            }
            case RETAINER_BOX -> retainer(c, i, w, at, width, height, r, g, b, a);
            default -> nodes |= paintChildren(c, i, r, g, b, a);
        }
        c.restore();
        return posed[i] = nodes;
    }

    private boolean paintChildren(CgCanvas c, int i, float r, float g, float b, float a) {
        boolean nodes = false;
        for (int ch : tree.paintOrder(i)) nodes |= paint(c, ch, r, g, b, a);
        return nodes;
    }

    /** Whether it draws itself in its colour: an image or a text block. */
    private static boolean leaf(CgWidget w) {
        return w.kind == CgWidget.Kind.IMAGE || w.kind == CgWidget.Kind.TEXT_BLOCK;
    }

    /** A replay's pose walk from its root: its nodes' values from its state now, nothing drawn. */
    private void poseAt(CgCanvas c, CgPaintChunk chunk) {
        int self = n * SLOTS * 4;
        c.save();
        renderAffine(self, selfPivotX * rootWidth(), selfPivotY * rootHeight());
        c.moved(chunk.node(nodeOf[n]), affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        if (fades[n]) {
            c.nodeOpacity(chunk.effect(effectOf[n]), state[self + RENDER_OPACITY * 4] * state[self + COLOUR * 4 + 3]);
        }
        if (n > 0) pose(c, chunk, 0);
        c.restore();
    }

    /** Widget {@code i}'s part of the pose walk, as {@link #paint(CgCanvas, int, float, float, float, float)} placed it. */
    private void pose(CgCanvas c, CgPaintChunk chunk, int i) {
        if (!posed[i]) return;
        CgWidget w = tree.widget(i);
        int at = i * SLOTS * 4;
        c.save();
        c.translate(position[i * 2], position[i * 2 + 1]);
        if (w.kind == CgWidget.Kind.USER_WIDGET) {
            nested[i].poseAt(c, chunk);
            c.restore();
            return;
        }
        boolean identity = renderAffine(at, w.pivotX * size[i * 2], w.pivotY * size[i * 2 + 1]);
        if (moves[i]) c.moved(chunk.node(nodeOf[i]), affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        else if (!identity) c.concat(affine[0], affine[1], affine[2], affine[3], affine[4], affine[5]);
        if (fades[i]) {
            c.nodeOpacity(chunk.effect(effectOf[i]),
                    state[at + RENDER_OPACITY * 4] * (leaf(w) ? state[at + COLOUR * 4 + 3] : 1f));
        }
        if (w.kind == CgWidget.Kind.SCALE_BOX) c.scale(w.userScale, w.userScale);
        if (w.kind == CgWidget.Kind.BORDER && contentFades[i]) {
            c.nodeOpacity(chunk.effect(contentEffectOf[i]), state[at + CONTENT_COLOUR * 4 + 3]);
        }
        for (int ch : tree.paintOrder(i)) pose(c, chunk, ch);
        c.restore();
    }

    /**
     * A brush over (0, 0) to ({@code width}, {@code height}), tinted in linear: Slate encodes the colour once
     * ({@code PackVertexColor}); a UI material reads it linear, as Slate's material shaders do.
     */
    private void image(CgCanvas c, int i, CgWidgetBrush br, float width, float height, float r, float g, float b,
                       float a) {
        if (a <= 0f) return;
        if (br.material() != null) {
            c.box(0f, 0f, width, height).material(material(i, br.material())).fill(linear(r, g, b, a)).draw();
            return;
        }
        int argb = CgLinearColour.argb(r, g, b, a);
        if (br.svg() != null) {
            // Rasterised at the size it lands on the target, its currentColor the tint
            CgSvg doc = CgSvg.of(br.svg());
            if (doc != null) c.box(0f, 0f, width, height).fill(CgBrush.svg(doc)).tint(argb).draw();
            return;
        }
        if (br.texture() != null) {
            // Shared by its path: loaded at its first draw, kept
            c.box(0f, 0f, width, height).fill(CgTextures.of(br.texture())).tint(argb).draw();
            return;
        }
        // A brush with no texture or material: a box of its tint, as Slate's
        c.fill(0f, 0f, width, height, argb);
    }

    /** A text block's text in a box {@code width} units wide, justified, tinted. */
    private void text(CgCanvas c, int i, CgWidget w, float width, float r, float g, float b, float a) {
        CgText t = laid[i];
        if (a <= 0f || t == null || shown[i].isEmpty()) return;
        float x = 0f;
        if (t.layout() != null) {
            // The layout justifies its lines across the wrap width; place the widest line's box in the widget's
            float f = switch (w.justification) {
                case CENTRE -> 0.5f;
                case RIGHT -> 1f;
                case LEFT -> 0f;
            };
            float total = t.layout().totalWidth();
            x = (width - total) * f - laidInset[i] * f;
        }
        c.text(t, x, 0f, CgLinearColour.argb(r, g, b, a));
    }

    /**
     * SRetainerWidget: its content drawn into a picture of its own at full opacity, blurred or drawn through its effect
     * material, then laid over at the tint everything above it gives.
     */
    private void retainer(CgCanvas c, int i, CgWidget w, int at, float width, float height, float r, float g, float b,
                          float a) {
        if (a <= 0f) return;
        float reach = 0f;
        for (int ch : tree.children(i)) reach = Math.max(reach, reach(ch, 1f));
        try (CgCanvasLayer layer = c.layer(-reach, -reach, width + 2f * reach, height + 2f * reach)
                .opacity(a).filter(filter(i, w, at, r, g, b))) {
            for (int ch : tree.children(i)) {
                if (contains(ch, CgWidget.Kind.RETAINER_BOX)) {
                    throw new IllegalStateException(w.name + ": a retainer box inside another is not drawn");
                }
                paint(c, ch, 1f, 1f, 1f, 1f);
            }
        }
    }

    /** A retainer box's filter: its effect material or its blur, then its tint's colour; kept while they hold. */
    private CgFilter filter(int i, CgWidget w, int at, float r, float g, float b) {
        float blur = state[at + BLUR * 4];
        int o = i * 5;
        CgFilter f = filters[i];
        if (f != null && filterKey[o] == blur && filterKey[o + 1] == r && filterKey[o + 2] == g && filterKey[o + 3] == b) {
            return f;
        }
        if (w.effect != null) {
            f = CgFilter.material(CgMaterial.of(w.effect), w.textureParameter);
        } else {
            CgWidget.BlurAxis axis = w.blurAxis;
            float sx = axis == CgWidget.BlurAxis.VERTICAL ? 0f : blur, sy = axis == CgWidget.BlurAxis.HORIZONTAL ? 0f : blur;
            f = blur > 0f ? CgFilter.blur(sx, sy) : CgFilter.NONE;
        }
        if (r != 1f || g != 1f || b != 1f) {
            // The tint's colour, encoded as the composite's vertex colour was: a premultiplied picture's rgb scaled
            int argb = CgLinearColour.argb(r, g, b, 1f);
            float sr = (argb >>> 16 & 0xFF) / 255f, sg = (argb >>> 8 & 0xFF) / 255f, sb = (argb & 0xFF) / 255f;
            f = f.then(CgFilter.matrix(new float[]{sr, 0, 0, 0, 0, 0, sg, 0, 0, 0, 0, 0, sb, 0, 0, 0, 0, 0, 1, 0}));
        }
        filters[i] = f;
        filterKey[o] = blur;
        filterKey[o + 1] = r;
        filterKey[o + 2] = g;
        filterKey[o + 3] = b;
        return f;
    }

    /** How far widget {@code i}'s text draws past its boxes, its styles' reach under {@code scale}: a scale box's. */
    private float reach(int i, float scale) {
        CgWidget w = tree.widget(i);
        float reach = w.kind == CgWidget.Kind.TEXT_BLOCK && style[i] != null ? style[i].reach() * scale : 0f;
        float inner = w.kind == CgWidget.Kind.SCALE_BOX ? scale * w.userScale : scale;
        for (int c : tree.children(i)) reach = Math.max(reach, reach(c, inner));
        return reach;
    }

    private boolean contains(int i, CgWidget.Kind kind) {
        if (tree.widget(i).kind == kind) return true;
        for (int c : tree.children(i)) if (contains(c, kind)) return true;
        return false;
    }

    /**
     * Slate's render transform about the pivot ({@code px}, {@code py}) into {@link #affine}, as
     * {@link CgCanvas#concat} takes it; whether it is the identity. {@code Geometry.h}'s order.
     */
    private boolean renderAffine(int at, float px, float py) {
        float sx = state[at + SCALE * 4], sy = state[at + SCALE * 4 + 1];
        float kx = shear(state[at + SHEAR * 4]), ky = shear(state[at + SHEAR * 4 + 1]);
        float angle = state[at + ANGLE * 4];
        float tx = state[at + TRANSLATION * 4], ty = state[at + TRANSLATION * 4 + 1];
        // FWidgetTransform::ToSlateRenderTransform: scale, then shear, then rotation, then translation.
        double rad = Math.toRadians(angle);
        float cos = angle == 0f ? 1f : (float) Math.cos(rad), sin = angle == 0f ? 0f : (float) Math.sin(rad);
        float ka = sx, kb = ky * sx, kc = kx * sy, kd = sy;
        float la = cos * ka - sin * kb, lb = sin * ka + cos * kb;
        float lc = cos * kc - sin * kd, ld = sin * kc + cos * kd;
        affine[0] = la;
        affine[1] = lb;
        affine[2] = lc;
        affine[3] = ld;
        affine[4] = px + tx - (la * px + lc * py);
        affine[5] = py + ty - (lb * px + ld * py);
        return tx == 0f && ty == 0f && sx == 1f && sy == 1f && kx == 0f && ky == 0f && angle == 0f;
    }

    /** Widget {@code i}'s brush material as its own instance, its {@code _Parameters} this frame's. */
    private CgMaterialInstance material(int i, CgMaterialDefinition definition) {
        CgMaterialInstance m = materials[i];
        if (m == null || materialOf[i] != definition) {
            m = materials[i] = CgMaterial.of(definition).instance();
            materialOf[i] = definition;
            takesParameters[i] = false;
            for (CgMaterialProperty p : m.material().properties()) {
                if (p.getName().equals(PARAMETERS)) takesParameters[i] = true;
            }
        }
        if (takesParameters[i]) {
            int at = i * SLOTS * 4 + MATERIAL_PARAMETERS * 4;
            m.vec4(PARAMETERS, state[at], state[at + 1], state[at + 2], state[at + 3]);
        }
        return m;
    }

    /** What a brush's UI material reads a widget's {@code material_parameters} as. */
    public static final String PARAMETERS = "_Parameters";

    /** Linear channels packed as they are, unencoded: what a UI material reads. */
    private static int linear(float r, float g, float b, float a) {
        return byteOf(a) << 24 | byteOf(r) << 16 | byteOf(g) << 8 | byteOf(b);
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    // ---- layout: Slate's desired sizes bottom up, then each panel arranging its children ----

    private boolean needsLayout() {
        if (dirty) return true;
        for (CgUserWidget w : nested) if (w != null && w.needsLayout()) return true;
        return false;
    }

    private void layoutIfDirty() {
        if (!needsLayout()) return;
        desireAll();
        if (n > 0) {
            position[0] = position[1] = 0f;
            size[0] = Float.isNaN(allottedWidth) ? desired[0] : allottedWidth;
            size[1] = Float.isNaN(allottedHeight) ? desired[1] : allottedHeight;
        }
        for (int i = 0; i < n; i++) arrange(i);
        dirty = false;
        revision++;
    }

    private void desireAll() {
        for (int i = n - 1; i >= 0; i--) desire(i);
    }

    private float rootWidth() {
        return n == 0 ? 0f : size[0];
    }

    private float rootHeight() {
        return n == 0 ? 0f : size[1];
    }

    private void desire(int i) {
        CgWidget w = tree.widget(i);
        float dx = 0f, dy = 0f;
        if (visibility[i] != CgWidget.Visibility.COLLAPSED) {
            switch (w.kind) {
                case IMAGE -> {
                    dx = brush[i].width();
                    dy = brush[i].height();
                }
                case TEXT_BLOCK -> {
                    shown[i] = resolve(text[i]);
                    lay(i, w);
                    dx = measured.x;
                    dy = measured.y;
                }
                case USER_WIDGET -> {
                    CgUserWidget inner = nested[i];
                    inner.desireAll();
                    if (inner.n > 0) {
                        dx = inner.desired[0];
                        dy = inner.desired[1];
                    }
                }
                case BORDER, RETAINER_BOX -> {
                    // SCompoundWidget: its child and its padding.
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        dx = desired[c * 2] + tree.padding[c * 4] + tree.padding[c * 4 + 2];
                        dy = desired[c * 2 + 1] + tree.padding[c * 4 + 1] + tree.padding[c * 4 + 3];
                    }
                }
                case SCALE_BOX -> {
                    // SScaleBox::ComputeDesiredSize at a user scale: SCompoundWidget's, times it.
                    float s = w.userScale;
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        dx = (desired[c * 2] + tree.padding[c * 4] + tree.padding[c * 4 + 2]) * s;
                        dy = (desired[c * 2 + 1] + tree.padding[c * 4 + 1] + tree.padding[c * 4 + 3]) * s;
                    }
                }
                case HORIZONTAL_BOX -> {
                    // SBoxPanel's ComputeDesiredSizeForBox.
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        dx += desired[c * 2] + tree.padding[c * 4] + tree.padding[c * 4 + 2];
                        dy = Math.max(dy, desired[c * 2 + 1] + tree.padding[c * 4 + 1] + tree.padding[c * 4 + 3]);
                    }
                }
                case CANVAS -> {
                    // SConstraintCanvas::ComputeDesiredSize.
                    float[] an = tree.anchors, off = tree.offsets;
                    for (int c : tree.children(i)) {
                        if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
                        float sx = tree.autoSize[c] ? desired[c * 2] : off[c * 4 + 2];
                        float sy = tree.autoSize[c] ? desired[c * 2 + 1] : off[c * 4 + 3];
                        boolean dockX = an[c * 4] == an[c * 4 + 2] && (an[c * 4] == 0f || an[c * 4] == 1f);
                        boolean dockY = an[c * 4 + 1] == an[c * 4 + 3] && (an[c * 4 + 1] == 0f || an[c * 4 + 1] == 1f);
                        dx = Math.max(dx, sx + (dockX ? Math.abs(off[c * 4]) : 0f));
                        dy = Math.max(dy, sy + (dockY ? Math.abs(off[c * 4 + 1]) : 0f));
                    }
                }
            }
        }
        desired[i * 2] = dx;
        desired[i * 2 + 1] = dy;
    }

    /** A string id in the player's language, else the text itself (Unreal shows a missing string's key). */
    private static String resolve(String text) {
        if (text.indexOf(TEXT_ID) <= 0) return text;
        String s = CgKitStrings.text(text);
        return s != null ? s : text;
    }

    private void arrange(int i) {
        CgWidget w = tree.widget(i);
        float width = size[i * 2], height = size[i * 2 + 1];
        switch (w.kind) {
            case CANVAS -> {
                // SConstraintCanvas::ArrangeLayeredChildren.
                float[] an = tree.anchors, off = tree.offsets, al = tree.alignment;
                for (int c : tree.children(i)) {
                    float sx = tree.autoSize[c] ? desired[c * 2] : off[c * 4 + 2];
                    float sy = tree.autoSize[c] ? desired[c * 2 + 1] : off[c * 4 + 3];
                    canvasAxis(c, 0, an[c * 4] * width, an[c * 4 + 2] * width, off[c * 4], off[c * 4 + 2], sx, al[c * 2]);
                    canvasAxis(c, 1, an[c * 4 + 1] * height, an[c * 4 + 3] * height, off[c * 4 + 1], off[c * 4 + 3], sy,
                            al[c * 2 + 1]);
                }
            }
            case HORIZONTAL_BOX -> arrangeBox(i, width, height);
            case BORDER, RETAINER_BOX -> {
                // ArrangeSingleChild.
                for (int c : tree.children(i)) {
                    align(c, 0, width, desired[c * 2], tree.horizontal[c], tree.padding[c * 4], tree.padding[c * 4 + 2]);
                    align(c, 1, height, desired[c * 2 + 1], tree.vertical[c], tree.padding[c * 4 + 1],
                            tree.padding[c * 4 + 3]);
                }
            }
            case SCALE_BOX -> {
                // SScaleBox::OnArrangeChildren: aligned at its scaled size, then placed in the scaled space the
                // paint applies (the offset over the scale), at its own desired size, or the area's over the scale
                // along an axis it fills.
                float s = w.userScale;
                for (int c : tree.children(i)) {
                    float pl = tree.padding[c * 4], pt = tree.padding[c * 4 + 1];
                    float pr = tree.padding[c * 4 + 2], pb = tree.padding[c * 4 + 3];
                    align(c, 0, width, desired[c * 2] * s, tree.horizontal[c], pl, pr);
                    align(c, 1, height, desired[c * 2 + 1] * s, tree.vertical[c], pt, pb);
                    position[c * 2] /= s;
                    position[c * 2 + 1] /= s;
                    size[c * 2] = tree.horizontal[c] == CgWidgetSlot.Align.FILL ? width / s : desired[c * 2];
                    size[c * 2 + 1] = tree.vertical[c] == CgWidgetSlot.Align.FILL ? height / s : desired[c * 2 + 1];
                }
            }
            case USER_WIDGET -> nested[i].allot(width, height).layoutIfDirty();
            default -> {
            }
        }
    }

    private void canvasAxis(int c, int axis, float anchorMin, float anchorMax, float offMin, float offMax, float s,
                            float alignment) {
        if (anchorMin != anchorMax) {
            position[c * 2 + axis] = anchorMin + offMin;
            size[c * 2 + axis] = anchorMax - position[c * 2 + axis] - offMax;
        } else {
            position[c * 2 + axis] = anchorMin + offMin - s * alignment;
            size[c * 2 + axis] = s;
        }
    }

    /** {@code SBoxPanel}'s {@code ArrangeChildrenAlong}, left to right: auto children their desired size, fill children the rest. */
    private void arrangeBox(int i, float width, float height) {
        float fixed = 0f, coefficients = 0f;
        boolean any = false;
        for (int c : tree.children(i)) {
            if (visibility[c] == CgWidget.Visibility.COLLAPSED) continue;
            any = true;
            fixed += tree.padding[c * 4] + tree.padding[c * 4 + 2];
            if (tree.fills[c]) coefficients += tree.fill[c];
            else fixed += desired[c * 2];
        }
        if (!any) return;
        float free = Math.max(0f, width - fixed);
        float so = 0f;
        for (int c : tree.children(i)) {
            boolean collapsed = visibility[c] == CgWidget.Visibility.COLLAPSED;
            float child = 0f;
            if (!collapsed) {
                child = tree.fills[c] ? (coefficients > 0f ? free * tree.fill[c] / coefficients : 0f) : desired[c * 2];
            }
            float padL = tree.padding[c * 4], padR = tree.padding[c * 4 + 2];
            float slot = child + padL + padR;
            align(c, 0, slot, desired[c * 2], tree.horizontal[c], padL, padR);
            position[c * 2] += so;
            align(c, 1, height, desired[c * 2 + 1], tree.vertical[c], tree.padding[c * 4 + 1], tree.padding[c * 4 + 3]);
            if (!collapsed) so += slot;
        }
    }

    /** {@code LayoutUtils.h}'s {@code AlignChild}: offset and size along one axis, clamped to the slot. */
    private void align(int c, int axis, float allotted, float childDesired, CgWidgetSlot.Align align, float pre,
                       float post) {
        float total = pre + post;
        if (align == CgWidgetSlot.Align.FILL) {
            position[c * 2 + axis] = pre;
            size[c * 2 + axis] = allotted - total;
            return;
        }
        float child = Math.min(childDesired, allotted - total);
        position[c * 2 + axis] = switch (align) {
            case LEFT -> pre;
            case CENTRE -> (allotted - child) / 2f + pre - post;
            default -> allotted - child - post;
        };
        size[c * 2 + axis] = child;
    }

    // ---- the sequence target: the user widget itself, and each widget by name ----

    @Override
    public int slot(String name) {
        return slotOf(name);
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        write(n, slot, values, mask);
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(state, (n * SLOTS + slot) * 4, out, 0, 4);
    }

    @Nullable
    @Override
    public CgSequenceTarget part(CgSequenceBinding.Part part, String name) {
        if (part != CgSequenceBinding.Part.NODE) return null;
        int i = tree.index(name);
        if (i < 0) return null;
        return nested[i] != null ? nested[i] : parts[i];
    }

    private static int slotOf(String name) {
        return switch (name) {
            case "translation" -> TRANSLATION;
            case "scale" -> SCALE;
            case "shear" -> SHEAR;
            case "angle" -> ANGLE;
            case "render_opacity" -> RENDER_OPACITY;
            case "colour_and_opacity" -> COLOUR;
            case "content_colour_and_opacity" -> CONTENT_COLOUR;
            case "material_parameters" -> MATERIAL_PARAMETERS;
            case "blur" -> BLUR;
            default -> -1;
        };
    }

    private void write(int widget, int slot, float[] values, int mask) {
        int at = (widget * SLOTS + slot) * 4, diff = 0;
        for (int k = 0; k < 4; k++) {
            if ((mask & 1 << k) != 0 && state[at + k] != values[k]) {
                state[at + k] = values[k];
                diff |= 1 << k;
            }
        }
        if (diff != 0) changed(widget, slot, diff);
    }

    private void set4(int widget, int slot, float x, float y, float z, float w) {
        int at = (widget * SLOTS + slot) * 4;
        int diff = put(at, x) | put(at + 1, y) << 1 | put(at + 2, z) << 2 | put(at + 3, w) << 3;
        if (diff != 0) changed(widget, slot, diff);
    }

    /** Stores {@code value} at {@code at}: 1 if it changed. */
    private int put(int at, float value) {
        if (state[at] == value) return 0;
        state[at] = value;
        return 1;
    }

    /**
     * What a change to {@code components} of widget {@code w}'s {@code slot} needs: the first move or fade puts it in a
     * node, painted once; a node's later changes are its value at replay. Anything else is a repaint.
     */
    private void changed(int w, int slot, int components) {
        switch (slot) {
            case TRANSLATION, SCALE, SHEAR, ANGLE -> {
                if (!moves[w]) {
                    moves[w] = true;
                    revision++;
                }
            }
            case RENDER_OPACITY -> fade(w);
            case COLOUR -> {
                if ((components & 8) != 0) fade(w);
                if ((components & 7) != 0) revision++;
            }
            case CONTENT_COLOUR -> {
                if ((components & 8) != 0 && w < n && !contentFades[w]) {
                    contentFades[w] = true;
                    revision++;
                }
                if ((components & 7) != 0) revision++;
            }
            default -> revision++;
        }
    }

    private void fade(int w) {
        if (!fades[w]) {
            fades[w] = true;
            revision++;
        }
    }

    /** Its revision and its nested widgets': any change under it raises the sum. */
    private long deepRevision() {
        long r = revision;
        for (CgUserWidget w : nested) if (w != null) r += w.deepRevision();
        return r;
    }

    /** One widget as a sequence sees it. */
    private final class Part implements CgSequenceTarget {
        private final int index;

        Part(int index) {
            this.index = index;
        }

        @Override
        public int slot(String name) {
            return slotOf(name);
        }

        @Override
        public void set(int slot, float[] values, int mask) {
            write(index, slot, values, mask);
        }

        @Override
        public void get(int slot, float[] out) {
            System.arraycopy(state, (index * SLOTS + slot) * 4, out, 0, 4);
        }
    }

    private int require(String name) {
        int i = tree.index(name);
        if (i < 0) throw new IllegalArgumentException("no widget " + name + " in " + tree);
        return i;
    }

    /**
     * Lays text block {@code i} out in its style, its size into {@code measured}: one line at its own width, or STextBlock's
     * lines justified across the wrap width, the box the widest line's.
     */
    private void lay(int i, CgWidget w) {
        CgTextStyle st = style[i];
        if (w.wrap <= 0f && w.justification == CgWidget.Justification.LEFT) {
            CgText t = CgText.of(shown[i]).style(st);
            CgTextLayout l = t.measure();
            laid[i] = t;
            laidInset[i] = 0f;
            measured.set(l.totalWidth(), l.totalHeight());
            return;
        }
        CgFont font = st.font(Math.max(1, Math.round(st.px())));
        CgTextAlign align = switch (w.justification) {
            case CENTRE -> CgTextAlign.CENTER;
            case RIGHT -> CgTextAlign.RIGHT;
            case LEFT -> CgTextAlign.LEFT;
        };
        CgTextLayout.Request request = CgTextLayout.of(shown[i], font).align(align);
        if (w.wrap > 0f) request.maxWidth(w.wrap);
        CgTextLayout layout = request.build();
        laid[i] = CgText.of(layout).style(st).contained(false);
        laidInset[i] = w.wrap > 0f ? w.wrap - layout.totalWidth() : 0f;
        measured.set(layout.totalWidth(), layout.totalHeight());
    }

    private void putColour(int at, CgLinearColour c) {
        state[at] = c.r();
        state[at + 1] = c.g();
        state[at + 2] = c.b();
        state[at + 3] = c.a();
    }

    private static float shear(float degrees) {
        return degrees == 0f ? 0f : (float) Math.tan(Math.toRadians(Math.max(-89f, Math.min(89f, degrees))));
    }

    @Override
    public String toString() {
        return "user widget of " + tree;
    }
}
