package com.crystalgraphics.shadergraph;

import com.crystalgraphics.gl.material.CgMaterialProperty;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.2 — that {@link CgShaderType} and {@code CgMaterialProperty.Type} agree.
 *
 * <h3>Why two type enums exist at all</h3>
 * <p>They answer different questions and are deliberately not merged — see the class note on
 * {@link CgShaderType}. What they share is the GLSL name, and that is the seam: a graph property becomes
 * a wire type through it, and a wire type becomes a generated {@code Properties} entry back through it.</p>
 *
 * <p><b>The seam is what needs a test, not the enums.</b> Nothing in the compiler forces the two to stay
 * in step, so the failure mode is somebody adding a member to one — and finding out when a generated
 * shader silently drops a property, or a port comes out untyped. That is a bad afternoon and a
 * one-line test.</p>
 */
public class CgShaderTypeBridgeTest {

    /**
     * <b>Every property type maps to a wire type.</b>
     *
     * <p>Including the two that are not GLSL keywords: {@code COLOR} arrives as {@code vec4} and
     * {@code Range} as {@code float}, which is exactly right — the affordance is the editor's, the type
     * on the wire is the compiled one.</p>
     */
    @Test
    public void everyPropertyTypeMapsToAShaderType() {
        for (CgMaterialProperty.Type property : CgMaterialProperty.Type.values()) {
            CgShaderType mapped = CgShaderType.parse(property.getGlslName());
            assertNotNull("no wire type for property type " + property + " (glsl: "
                    + property.getGlslName() + ") — the two enums have drifted", mapped);
            assertEquals("the mapping must preserve the GLSL name",
                    property.getGlslName(), mapped.glsl());
        }
    }

    /** The editor affordances land on their compiled types rather than on themselves. */
    @Test
    public void colourAndRangeArriveAsWhatTheyCompileTo() {
        assertEquals(CgShaderType.VEC4, CgShaderType.parse(CgMaterialProperty.Type.COLOR.getGlslName()));
        assertEquals(CgShaderType.FLOAT, CgShaderType.parse(CgMaterialProperty.Type.RANGE.getGlslName()));
    }

    /**
     * <b>And back the other way, for every wire type a property can actually be.</b>
     *
     * <p>Matrices and {@link CgShaderType#DYNAMIC} are excluded on purpose: there is no matrix property
     * type today, and {@code DYNAMIC} is resolved away before anything is emitted, so neither can appear
     * in a generated {@code Properties} block.</p>
     */
    @Test
    public void everyPropertyCapableShaderTypeMapsBack() {
        for (CgShaderType type : CgShaderType.values()) {
            String token = type.propertyTypeName();
            if (token == null) {
                assertTrue(type + " has no property type, which is only correct for dynamic and matrices",
                        type == CgShaderType.DYNAMIC || type.isMatrix());
                continue;
            }
            assertNotNull("no property type for wire type " + type + " (token: " + token
                            + ") — a graph could produce a value that cannot be declared as a uniform",
                    CgMaterialProperty.Type.fromPropertyType(token));
        }
    }

    /**
     * <b>{@code bool} is written {@code boolean} in a Properties block</b>, and this test is why
     * {@link CgShaderType#propertyTypeName()} exists at all.
     *
     * <p>Found by the bridge test above rather than by reading: emitting the GLSL name would produce a
     * token the {@code .shader} parser rejects, so a perfectly valid graph would fail at parse time with
     * an error about a Properties entry the user never wrote.</p>
     */
    @Test
    public void theBooleanSpellingDiffersBetweenGlslAndProperties() {
        assertEquals("bool", CgShaderType.BOOL.glsl());
        assertEquals("boolean", CgShaderType.BOOL.propertyTypeName());
        assertNotNull(CgMaterialProperty.Type.fromPropertyType(CgShaderType.BOOL.propertyTypeName()));
    }

    /** {@code boolean} and {@code bool} both parse, because the Properties block already accepts the
     * former and one vocabulary is better than two. */
    @Test
    public void bothSpellingsOfBooleanParse() {
        assertEquals(CgShaderType.BOOL, CgShaderType.parse("bool"));
        assertEquals(CgShaderType.BOOL, CgShaderType.parse("boolean"));
    }
}
