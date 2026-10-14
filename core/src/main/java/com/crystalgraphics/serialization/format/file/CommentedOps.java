package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgDynamicOps;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A text format's tree as one value encodes it, each map's comments kept beside it for the writer: one per write.
 * Everything else is {@code base}'s.
 */
final class CommentedOps implements CgDynamicOps<Object> {

    private final CgDynamicOps<Object> base;
    private final Map<Object, Map<String, String>> comments = new IdentityHashMap<>();

    CommentedOps(CgDynamicOps<Object> base) {
        this.base = base;
    }

    @Override
    public Object withComments(Object map, Map<String, String> comments) {
        if (!(map instanceof Map)) return map;   // a string or number has nowhere to put one
        Map<String, String> had = this.comments.get(map);
        if (had == null) {
            this.comments.put(map, comments);
        } else {
            Map<String, String> both = new LinkedHashMap<>(had);
            both.putAll(comments);
            this.comments.put(map, both);
        }
        return map;
    }

    @Override
    public Map<String, String> commentsOf(Object map) {
        Map<String, String> of = comments.get(map);
        return of == null ? Collections.emptyMap() : of;
    }

    @Override public Object empty() { return base.empty(); }
    @Override public Object createString(String value) { return base.createString(value); }
    @Override public Object createNumber(Number value) { return base.createNumber(value); }
    @Override public Object createBoolean(boolean value) { return base.createBoolean(value); }
    @Override public Object createList(List<Object> values) { return base.createList(values); }
    @Override public Object createMap(Map<Object, Object> entries) { return base.createMap(entries); }
    @Override public Object createBytes(byte[] value) { return base.createBytes(value); }
    @Override public byte[] getBytesValue(Object value) { return base.getBytesValue(value); }
    @Override public String getStringValue(Object value) { return base.getStringValue(value); }
    @Override public Number getNumberValue(Object value) { return base.getNumberValue(value); }
    @Override public boolean getBooleanValue(Object value) { return base.getBooleanValue(value); }
    @Override public List<Object> getListValue(Object value) { return base.getListValue(value); }
    @Override public Map<Object, Object> getMapValue(Object value) { return base.getMapValue(value); }
}
