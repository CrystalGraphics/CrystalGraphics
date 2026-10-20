package com.crystalgraphics.serialization.format.file;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import com.crystalgraphics.serialization.CgCodecException;

/**
 * A YAML text as the plain tree, with the line every key and every list element sits on: what a reader needs to put a
 * file's comments where they belong. YAML 1.1's scalars as PyYAML reads them, with 1.2's exponent floats
 * ({@code 1.0e-05}) and no timestamps; maps keep their order.
 *
 * <pre>{@code
 * YamlDocument doc = YamlDocument.parse(text);
 * Object tree = doc.root();                       // LinkedHashMap, ArrayList, String, Integer, Double…
 * for (YamlDocument.Key k : doc.keys()) ...       // each key's line and path, in document order
 * for (YamlDocument.Element e : doc.elements()) ... // each map inside a list, with its first and last line
 * }</pre>
 *
 * <ul>
 *   <li>Lines count from 0. A path holds map keys and list indices from the root.</li>
 *   <li>Builds maps, lists and scalars only: a tag naming a class is refused.</li>
 * </ul>
 */
public final class YamlDocument {

    /** A map key: the line it starts on, its path, and whether it is its map's first. */
    public static final class Key {
        /** The line the key starts on, from 0. */
        public final int line;
        /** Map keys and list indices from the root, this key last. */
        public final List<Object> path;
        /** Whether it is its map's first key. */
        public final boolean first;

        Key(int line, List<Object> path, boolean first) {
            this.line = line;
            this.path = path;
            this.first = first;
        }
    }

    /** A map that is an item of a list: its first and last line, its path and the map itself. */
    public static final class Element {
        /** Its first and last line, from 0. */
        public final int start, end;
        /** Map keys and list indices from the root, its index last. */
        public final List<Object> path;
        /** The map, as it sits in {@link YamlDocument#root()}: a change to it is a change to the tree. */
        public final Map<Object, Object> value;

        Element(int start, int end, List<Object> path, Map<Object, Object> value) {
            this.start = start;
            this.end = end;
            this.path = path;
            this.value = value;
        }
    }

    private final Object root;
    private final List<Key> keys;
    private final List<Element> elements;

    private YamlDocument(Object root, List<Key> keys, List<Element> elements) {
        this.root = root;
        this.keys = Collections.unmodifiableList(keys);
        this.elements = Collections.unmodifiableList(elements);
    }

    /**
     * Parses {@code text}; an empty document's root is null.
     *
     * @throws CgCodecException if it is not YAML
     */
    public static YamlDocument parse(String text) {
        LoaderOptions options = new LoaderOptions();
        // A kit's baked curves pass SnakeYAML's 3 MB default.
        options.setCodePointLimit(Integer.MAX_VALUE);
        Scalars scalars = new Scalars(options);
        DumperOptions dump = new DumperOptions();
        Yaml yaml = new Yaml(scalars, new Representer(dump), dump, options, new NoTimestamps());
        Node node;
        try {
            node = yaml.compose(new StringReader(text));
        } catch (YAMLException e) {
            throw new CgCodecException("Not YAML: " + e.getMessage(), e);
        }
        List<Key> keys = new ArrayList<>();
        List<Element> elements = new ArrayList<>();
        Object root = node == null ? null : convert(scalars, node, Collections.emptyList(), keys, elements);
        return new YamlDocument(root, keys, elements);
    }

    /** The tree: maps ({@link LinkedHashMap}), lists, strings, numbers, booleans and null. */
    public Object root() {
        return root;
    }

    /** Every map key, in document order. */
    public List<Key> keys() {
        return keys;
    }

    /** Every map that is a list's item, in document order of their ends. */
    public List<Element> elements() {
        return elements;
    }

    private static Object convert(Scalars scalars, Node node, List<Object> path, List<Key> keys, List<Element> elements) {
        if (node instanceof MappingNode) {
            Map<Object, Object> map = new LinkedHashMap<>();
            List<NodeTuple> tuples = ((MappingNode) node).getValue();
            for (int i = 0; i < tuples.size(); i++) {
                NodeTuple t = tuples.get(i);
                Object key = scalars.construct(t.getKeyNode());
                List<Object> at = append(path, key);
                keys.add(new Key(t.getKeyNode().getStartMark().getLine(), at, i == 0));
                map.put(key, convert(scalars, t.getValueNode(), at, keys, elements));
            }
            return map;
        }
        if (node instanceof SequenceNode) {
            List<Node> items = ((SequenceNode) node).getValue();
            List<Object> list = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                Node item = items.get(i);
                List<Object> at = append(path, i);
                Object value = convert(scalars, item, at, keys, elements);
                if (value instanceof Map) {
                    int start = item.getStartMark().getLine();
                    // A block map ends at the next token, often column 0 of the line after its last.
                    int end = item.getEndMark().getLine() - (item.getEndMark().getColumn() == 0 ? 1 : 0);
                    @SuppressWarnings("unchecked") Map<Object, Object> map = (Map<Object, Object>) value;
                    elements.add(new Element(start, Math.max(end, start), at, map));
                }
                list.add(value);
            }
            return list;
        }
        return scalars.construct(node);
    }

    private static List<Object> append(List<Object> path, Object last) {
        List<Object> out = new ArrayList<>(path.size() + 1);
        out.addAll(path);
        out.add(last);
        return Collections.unmodifiableList(out);
    }

    /** SafeConstructor's scalars, one node at a time. */
    private static final class Scalars extends SafeConstructor {
        Scalars(LoaderOptions options) {
            super(options);
        }

        Object construct(Node node) {
            return constructObject(node);
        }
    }

    /** YAML 1.1's implicit types without timestamps: a date stays text. */
    private static final class NoTimestamps extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO", 10);
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<", 10);
            addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
            addImplicitResolver(Tag.NULL, EMPTY, null, 10);
            addImplicitResolver(Tag.YAML, YAML, "!&*", 10);
        }
    }
}
