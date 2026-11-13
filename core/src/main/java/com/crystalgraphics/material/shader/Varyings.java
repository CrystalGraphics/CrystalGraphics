package com.crystalgraphics.material.shader;

import com.crystalgraphics.material.shader.syntax.CgShaderNames;
import com.crystalgraphics.material.shader.syntax.CgShaderSyntax;
import com.crystalgraphics.material.shader.syntax.CgShaderTokens;
import com.github.bsideup.jabel.Desugar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A {@code struct v2f}'s fields: read as written, then held to what an interface block may carry. */
final class Varyings {

    /** A field as written, {@code token} its first for an error. */
    @Desugar
    record Field(String qualifier, String type, String name, String text, int token) {
        CgVarying varying() {
            return new CgVarying(type, name, qualifier);
        }
    }

    private static final Set<String> QUALIFIERS = Set.of("flat", "noperspective", "smooth");
    private static final Set<String> TYPES = Set.of("float", "vec2", "vec3", "vec4", "mat3", "mat4", "int", "uint",
            "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4");
    private static final Set<String> INTEGERS = Set.of("int", "uint", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4");

    private Varyings() {}

    /** The fields of {@code v2f}, each {@code [qualifier] type name;}; none for a file with no v2f. */
    static List<Field> fields(CgShaderSyntax file, CgShaderSyntax.Struct v2f) {
        List<Field> out = new ArrayList<>();
        if (v2f == null) return out;
        CgShaderTokens t = file.tokens();
        int i = v2f.open() + 1;
        while (i < v2f.close()) {
            int first = i;
            while (i < v2f.close() && !t.is(i, ';')) i++;
            String text = t.plain(first, i < v2f.close() ? i : i - 1).replace('\n', ' ');
            int n = i - first;
            boolean qualified = n == 3 && QUALIFIERS.contains(t.text(first));
            if (i >= v2f.close() || !(n == 2 || qualified) || !t.isIdent(i - 1) || !t.isIdent(i - 2)) {
                throw t.error(first, "Invalid v2f field: '" + text + "'");
            }
            out.add(new Field(qualified ? t.text(first) : "", t.text(i - 2), t.text(i - 1), text, first));
            i++;
        }
        return out;
    }

    /**
     * Holds {@code fields} to an interface block's: an integer is {@code flat}, a type is a scalar, vector or matrix,
     * a name takes no engine prefix ({@code passed} allows a template's {@code _cg_in_}).
     */
    static List<CgVarying> check(CgShaderSyntax file, List<Field> fields, boolean passed) {
        List<CgVarying> out = new ArrayList<>(fields.size());
        for (Field f : fields) {
            if (INTEGERS.contains(f.type()) && !f.qualifier().equals("flat")) {
                throw file.tokens().error(f.token(), "An integer v2f field is 'flat', since it is not interpolated. Found: '"
                        + f.text() + "'");
            }
            if (!TYPES.contains(f.type())) throw file.tokens().error(f.token(), "Invalid v2f field: '" + f.text() + "'.");
            CgShaderNames.refuseReserved(file.tokens(), f.token(), f.name(), "struct v2f", passed);
            out.add(f.varying());
        }
        return out;
    }
}
