package com.crystalgraphics.widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

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
 * changes, and paints its leaves through a {@link CgWidgetPainter}. It is a sequence target: a widget animation binds
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
 * pop.retain(painter);
 * pop.paint(painter, placement.scaling(scale));
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
    private static final int SLOTS = 8;
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
    private final Object[] laid;
    private final CgWidgetBrush[] brush;
    private final Part[] parts;
    private final CgUserWidget[] nested;

    private final float[] desired;
    private final float[] position;
    private final float[] size;
    private final Matrix4f[] poses;
    private final Matrix4f origin = new Matrix4f();
    private final int[] region;
    private final Vector2f measured = new Vector2f();
    private final Vector4f parameters = new Vector4f();
    private float allottedWidth = Float.NaN;
    private float allottedHeight = Float.NaN;
    private boolean dirty = true;
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
        laid = new Object[n];
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
        poses = new Matrix4f[n + 1];
        for (int i = 0; i <= n; i++) poses[i] = new Matrix4f();
        region = new int[n];
        Arrays.fill(region, -1);
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
     *   <li>Its world is its layer's ({@link CgViewportWidgetLayer}, {@link CgWorldWidgetLayer}), else the client's.</li>
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
        putColour(tree.index(name) * SLOTS * 4 + COLOUR * 4, Objects.requireNonNull(colour, "colour"));
        return this;
    }

    /**
     * Widget {@code name}'s material parameters: four values its brush's UI material reads as {@code CG_QUAD_CUSTOM0},
     * per instance, as a dynamic material instance's parameters are in UMG. Zero until set; an animation keys them as
     * {@code material_parameters} and starts from these. Never relays out.
     *
     * <pre>{@code
     * counter.materialParameters("Streak", 0.5f, 1.2f, 0f, 0f);   // its shader: float energy = CG_QUAD_CUSTOM0.x;
     * }</pre>
     */
    public CgUserWidget materialParameters(String name, float x, float y, float z, float w) {
        int at = require(name) * SLOTS * 4 + MATERIAL_PARAMETERS * 4;
        state[at] = x;
        state[at + 1] = y;
        state[at + 2] = z;
        state[at + 3] = w;
        return this;
    }

    /** Widget {@code index}'s render translation, in units: UMG's {@code SetRenderTranslation}. Never relays out. */
    public CgUserWidget translation(int index, float x, float y) {
        int at = index * SLOTS * 4 + TRANSLATION * 4;
        state[at] = x;
        state[at + 1] = y;
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

    /** Widget {@code index}'s box in its parent after the last layout, into {@code out}: x, y, width, height. */
    public float[] box(int index, float[] out) {
        out[0] = position[index * 2];
        out[1] = position[index * 2 + 1];
        out[2] = size[index * 2];
        out[3] = size[index * 2 + 1];
        return out;
    }

    /**
     * Lays it out if anything it reads changed, measuring text through {@code painter}; its size into {@code out}:
     * the allotted, else its root's desired.
     */
    public Vector2f layout(CgWidgetPainter painter, Vector2f out) {
        layoutIfDirty(painter);
        return out.set(rootWidth(), rootHeight());
    }

    /**
     * Paints each visible retainer box's content into a region of {@code painter}'s retained image, at full opacity,
     * as {@code SRetainerWidget} paints into its render target. Every instance a frame draws retains before any
     * paints: the retained image is drawn before anything that reads it.
     */
    public void retain(CgWidgetPainter painter) {
        layoutIfDirty(painter);
        if (n > 0) retain(painter, 0);
    }

    private void retain(CgWidgetPainter painter, int i) {
        if (!visibility[i].draws()) return;
        CgWidget w = tree.widget(i);
        if (w.kind == CgWidget.Kind.USER_WIDGET) {
            nested[i].retain(painter);
            return;
        }
        if (w.kind == CgWidget.Kind.RETAINER_BOX) {
            region[i] = painter.beginRetained(size[i * 2], size[i * 2 + 1]);
            try {
                for (int c : tree.children(i)) {
                    if (contains(c, CgWidget.Kind.RETAINER_BOX)) {
                        throw new IllegalStateException(w.name + ": a retainer box inside another is not drawn");
                    }
                    paint(painter, c, origin.identity(), 1f, 1f, 1f, 1f);
                }
            } finally {
                painter.endRetained();
            }
            return;
        }
        for (int c : tree.children(i)) retain(painter, c);
    }

    private boolean contains(int i, CgWidget.Kind kind) {
        if (tree.widget(i).kind == kind) return true;
        for (int c : tree.children(i)) if (contains(c, kind)) return true;
        return false;
    }

    /**
     * Paints it: lays out if needed, then each visible leaf in tree and z order, under {@code placement} (widget units
     * of its box to canvas pixels). {@link #retain} first where it holds a retainer box.
     */
    public void paint(CgWidgetPainter painter, Matrix4f placement) {
        paintAt(painter, placement, 1f, 1f, 1f, 1f);
    }

    /** Paints it with its box at {@code pose}'s origin, under a parent's tint (linear). */
    private void paintAt(CgWidgetPainter painter, Matrix4f pose, float r, float g, float b, float a) {
        layoutIfDirty(painter);
        int self = n * SLOTS * 4;
        Matrix4f own = poses[n].set(pose);
        renderTransform(own, self, selfPivotX * rootWidth(), selfPivotY * rootHeight());
        // SObjectWidget is a compound widget: the user widget's colour and opacity tints its content.
        a *= state[self + RENDER_OPACITY * 4];
        int c = self + COLOUR * 4;
        if (n > 0) paint(painter, 0, own, r * state[c], g * state[c + 1], b * state[c + 2], a * state[c + 3]);
    }

    private void paint(CgWidgetPainter painter, int i, Matrix4f parentPose, float r, float g, float b, float a) {
        if (!visibility[i].draws()) return;
        CgWidget w = tree.widget(i);
        int at = i * SLOTS * 4;
        float width = size[i * 2], height = size[i * 2 + 1];
        Matrix4f pose = poses[i].set(parentPose).translate(position[i * 2], position[i * 2 + 1], 0f);
        if (w.kind == CgWidget.Kind.USER_WIDGET) {
            nested[i].paintAt(painter, pose, r, g, b, a);
            return;
        }
        renderTransform(pose, at, w.pivotX * width, w.pivotY * height);
        // SWidget::Paint: its render opacity into the style it paints itself and its children with.
        a *= state[at + RENDER_OPACITY * 4];
        int c = at + COLOUR * 4;
        switch (w.kind) {
            case IMAGE -> {
                CgWidgetBrush br = brush[i];
                if (br.draws()) {
                    // SImage: style tint x colour and opacity x brush tint.
                    CgLinearColour t = br.tint();
                    painter.image(br, pose, width, height, r * state[c] * t.r(), g * state[c + 1] * t.g(),
                            b * state[c + 2] * t.b(), a * state[c + 3] * t.a(), parameters(at));
                }
            }
            case TEXT_BLOCK -> painter.text(style[i], shown[i], laid[i], w.justification, pose, width,
                    r * state[c], g * state[c + 1], b * state[c + 2], a * state[c + 3]);
            case BORDER -> {
                CgWidgetBrush br = brush[i];
                if (br.draws()) {
                    // SBorder: brush tint x style tint x brush colour.
                    CgLinearColour t = br.tint(), bc = w.brushColour;
                    painter.image(br, pose, width, height, r * t.r() * bc.r(), g * t.g() * bc.g(), b * t.b() * bc.b(),
                            a * t.a() * bc.a(), parameters(at));
                }
                // SCompoundWidget: its content colour into its content's style.
                int cc = at + CONTENT_COLOUR * 4;
                paintChildren(painter, i, pose, r * state[cc], g * state[cc + 1], b * state[cc + 2], a * state[cc + 3]);
            }
            case SCALE_BOX -> {
                // FGeometry::MakeChild's layout scale: everything inside is laid out and drawn in its scaled space.
                pose.scale(w.userScale, w.userScale, 1f);
                paintChildren(painter, i, pose, r, g, b, a);
            }
            case RETAINER_BOX -> {
                // SRetainerWidget: the retained image as one quad, tinted by everything above it.
                if (region[i] < 0) throw new IllegalStateException(w.name + ": retain(painter) before paint");
                painter.retained(region[i], w.effect, w.textureParameter, pose, width, height, r, g, b, a);
                region[i] = -1;
            }
            default -> paintChildren(painter, i, pose, r, g, b, a);
        }
    }

    private void paintChildren(CgWidgetPainter painter, int i, Matrix4f pose, float r, float g, float b, float a) {
        for (int c : tree.paintOrder(i)) paint(painter, c, pose, r, g, b, a);
    }

    /** Slate's render transform about the pivot ({@code px}, {@code py}), appended to {@code pose}: {@code Geometry.h}'s order. */
    private void renderTransform(Matrix4f pose, int at, float px, float py) {
        float sx = state[at + SCALE * 4], sy = state[at + SCALE * 4 + 1];
        float kx = shear(state[at + SHEAR * 4]), ky = shear(state[at + SHEAR * 4 + 1]);
        float angle = state[at + ANGLE * 4];
        float tx = state[at + TRANSLATION * 4], ty = state[at + TRANSLATION * 4 + 1];
        if (tx == 0f && ty == 0f && sx == 1f && sy == 1f && kx == 0f && ky == 0f && angle == 0f) return;
        // FWidgetTransform::ToSlateRenderTransform: scale, then shear, then rotation, then translation.
        pose.translate(px + tx, py + ty, 0f);
        if (angle != 0f) pose.rotateZ((float) Math.toRadians(angle));
        if (kx != 0f || ky != 0f) pose.mul(1f, ky, 0f, 0f, kx, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f);
        pose.scale(sx, sy, 1f).translate(-px, -py, 0f);
    }

    // ---- layout: Slate's desired sizes bottom up, then each panel arranging its children ----

    private boolean needsLayout() {
        if (dirty) return true;
        for (CgUserWidget w : nested) if (w != null && w.needsLayout()) return true;
        return false;
    }

    private void layoutIfDirty(CgWidgetPainter painter) {
        if (!needsLayout()) return;
        desireAll(painter);
        if (n > 0) {
            position[0] = position[1] = 0f;
            size[0] = Float.isNaN(allottedWidth) ? desired[0] : allottedWidth;
            size[1] = Float.isNaN(allottedHeight) ? desired[1] : allottedHeight;
        }
        for (int i = 0; i < n; i++) arrange(i, painter);
        dirty = false;
    }

    private void desireAll(CgWidgetPainter painter) {
        for (int i = n - 1; i >= 0; i--) desire(i, painter);
    }

    private float rootWidth() {
        return n == 0 ? 0f : size[0];
    }

    private float rootHeight() {
        return n == 0 ? 0f : size[1];
    }

    private void desire(int i, CgWidgetPainter painter) {
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
                    laid[i] = painter.layout(style[i], shown[i], w.wrap, w.justification, measured);
                    dx = measured.x;
                    dy = measured.y;
                }
                case USER_WIDGET -> {
                    CgUserWidget inner = nested[i];
                    inner.desireAll(painter);
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

    private void arrange(int i, CgWidgetPainter painter) {
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
            case USER_WIDGET -> nested[i].allot(width, height).layoutIfDirty(painter);
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
            default -> -1;
        };
    }

    private void write(int widget, int slot, float[] values, int mask) {
        int at = (widget * SLOTS + slot) * 4;
        for (int k = 0; k < 4; k++) {
            if ((mask & 1 << k) != 0) state[at + k] = values[k];
        }
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

    /** The material parameters at widget state {@code at}, in a vector reused for each draw. */
    private Vector4f parameters(int at) {
        int p = at + MATERIAL_PARAMETERS * 4;
        return parameters.set(state[p], state[p + 1], state[p + 2], state[p + 3]);
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
