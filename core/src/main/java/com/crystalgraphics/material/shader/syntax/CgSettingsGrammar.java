package com.crystalgraphics.material.shader.syntax;

import com.github.bsideup.jabel.Desugar;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Name Value} pairs: a {@code Material { }}, {@code Factory { }} or {@code Primitive { }} block, a {@code Step}'s
 * head. A value is one word ({@code Translucent}, {@code 0.5}, {@code mymod:Ribbons}, {@code cg_Vfx.presence}) or a
 * comma list ({@code VertexFactory Mesh, MeshParticles}).
 *
 * <pre>{@code
 * Map<String, CgSettingsGrammar.Setting> m = CgSettingsGrammar.read(tokens, block.open() + 1, block.close(),
 *         List.of("BlendMode", "Faces"), "Material");
 * String blend = m.containsKey("BlendMode") ? m.get("BlendMode").value() : "Opaque";
 * }</pre>
 *
 * <ul>
 *   <li>A name not in {@code allowed}, a name twice and a name with no value fail, placed on the name.</li>
 *   <li>A list's value is its items joined by {@code ,}; {@link Setting#values()} splits it.</li>
 * </ul>
 */
public final class CgSettingsGrammar {

    /** One pair; {@code keyToken} and {@code valueToken} place an error on either. */
    @Desugar
    public record Setting(String key, String value, int keyToken, int valueToken) {
        public List<String> values() {
            return Arrays.asList(value.split(","));
        }
    }

    private CgSettingsGrammar() {}

    /** Tokens {@code from} to {@code to} (exclusive) as pairs; {@code what} names the block in errors. */
    public static Map<String, Setting> read(CgShaderTokens t, int from, int to, Collection<String> allowed, String what) {
        Map<String, Setting> out = new LinkedHashMap<>();
        List<Words.Word> words = Words.of(t, from, to);
        int i = 0;
        while (i < words.size()) {
            Words.Word key = words.get(i);
            if (key.is(",")) throw t.error(key.first(), "'" + what + "' holds Name Value pairs: a ',' with no list before it");
            if (i + 1 >= words.size() || words.get(i + 1).is(",")) {
                throw t.error(key.first(), "'" + what + "' holds Name Value pairs: '" + key.text() + "' has no value");
            }
            if (!allowed.contains(key.text())) {
                throw t.error(key.first(), "'" + key.text() + "' is no " + what + " setting here: " + allowed);
            }
            Words.Word value = words.get(i + 1);
            StringBuilder joined = new StringBuilder(value.text());
            i += 2;
            while (i + 1 < words.size() && words.get(i).is(",")) {
                joined.append(',').append(words.get(i + 1).text());
                i += 2;
            }
            if (out.put(key.text(), new Setting(key.text(), joined.toString(), key.first(), value.first())) != null) {
                throw t.error(key.first(), "'" + key.text() + "' twice");
            }
        }
        return out;
    }
}
