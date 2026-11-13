package com.crystalgraphics.api.material;

import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.parse.CgPermutationDomain;

import javax.annotation.Nullable;
import java.util.List;

/**
 * What a draw takes: a {@link CgMaterial}, an asset whose values are its defaults, or a {@link CgMaterialInstance},
 * values of its own over one. Unreal's {@code UMaterialInterface}.
 *
 * <pre>{@code
 * CgMaterial stone = CgMaterial.load("mymod:shaders/stone.shader");
 * world.draw(mesh, stone).submit();                       // the asset as it is
 *
 * CgMaterialInstance hot = stone.instance().set1f("_Heat", 0.8f);
 * world.draw(mesh, hot).submit();                         // its programs, these values
 * }</pre>
 *
 * <p>The reads answer its material's: an instance changes values, never programs.</p>
 */
public interface CgMaterialInterface {

    /** The material whose programs it draws with: itself, or an instance's. */
    CgMaterial material();

    /**
     * How many times something that may change what it draws was set: a canvas starts a new draw where this moved, so a
     * value set between two items takes effect at the second.
     */
    int changes();

    /** A unique, non-negative id: the opaque sort key's material bits. An instance has its own. */
    int getMaterialId();

    /**
     * Starts its compile without waiting, and answers whether its Forward pipeline now builds without waiting on the
     * driver: what a caller that must never stall a frame polls before its first draw.
     *
     * <pre>{@code
     * if (next.precache()) current = next;    // compiled, or failed: lastCompileError() says which
     * }</pre>
     */
    boolean precache();

    /** What the driver said about its last failed compile, or null. */
    @Nullable
    default String lastCompileError() {
        return material().lastCompileError();
    }

    /** Its render state where it is not its shader's; {@link CgMaterialState#SHADER} for none. */
    default CgMaterialState state() {
        return material().state();
    }

    /**
     * The properties its shader declares with its values, and under {@code PerDrawValues On} the hidden row slots the parser
     * adds, from the parse alone. Empty while the source does not parse.
     *
     * <pre>{@code
     * for (CgMaterialProperty p : material.properties()) if (p.isRowable()) bindable.add(p.getName());
     * }</pre>
     */
    default List<CgMaterialProperty> properties() {
        return material().properties();
    }

    /** The keywords its shader declares ({@code Keywords { }}), from the parse alone. */
    default List<String> declaredKeywords() {
        return material().declaredKeywords();
    }

    /** Whether {@code name} is one of the keywords its programs are built with. */
    default boolean isKeywordEnabled(String name) {
        return material().isKeywordEnabled(name);
    }

    /**
     * The keywords a renderer switches on its shader at runtime (its {@code switched} keywords and one-ofs), from the parse alone:
     * every combination a precache of the shader builds.
     */
    default CgPermutationDomain permutations() {
        return material().permutations();
    }

    /** Whether its shader's {@code Buffers { }} declares {@code name}, from the parse alone. */
    default boolean declaresBuffer(String name) {
        return material().declaresBuffer(name);
    }

    /**
     * Whether a pass of its shader names {@code identifier} as a whole word, in its declarations or its stages' code:
     * what a renderer asks before writing a value only some shaders read. A name used only in an included file is not
     * seen.
     *
     * <pre>{@code
     * boolean bounds = material.names("CG_OBJECT_POSITION") || material.names("CG_OBJECT_RADIUS");
     * }</pre>
     */
    default boolean names(String identifier) {
        return material().names(identifier);
    }
}
