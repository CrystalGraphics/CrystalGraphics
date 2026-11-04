package com.crystalgraphics.asset.tree;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;

/**
 * A file's inline children, each read as its own file would be: {@code children: { <name>: <delta> }} expanded into one
 * tree per child, with {@code parent:} the block holding it and the id {@code <that block's id>/<name>}. Everything
 * after reading (registration, the merge, decode, cook, hot reload) sees ordinary assets.
 *
 * <pre>{@code
 * # cues/ground_break.yaml: rpgcore:ground_break, rpgcore:ground_break/hard, rpgcore:ground_break/huge
 * _v: 1
 * tag: rpgcore:cue.ground_break
 * kind: burst
 * range: 32
 * burst:
 *   shake: { shake: rpgcore:ground_break/jolt, scale: 1, in_world: true, inner: 0, outer: 32, falloff: 1 }
 * children:
 *   hard: { range: 64, burst: { shake: { scale: 2, outer: 64 } } }    # tag rpgcore:cue.ground_break.hard
 *   huge: { range: 128, burst: { shake: { scale: 4, outer: 128 } } }
 * }</pre>
 *
 * <pre>{@code
 * Map<String, Map<String, Object>> trees = CgAssetChildren.expand("rpgcore:ground_break", CgGameplayKinds.CUE, file);
 * trees.get("rpgcore:ground_break/hard");   // { _v: 1, parent: rpgcore:ground_break, tag: ..., range: 64, burst: ... }
 * }</pre>
 *
 * <ul>
 *   <li>Children nest: a child's own {@code children} derive from it, {@code <its id>/<name>}.</li>
 *   <li>A child takes its file's {@code _v} and {@code units}, and writes neither, nor a {@code parent}.</li>
 *   <li>A {@link CgPropertyFlag#NAMES_ASSET} property a child leaves unwritten is its block's plus {@code .<name>}.</li>
 *   <li>A name takes an element id's characters: lower-case letters, digits and {@code _}, at most 32.</li>
 * </ul>
 */
public final class CgAssetChildren {

    /** The key a block writes its children under. */
    public static final String CHILDREN = CgType.CHILDREN;

    /** What a child's name matches. */
    public static final String NAME_PATTERN = "^[a-z0-9_]{1,32}$";

    private static final Pattern NAME = Pattern.compile(NAME_PATTERN);

    private CgAssetChildren() {
    }

    /** Whether {@code file} writes children inline. */
    public static boolean has(Map<?, ?> file) {
        return file.get(CHILDREN) != null;
    }

    /**
     * {@code file}'s assets in order, its own first and each child after the block holding it: the file's tree less its
     * children, then each child's as its own file would read. A file with no children is its own tree alone.
     *
     * @throws CgAssetException naming the file and the child, for a child that is not a map, has a bad name, or writes
     *                          {@code _v}, {@code parent} or {@code units}
     */
    public static Map<String, Map<String, Object>> expand(String id, CgType kind, Map<?, ?> file) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Object> own = copyWithout(file);
        out.put(id, own);
        Object children = file.get(CHILDREN);
        if (children != null) expand(id, id, kind, file, own, children, out);
        return out;
    }

    /**
     * The tree asset {@code id} reads from its file's {@code file}: its own, less its children, or the child's where
     * {@code inlineIn} names the file's asset (a registry entry's {@code inlineIn()}).
     *
     * @throws CgAssetException if the file no longer writes that child
     */
    public static Map<String, Object> tree(String id, @Nullable String inlineIn, CgType kind, Map<?, ?> file) {
        Map<String, Object> tree = expand(inlineIn != null ? inlineIn : id, kind, file).get(id);
        if (tree == null) throw new CgAssetException(id + ": its file no longer writes it under " + inlineIn + "'s children");
        return tree;
    }

    private static void expand(String fileId, String blockId, CgType kind, Map<?, ?> file, Map<String, Object> block,
                               Object children, Map<String, Map<String, Object>> out) {
        if (!(children instanceof Map<?, ?> named)) {
            throw new CgAssetException(fileId + ": " + where(fileId, blockId) + "children is not a map of name to delta");
        }
        for (Map.Entry<?, ?> e : named.entrySet()) {
            String name = String.valueOf(e.getKey());
            String id = blockId + "/" + name;
            if (!NAME.matcher(name).matches()) {
                throw new CgAssetException(fileId + ": child " + id + " is not named by lower-case letters, digits and _ (at most 32)");
            }
            if (!(e.getValue() instanceof Map<?, ?> delta)) {
                throw new CgAssetException(fileId + ": child " + id + " is not a map of what differs from " + blockId);
            }
            for (String own : new String[] {CgType.VERSION, CgType.PARENT, CgType.UNITS}) {
                if (delta.containsKey(own)) {
                    throw new CgAssetException(fileId + ": child " + id + " writes " + own + "; a child takes its file's");
                }
            }
            Map<String, Object> tree = new LinkedHashMap<>();
            if (file.containsKey(CgType.VERSION)) tree.put(CgType.VERSION, file.get(CgType.VERSION));
            tree.put(CgType.PARENT, blockId);
            if (file.containsKey(CgType.UNITS)) tree.put(CgType.UNITS, file.get(CgType.UNITS));
            for (Map.Entry<?, ?> d : delta.entrySet()) {
                if (!CHILDREN.equals(d.getKey())) tree.put(String.valueOf(d.getKey()), d.getValue());
            }
            for (CgProperty p : kind.properties()) {
                if (!p.has(CgPropertyFlag.NAMES_ASSET) || tree.containsKey(p.name())) continue;
                if (block.get(p.name()) instanceof String named2) tree.put(p.name(), named2 + "." + name);
            }
            out.put(id, tree);
            Object nested = delta.get(CHILDREN);
            if (nested != null) expand(fileId, id, kind, file, tree, nested, out);
        }
    }

    private static String where(String fileId, String blockId) {
        return blockId.equals(fileId) ? "" : "child " + blockId + "'s ";
    }

    private static Map<String, Object> copyWithout(Map<?, ?> file) {
        Map<String, Object> own = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : file.entrySet()) {
            if (!CHILDREN.equals(e.getKey())) own.put(String.valueOf(e.getKey()), e.getValue());
        }
        return own;
    }
}
