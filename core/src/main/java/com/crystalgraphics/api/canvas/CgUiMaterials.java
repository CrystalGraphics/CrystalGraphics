package com.crystalgraphics.api.canvas;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.precache.CgPipelineList;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The engine's ready {@code #domain ui} materials, so a mod never writes a shader to draw an image: the canvas's own
 * element under each blend, and an image material for an API that takes a material rather than a paint.
 *
 * <pre>{@code
 * static final CgMaterialInstance SPLASH = CgUiMaterials.image(splashTexture);   // made once, kept
 * target.drawMaterial(SPLASH);                                                   // the texture over the whole target
 * canvas.box(x, y, w, h).material(SPLASH).fill(0x80FFFFFF).draw();             // tinted, half faded
 * }</pre>
 *
 * <pre>{@code
 * CgMaterialInstance glow = CgUiMaterials.image(sparkTexture, CgBlendMode.ADDITIVE);   // added onto what is behind
 * }</pre>
 *
 * <ul>
 *   <li>On the canvas a paint is simpler: {@code box(...).fill(texture)}, {@code fill(CgBrush)}, {@code blend(...)}.
 *       These are for {@code CgRenderTarget.drawMaterial} and anything else that takes a material.</li>
 *   <li>{@link #image(CgTexture)} answers a new instance each call: make it once, and {@code delete()} it when done.</li>
 *   <li>The shared materials ({@link #element}, {@link #image(CgBlendMode)}) are the engine's: take an
 *       {@code instance()} to set values, never set them on the material.</li>
 *   <li>Premultiplied, additive, modulate, subtract and mask: a ui element writes premultiplied, so {@code ALPHA} and
 *       {@code OPAQUE} are refused.</li>
 * </ul>
 */
public final class CgUiMaterials {

    /** The canvas's element: the record's own paint, its surface adding nothing. */
    public static final String ELEMENT = "crystalgraphics:shaders/ui/element.shader";
    /** {@code _MainTex}, straight alpha, over the element times its colour. */
    public static final String IMAGE = "crystalgraphics:shaders/ui/image.shader";

    private static final CgBlendMode[] BLENDS = {CgBlendMode.PREMULTIPLIED, CgBlendMode.ADDITIVE,
            CgBlendMode.MODULATE, CgBlendMode.SUBTRACT, CgBlendMode.MASK};

    private static final Map<CgBlendMode, CgMaterial> ELEMENTS = new EnumMap<>(CgBlendMode.class);
    private static final Map<CgBlendMode, CgMaterial> IMAGES = new EnumMap<>(CgBlendMode.class);

    private CgUiMaterials() {
    }

    /** The material a canvas item draws with unless it names its own: the element, premultiplied over. */
    public static CgMaterial element() {
        return element(CgBlendMode.PREMULTIPLIED);
    }

    /** The element under {@code blend}. */
    public static synchronized CgMaterial element(CgBlendMode blend) {
        return ELEMENTS.computeIfAbsent(require(blend), b -> load(ELEMENT, b));
    }

    /** The image material under {@code blend}, its {@code _MainTex} white until an instance sets it. */
    public static synchronized CgMaterial image(CgBlendMode blend) {
        return IMAGES.computeIfAbsent(require(blend), b -> load(IMAGE, b));
    }

    /** A new instance drawing {@code texture}, straight alpha, premultiplied over what is behind. */
    public static CgMaterialInstance image(CgTexture texture) {
        return image(texture, CgBlendMode.PREMULTIPLIED);
    }

    /** A new instance drawing {@code texture}, straight alpha, under {@code blend}. */
    public static CgMaterialInstance image(CgTexture texture, CgBlendMode blend) {
        return image(blend).instance().sampler("_MainTex", 0, Objects.requireNonNull(texture, "texture"));
    }

    /** Every blend of the element, and the image over: what a canvas draws ahead of its first frame. */
    static void precache(CgPipelineList list) {
        for (CgBlendMode b : BLENDS) list.addPass(element(b), CgInstanceKind.ELEMENT);
        list.addPass(image(CgBlendMode.PREMULTIPLIED), CgInstanceKind.ELEMENT);
    }

    /** Forgets them at a context's teardown: the material registry frees them. */
    static synchronized void releaseAll() {
        ELEMENTS.clear();
        IMAGES.clear();
    }

    /** {@code blend}, refused where a premultiplied writer has no form. */
    static CgBlendMode require(@Nullable CgBlendMode blend) {
        Objects.requireNonNull(blend, "blend");
        for (CgBlendMode b : BLENDS) if (b == blend) return blend;
        throw new IllegalArgumentException("a ui element blends premultiplied, additive, modulate, subtract or mask, not "
                + blend);
    }

    private static CgMaterial load(String shader, CgBlendMode blend) {
        CgMaterial m = CgMaterial.load(shader, CgMaterialState.builder().blend(blend).twoSided().disableDepthTest()
                .build());
        m.precache();
        return m;
    }
}
