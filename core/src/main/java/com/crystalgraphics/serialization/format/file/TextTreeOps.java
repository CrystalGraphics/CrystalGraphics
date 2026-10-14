package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;

import java.util.List;
import java.util.Map;

/** The plain tree with bytes as Base64 text: what a text format with no byte type (TOML, JSONC) holds. */
final class TextTreeOps implements CgDynamicOps<Object> {

    static final TextTreeOps INSTANCE = new TextTreeOps();

    private TextTreeOps() {
    }

    @Override public Object empty() { return CgPlainOps.INSTANCE.empty(); }
    @Override public Object createString(String value) { return value; }
    @Override public Object createNumber(Number value) { return value; }
    @Override public Object createBoolean(boolean value) { return value; }
    @Override public Object createList(List<Object> values) { return CgPlainOps.INSTANCE.createList(values); }
    @Override public Object createMap(Map<Object, Object> entries) { return CgPlainOps.INSTANCE.createMap(entries); }
    @Override public String getStringValue(Object value) { return CgPlainOps.INSTANCE.getStringValue(value); }
    @Override public Number getNumberValue(Object value) { return CgPlainOps.INSTANCE.getNumberValue(value); }
    @Override public boolean getBooleanValue(Object value) { return CgPlainOps.INSTANCE.getBooleanValue(value); }
    @Override public List<Object> getListValue(Object value) { return CgPlainOps.INSTANCE.getListValue(value); }
    @Override public Map<Object, Object> getMapValue(Object value) { return CgPlainOps.INSTANCE.getMapValue(value); }
    @Override public Object toPlain(Object value) { return value; }
}
