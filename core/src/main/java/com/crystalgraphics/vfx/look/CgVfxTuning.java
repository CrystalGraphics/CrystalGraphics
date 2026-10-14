package com.crystalgraphics.vfx.look;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadable;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A look opened for tuning: its numbers, its colours and its layers' constants as variables, set from the console, a
 * tuning panel or a remote tool, and what differs from where they started saved as YAML and loaded back, hot reloaded.
 *
 * <pre>{@code
 * CgVfxTuning tuning = CgVfxTuning.open("vfx.kamehameha", CgEnergyWave.kamehameha());
 * CgVars.get("vfx.kamehameha.radius").setText(CgVar.Source.CONSOLE, "0.9");
 * CgVars.get("vfx.kamehameha.core.g").setText(CgVar.Source.CONSOLE, "0.8");                // a colour: r, g, b, a
 * CgVars.get("vfx.kamehameha.crystalgraphics.charge.radius").setText(CgVar.Source.CONSOLE, "1.5"); // a kit's schema
 * CgVars.get("vfx.kamehameha.layer.body_shell._Flow").setText(CgVar.Source.CONSOLE, "30");      // a layer's constant
 * tuning.save(Paths.get("src/main/resources/assets/mymod/vfx/kamehameha.yaml"));   // what was changed
 * tuning.close();   // the variables go; the look keeps what was set
 * }</pre>
 *
 * A look's overrides shipped as an asset, edited live in a dev run:
 *
 * <pre>{@code
 * CgVfxTuning.open("vfx.kamehameha", LOOK).follow("mymod:vfx/kamehameha.yaml");   // loaded now, again on each save
 * }</pre>
 *
 * The file holds a value for each variable that differs, named as the variable less the prefix:
 *
 * <pre>{@code
 * radius: 0.9
 * core.g: 0.8
 * layer.body_shell._Flow: 30.0
 * }</pre>
 *
 * <ul>
 *   <li>A parameter is named as its layout names it ({@link CgVfxLayout#qualifiedName}): as declared for the effect's
 *       schema, after its schema for another's. A layer is named for its shader, then {@code _2} and on where two share a name; a vector
 *       constant's components are {@code x y z w}.</li>
 *   <li>The prefix is a variable id's: lowercase first, dots between, no {@code :}.</li>
 *   <li>A layer's constant is the layer's: every look drawing that layer takes it. Only constants it {@code set}s are
 *       variables; a shader's own defaults are not.</li>
 *   <li>Curves and shakes are not variables: they have no text form.</li>
 *   <li>A tuned value wins over one an effect set for itself, and breaks the link it followed.</li>
 *   <li>A file's values are {@link CgVar.Source#FILE}'s, under the console's. One tuning a prefix at a time.</li>
 *   <li>Clearing a variable puts back the value it had when the look was opened.</li>
 * </ul>
 */
public final class CgVfxTuning implements AutoCloseable, CgReloadable {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    private static final String[] COLOUR = {"r", "g", "b", "a"}, VECTOR = {"x", "y", "z", "w"};
    private static final CgCodec<Map<String, Float>> OVERRIDES = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.FLOAT);

    private final String prefix;
    /** Each variable by its name less the prefix, in the look's order. */
    private final Map<String, CgVar.Num> variables = new LinkedHashMap<>();
    /** The asset {@link #follow} reads, or null. */
    private String followed;

    private CgVfxTuning(String prefix) {
        this.prefix = prefix;
    }

    /** Declares a variable under {@code prefix} for each number of {@code look}, colour component, layer constant. */
    public static CgVfxTuning open(String prefix, CgVfxLook look) {
        CgVfxTuning tuning = new CgVfxTuning(prefix);
        try {
            tuning.declareValues(look);
            tuning.declareLayers(look);
        } catch (RuntimeException e) {
            tuning.close();
            throw e;
        }
        return tuning;
    }

    private void declareValues(CgVfxLook look) {
        CgVfxValues values = look.values();
        CgVfxLayout layout = look.layout();
        for (CgVfxParam param : layout.params()) {
            String key = layout.qualifiedName(param);
            int at = layout.offset(param);
            if (param instanceof CgVfxParam.Scalar) {
                CgVfxUnit unit = ((CgVfxParam.Scalar) param).unit();
                declare(key, values.raw(at), v -> values.tune(at, v), param.name + " of " + prefix
                        + (unit == CgVfxUnit.NONE ? "." : ", in " + unit.symbol() + "."));
            } else if (param instanceof CgVfxParam.Color) {
                for (int i = 0; i < 4; i++) {
                    int c = at + i;
                    declare(key + "." + COLOUR[i], values.raw(c), v -> values.tune(c, v),
                            "Colour " + param.name + " of " + prefix + ", its " + COLOUR[i] + ".");
                }
            } else if (param instanceof CgVfxParam.Palette) {
                for (int e = 0; e < ((CgVfxParam.Palette) param).count(); e++) {
                    for (int i = 0; i < 4; i++) {
                        int c = at + e * 4 + i;
                        declare(key + "." + e + "." + COLOUR[i], values.raw(c), v -> values.tune(c, v),
                                "Colour " + e + " of " + param.name + " of " + prefix + ", its " + COLOUR[i] + ".");
                    }
                }
            }
        }
    }

    private void declareLayers(CgVfxLook look) {
        Map<String, Integer> seen = new HashMap<>();
        for (CgVfxLayer layer : look.layers()) {
            String name = CgVfxLayout.segment(layer.name());
            int n = seen.merge(name, 1, Integer::sum);
            String key = "layer." + (n == 1 ? name : name + "_" + n);
            for (String property : layer.constants().keySet()) {
                float[] v = layer.constant(property);
                for (int i = 0; i < v.length; i++) {
                    int component = i;
                    String suffix = v.length == 1 ? "" : "." + (v.length <= 4 ? VECTOR[i] : String.valueOf(i));
                    declare(key + "." + CgVfxLayout.segment(property) + suffix, v[i],
                            value -> layer.tune(property, component, value),
                            property + " of layer " + layer.name() + " in " + prefix + ".");
                }
            }
        }
    }

    private void declare(String key, float value, Tuner tuner, String description) {
        CgVar.Num v = CgVar.number(prefix + "." + key, value, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
                CgVar.DEV, description);
        variables.put(key, v);
        v.onChange(tuner::tune);
        // A -D value was taken at declaration, before the hook.
        if (v.get() != value) tuner.tune(v.get());
    }

    /** Its variables, in the look's order: its values, then its layers'. */
    public List<CgVar<?>> variables() {
        return Collections.unmodifiableList(new ArrayList<>(variables.values()));
    }

    /** Every variable that differs from where it was opened, as YAML: what {@link #load} takes back. */
    public String overrides() {
        Map<String, Float> changed = new LinkedHashMap<>();
        for (Map.Entry<String, CgVar.Num> e : variables.entrySet()) {
            CgVar.Num v = e.getValue();
            if (v.get() != v.defaultValue()) changed.put(e.getKey(), v.get());
        }
        return CgFormats.YAML.toText(OVERRIDES, changed);
    }

    /** Writes {@link #overrides()} to {@code file}, making its folders. */
    public void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, overrides().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes {@code yaml}'s values as the file's: what it names is set, every other variable's file value dropped. A
     * name it does not know is logged and skipped.
     */
    public CgVfxTuning load(String yaml) {
        Map<String, Float> values = yaml == null || yaml.trim().isEmpty() ? Collections.emptyMap()
                : CgFormats.YAML.fromText(OVERRIDES, yaml);
        for (Map.Entry<String, CgVar.Num> e : variables.entrySet()) {
            Float value = values.get(e.getKey());
            if (value != null) e.getValue().set(CgVar.Source.FILE, value);
            else e.getValue().clear(CgVar.Source.FILE);
        }
        for (String key : values.keySet()) {
            if (!variables.containsKey(key)) {
                LOGGER.warn("[vfx tuning] {} names {}, which {} has not", followed != null ? followed : "YAML", key,
                        prefix);
            }
        }
        return this;
    }

    /**
     * Loads the asset at {@code path} now if it is there, and again each time it changes (hot reload, in a dev run).
     * One asset a tuning; following another replaces it.
     */
    public CgVfxTuning follow(String path) {
        this.followed = path;
        CgAssetReloader.track(this, Collections.singletonList(path));
        refresh();
        return this;
    }

    /** Reads the followed asset again: the hot reload's call. A file that does not parse keeps the values it had. */
    @Override
    public void refresh() {
        if (followed == null) return;
        String yaml = CgIO.loadSource(followed);
        if (yaml != null) load(yaml);
    }

    @Override
    public void close() {
        if (followed != null) CgAssetReloader.untrack(this);
        for (CgVar<?> v : variables.values()) CgVars.unregister(v);
        variables.clear();
    }

    @Override
    public String toString() {
        return "tuning " + prefix;
    }

    private interface Tuner {
        void tune(float value);
    }
}
