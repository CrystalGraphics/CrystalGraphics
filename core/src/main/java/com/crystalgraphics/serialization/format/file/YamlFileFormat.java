package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgPlainOps;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;

/**
 * {@link CgFormats#YAML}: SnakeYAML over the plain tree, block style. {@link SafeConstructor} only: a file builds maps,
 * lists and scalars, never a class it names.
 */
public final class YamlFileFormat extends TextFileFormat<Object> {

    @Override
    CgDynamicOps<Object> ops() {
        return CgPlainOps.INSTANCE;
    }

    @Override
    String print(Object tree) {
        return yaml().dump(tree);
    }

    @Override
    Object parse(String text) {
        try {
            return yaml().load(text);
        } catch (YAMLException e) {
            throw new CgCodecException("Not YAML: " + e.getMessage(), e);
        }
    }

    /** A Yaml is not thread-safe; one per call. */
    private static Yaml yaml() {
        DumperOptions dump = new DumperOptions();
        dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dump.setIndent(2);
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(dump), dump);
    }
}
