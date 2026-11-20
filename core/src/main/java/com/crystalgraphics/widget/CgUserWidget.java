package com.crystalgraphics.widget;

import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Vector2f;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.paint.CgColor;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
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

    // Each widget's property slots, 4 floats each, in its run of the store
    static final int TRANSLATION = 0;
    static final int SCALE = 1;
    static final int SHEAR = 2;
    static final int ANGLE = 3;
    static final int RENDER_OPACITY = 4;
    static final int COLOUR = 5;
    static final int CONTENT_COLOUR = 6;
    static final int MATERIAL_PARAMETERS = 7;
    static final int BLUR = 8;
    static final int SLOTS = 9;

    private final CgWidgetTree tree;
    private final int n;
    // The property store: per widget, and the user widget itself at index n, SLOTS x 4 floats each, colours linear.
    final float[] state;
    private final float[] designer;
    final float selfPivotX;
    final float selfPivotY;
    final CgWidget.Visibility[] visibility;
    final String[] text;
    final CgTextStyle[] style;
    final CgBrush[] brush;
    /** What each draws: its brush untinted, its tint multiplied in linear by paint. */
    final CgBrush[] drawn;
    private final CgWidgetPart[] parts;
    final CgUserWidget[] nested;
    final CgWidgetLayout layout;
    final CgWidgetPainter painter;

    /** Bumped by every change a replay cannot show: layout, text, a brush, visibility, a colour, material parameters. */
    private long revision;
    /** Raised by every change it shows, a move or fade a replay poses included: {@link #changes}. */
    private long touched;
    private final CgWidgetAnimations animations = new CgWidgetAnimations(this);

    CgUserWidget(CgWidgetTree tree, @Nullable CgWidget as) {
        this.tree = tree;
        n = tree.size();
        state = new float[(n + 1) * SLOTS * 4];
        visibility = new CgWidget.Visibility[n];
        text = new String[n];
        style = new CgTextStyle[n];
        brush = new CgBrush[n];
        drawn = new CgBrush[n];
        parts = new CgWidgetPart[n];
        nested = new CgUserWidget[n];
        for (int i = 0; i < n; i++) {
            CgWidget w = tree.widget(i);
            CgWidget.seed(w, state, i * SLOTS * 4);
            parts[i] = new CgWidgetPart(this, i);
            if (w.kind == CgWidget.Kind.USER_WIDGET) nested[i] = new CgUserWidget(w.widget, w);
        }
        if (as != null) {
            CgWidget.seed(as, state, n * SLOTS * 4);
            selfPivotX = as.pivotX;
            selfPivotY = as.pivotY;
        } else {
            CgWidget.seed(null, state, n * SLOTS * 4);
            selfPivotX = selfPivotY = 0.5f;
        }
        designer = state.clone();
        restore();
        layout = new CgWidgetLayout(this);
        painter = new CgWidgetPainter(this);
    }

    private void restore() {
        for (int i = 0; i < n; i++) {
            CgWidget w = tree.widget(i);
            visibility[i] = w.visibility;
            text[i] = w.text;
            style[i] = w.style;
            setBrush(i, w.brush);
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
        return animations.play(animation, start, end, speed);
    }

    /**
     * Where {@code animation} is, in seconds, while it plays; 0 when it is not playing or has finished. UMG's
     * {@code GetAnimationCurrentTime} ({@code UserWidget.cpp:740}), which asks only its active players.
     */
    public double animationCurrentTime(CgSequenceDefinition animation) {
        return animations.currentTime(animation);
    }

    /**
     * Stops {@code animation} mid-play, putting back what it wrote. Nothing for one not playing or finished. UMG's
     * {@code StopAnimation}.
     */
    public void stopAnimation(CgSequenceDefinition animation) {
        animations.stop(animation);
    }

    /** Stops every animation playing on it: UMG's {@code StopAllAnimations}. */
    public void stopAllAnimations() {
        animations.stopAll();
    }

    /** Whether {@code animation} is playing and has not reached its end: UMG's {@code IsAnimationPlaying}. */
    public boolean isAnimationPlaying(CgSequenceDefinition animation) {
        return animations.isPlaying(animation);
    }

    /**
     * Runs {@code listener} each time {@code animation} reaches its end on this widget, its last frame applied: UMG's
     * {@code BindToAnimationFinished}. Bound once; it holds for every later play, and through {@link #reset()}.
     */
    public CgUserWidget onAnimationFinished(CgSequenceDefinition animation, Runnable listener) {
        animations.onFinished(animation, listener);
        return this;
    }

    /** The world its animations play on: its layer's. */
    void world(@Nullable CgWorld world) {
        animations.world(world);
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
        layout.forget();
        touched++;
        revision++;
    }

    /** Lays it out at {@code width} x {@code height} widget units rather than its root's desired size. */
    public CgUserWidget allot(float width, float height) {
        if (layout.allot(width, height)) touched++;
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
            layout.invalidate();
            touched++;
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
            layout.invalidate();
            touched++;
        }
        return this;
    }

    /**
     * Widget {@code name}'s colour and opacity, multiplied into what it draws: UMG's {@code SetColorAndOpacity} on an
     * image, a text block or a user widget. Never relays out; an animation keying it starts from this.
     *
     * <pre>{@code
     * counter.colourAndOpacity("Banner", CgColor.argb(0xFFFF8410));   // the banner dyed the grade's orange
     * }</pre>
     */
    public CgUserWidget colourAndOpacity(String name, CgColor colour) {
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
            layout.invalidate();
            touched++;
        }
        return this;
    }

    /** An image's brush or a border's background: UMG's {@code SetBrush}. */
    public CgUserWidget brush(String name, CgBrush value) {
        return brush(require(name), value);
    }

    public CgUserWidget brush(int index, CgBrush value) {
        if (!value.equals(brush[index])) {
            setBrush(index, value);
            layout.invalidate();
            touched++;
        }
        return this;
    }

    /** {@code value} as widget {@code i}'s brush, and the copy it draws: untinted, its tint multiplied in linear here. */
    private void setBrush(int i, CgBrush value) {
        brush[i] = value;
        drawn[i] = value.linearTint().equals(CgColor.WHITE) ? value : value.tint(CgColor.WHITE);
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
    public CgColor colour(int index) {
        int at = index * SLOTS * 4 + COLOUR * 4;
        return new CgColor(state[at], state[at + 1], state[at + 2], state[at + 3]);
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
        return painter.filter(require(name));
    }

    /** Widget {@code index}'s box in its parent after the last layout, into {@code out}: x, y, width, height. */
    public float[] box(int index, float[] out) {
        out[0] = layout.position[index * 2];
        out[1] = layout.position[index * 2 + 1];
        out[2] = layout.size[index * 2];
        out[3] = layout.size[index * 2 + 1];
        return out;
    }

    /**
     * Lays it out if anything it reads changed, measuring its text; its size into {@code out}: the allotted, else its
     * root's desired.
     */
    public Vector2f layout(Vector2f out) {
        layoutIfDirty();
        return out.set(layout.rootWidth(), layout.rootHeight());
    }

    /** Lays it out if anything it reads changed; a layout is a change a replay cannot show. */
    void layoutIfDirty() {
        if (layout.layoutIfDirty()) revision++;
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
        painter.paint(canvas);
    }

    /**
     * Raised by any change to what it shows, its own or a nested widget's: content, layout, and the moves and fades a
     * replay poses without repainting. What a picture of it compares to know it is stale (a widget in the world).
     */
    public long changes() {
        long c = touched + revision;
        for (CgUserWidget w : nested) if (w != null) c += w.changes();
        return c;
    }

    /** How often it has been painted rather than replayed: an animation that only moves and fades it adds none. */
    public long repaints() {
        return painter.repaints();
    }

    /** What a brush's UI material reads a widget's {@code material_parameters} as. */
    public static final String PARAMETERS = "_Parameters";

    // ---- the sequence target: the user widget itself, and each widget by name ----

    @Override
    public int slot(String name) {
        return CgWidgetPart.slotOf(name);
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

    /** Writes {@code mask}'s components of {@code values} to widget {@code widget}'s {@code slot}. */
    void write(int widget, int slot, float[] values, int mask) {
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
        touched++;
        switch (slot) {
            case TRANSLATION, SCALE, SHEAR, ANGLE -> {
                if (painter.move(w)) revision++;
            }
            case RENDER_OPACITY -> fade(w);
            case COLOUR -> {
                if ((components & 8) != 0) fade(w);
                if ((components & 7) != 0) revision++;
            }
            case CONTENT_COLOUR -> {
                if ((components & 8) != 0 && w < n && painter.contentFade(w)) revision++;
                if ((components & 7) != 0) revision++;
            }
            default -> revision++;
        }
    }

    private void fade(int w) {
        if (painter.fade(w)) revision++;
    }

    /** Its revision and its nested widgets': any change under it raises the sum. */
    long deepRevision() {
        long r = revision;
        for (CgUserWidget w : nested) if (w != null) r += w.deepRevision();
        return r;
    }

    private int require(String name) {
        int i = tree.index(name);
        if (i < 0) throw new IllegalArgumentException("no widget " + name + " in " + tree);
        return i;
    }

    @Override
    public String toString() {
        return "user widget of " + tree;
    }
}
