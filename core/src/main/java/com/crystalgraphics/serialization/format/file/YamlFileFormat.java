package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.format.CgFormats;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgPlainOps;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.representer.Representer;

import javax.annotation.Nullable;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CgFormats#YAML}: SnakeYAML over the plain tree, block style. {@link SafeConstructor} only: a file builds maps,
 * lists and scalars, never a class it names.
 *
 * <p>Comments: a codec's own ({@link CgCodec#comments}) are written above their keys, and a person's, in the file being
 * replaced, are carried to the same key path; theirs win. A comment on a key no longer written is dropped.</p>
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

    @Override
    <V> String text(CgCodec<V> codec, V value, @Nullable String previous) {
        Map<String, String> ours = codec.comments();
        Node old = previous == null ? null : composed(previous);
        Object tree = codec.encode(ops(), value);
        if (ours.isEmpty() && old == null) return print(tree);
        Yaml yaml = yaml();
        Node now = yaml.represent(tree);
        comment(now, old, ours, "");
        if (old != null) now.setEndComments(old.getEndComments());
        StringWriter out = new StringWriter();
        yaml.serialize(now, out);
        return out.toString();
    }

    /** {@code previous} as nodes with its comments, or null where it is not YAML: it is replaced either way. */
    @Nullable
    private static Node composed(String previous) {
        try {
            return yaml().compose(new StringReader(previous));
        } catch (YAMLException notYaml) {
            return null;
        }
    }

    private static void comment(Node now, @Nullable Node old, Map<String, String> ours, String path) {
        if (now instanceof MappingNode) {
            Map<String, NodeTuple> before = new HashMap<>();
            if (old instanceof MappingNode) {
                for (NodeTuple t : ((MappingNode) old).getValue()) {
                    if (t.getKeyNode() instanceof ScalarNode) before.put(((ScalarNode) t.getKeyNode()).getValue(), t);
                }
            }
            for (NodeTuple t : ((MappingNode) now).getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode)) continue;
                String key = ((ScalarNode) t.getKeyNode()).getValue();
                String at = path.isEmpty() ? key : path + "." + key;
                NodeTuple was = before.get(key);
                List<CommentLine> theirs = was == null ? null : was.getKeyNode().getBlockComments();
                if (theirs != null && !theirs.isEmpty()) t.getKeyNode().setBlockComments(theirs);
                else if (ours.containsKey(at)) t.getKeyNode().setBlockComments(lines(ours.get(at)));
                if (was != null) {
                    t.getValueNode().setInLineComments(was.getValueNode().getInLineComments());
                    comment(t.getValueNode(), was.getValueNode(), ours, at);
                } else {
                    comment(t.getValueNode(), null, ours, at);
                }
            }
        } else if (now instanceof SequenceNode && old instanceof SequenceNode) {
            List<Node> was = ((SequenceNode) old).getValue();
            List<Node> is = ((SequenceNode) now).getValue();
            for (int i = 0; i < Math.min(was.size(), is.size()); i++) {
                is.get(i).setBlockComments(was.get(i).getBlockComments());
                is.get(i).setInLineComments(was.get(i).getInLineComments());
            }
        }
    }

    private static List<CommentLine> lines(String text) {
        List<CommentLine> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) out.add(new CommentLine(null, null, " " + line, CommentType.BLOCK));
        return out;
    }

    /** A Yaml is not thread-safe; one per call. */
    private static Yaml yaml() {
        DumperOptions dump = new DumperOptions();
        dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dump.setIndent(2);
        dump.setProcessComments(true);
        LoaderOptions load = new LoaderOptions();
        load.setProcessComments(true);
        return new Yaml(new SafeConstructor(load), new Representer(dump), dump, load);
    }
}
