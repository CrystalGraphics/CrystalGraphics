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
 * <p>Comments: a codec's own, made with its maps, are written above their keys, and a person's, in the file being
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
        CommentedOps ours = new CommentedOps(ops());
        Object tree = codec.encode(ours, value);
        Node old = previous == null ? null : composed(previous);
        Yaml yaml = yaml();
        Node now = yaml.represent(tree);
        comment(now, tree, old, ours, ours.commentsOf(tree).get(null));
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

    /**
     * Comments onto {@code now}, the node {@code tree} became: a person's from {@code old} where they wrote one, else
     * the codec's from the maps {@code ours} made. {@code header} goes above the first key, then a blank line.
     */
    private static void comment(Node now, Object tree, @Nullable Node old, CommentedOps ours, @Nullable String header) {
        if (now instanceof MappingNode && tree instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) tree;
            Map<String, String> notes = ours.commentsOf(tree);
            Map<String, NodeTuple> before = new HashMap<>();
            if (old instanceof MappingNode) {
                for (NodeTuple t : ((MappingNode) old).getValue()) {
                    if (t.getKeyNode() instanceof ScalarNode) before.put(((ScalarNode) t.getKeyNode()).getValue(), t);
                }
            }
            boolean first = true;
            for (NodeTuple t : ((MappingNode) now).getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode)) continue;
                String key = ((ScalarNode) t.getKeyNode()).getValue();
                Object value = values.get(key);
                NodeTuple was = before.get(key);
                List<CommentLine> theirs = was == null ? null : was.getKeyNode().getBlockComments();
                if (theirs != null && !theirs.isEmpty()) {
                    t.getKeyNode().setBlockComments(theirs);
                } else {
                    List<CommentLine> mine = new ArrayList<>();
                    if (first && header != null) {
                        mine.addAll(lines(header));
                        mine.add(new CommentLine(null, null, "", CommentType.BLANK_LINE));
                    }
                    String note = notes.get(key);
                    if (note == null && value != null) note = ours.commentsOf(value).get(null);
                    // A blank line above a commented key, the first in its map excepted, as TomlWriter does.
                    if (note != null && !first) mine.add(new CommentLine(null, null, "", CommentType.BLANK_LINE));
                    if (note != null) mine.addAll(lines(note));
                    if (!mine.isEmpty()) t.getKeyNode().setBlockComments(mine);
                }
                first = false;
                if (was != null) t.getValueNode().setInLineComments(was.getValueNode().getInLineComments());
                if (value != null) comment(t.getValueNode(), value, was == null ? null : was.getValueNode(), ours, null);
            }
        } else if (now instanceof SequenceNode && tree instanceof List) {
            List<?> values = (List<?>) tree;
            List<Node> is = ((SequenceNode) now).getValue();
            List<Node> was = old instanceof SequenceNode ? ((SequenceNode) old).getValue() : null;
            for (int i = 0; i < Math.min(is.size(), values.size()); i++) {
                Node then = was != null && i < was.size() ? was.get(i) : null;
                if (then != null) {
                    is.get(i).setBlockComments(then.getBlockComments());
                    is.get(i).setInLineComments(then.getInLineComments());
                }
                if (values.get(i) != null) comment(is.get(i), values.get(i), then, ours, null);
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
