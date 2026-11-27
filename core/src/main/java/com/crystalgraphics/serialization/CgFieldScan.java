package com.crystalgraphics.serialization;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * What {@link CgCodecs#fieldsOf} derives from a class: each non-static, non-{@code transient} field, superclasses
 * included, its codec found from its declared type, read and written through method handles made once.
 *
 * <p>Its rules are Gson's {@code ReflectiveTypeAdapterFactory} and {@code $Gson$Types}: {@code transient} and static
 * fields skipped, superclasses walked, a field's type resolved against the class it is read on, so a {@code List<T>} of
 * a generic superclass names its element. Written here to those rules, not copied from Gson.</p>
 */
final class CgFieldScan<A> {

    final Class<A> type;
    final MethodHandles.Lookup lookup;
    final Map<String, String> keys = new HashMap<>();
    final Set<String> skipped = new HashSet<>();
    final Map<Class<?>, CgCodec<?>> with = new HashMap<>();

    CgFieldScan(Class<A> type, MethodHandles.Lookup lookup) {
        this.type = type;
        this.lookup = lookup;
        if (isRecord(type)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is a record: give it CgCodecs.record(...), "
                    + "which builds it through its constructor");
        }
    }

    /** A record, here or as the Java 8 copy downgrades one: a final class over {@code Record} or its stand-in. */
    static boolean isRecord(Class<?> type) {
        Class<?> parent = type.getSuperclass();
        return parent != null && parent.getSimpleName().equals("Record") && Modifier.isFinal(type.getModifiers());
    }

    /** Its no-argument constructor as a factory, or null when it has none this lookup may call. */
    @Nullable
    Supplier<A> factory() {
        try {
            Constructor<A> made = type.getDeclaredConstructor();
            MethodHandle handle = lookup.unreflectConstructor(made);
            return () -> {
                try {
                    @SuppressWarnings("unchecked") A instance = (A) handle.invoke();
                    return instance;
                } catch (Throwable failed) {
                    throw new CgCodecException(type.getSimpleName() + " could not be made: " + failed);
                }
            };
        } catch (NoSuchMethodException | IllegalAccessException none) {
            return null;
        }
    }

    /** Each derived field, in its key's order, but those the builder skips or declares itself. */
    void deriveInto(CgObjectCodec.Builder<A> builder, Set<String> declared) {
        List<Field> found = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || field.isSynthetic()) continue;
                found.add(field);
            }
        }
        // KEYS IN NAME ORDER, never reflection's, which the JVM does not promise.
        found.sort(Comparator.comparing(this::keyOf));
        Set<String> seen = new HashSet<>();
        for (Field field : found) {
            if (skipped.contains(field.getName())) continue;
            String key = keyOf(field);
            if (!seen.add(key)) {
                throw new IllegalArgumentException(type.getSimpleName() + " has two fields keyed '" + key
                        + "' (a superclass's too): key(\"" + field.getName() + "\", \"another\") or skip one");
            }
            if (declared.contains(key)) continue;
            add(builder, field, key);
        }
    }

    private String keyOf(Field field) {
        return keys.getOrDefault(field.getName(), field.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void add(CgObjectCodec.Builder<A> builder, Field field, String key) {
        Type declared = resolve(field.getGenericType(), field.getDeclaringClass());
        CgCodec codec = codecFor(declared, field);
        MethodHandle getter = handle(field, true);
        Function<A, Object> get = owner -> {
            try {
                return getter.invoke(owner);
            } catch (Throwable failed) {
                throw new CgCodecException(field.getName() + " could not be read: " + failed);
            }
        };
        if (Modifier.isFinal(field.getModifiers())) {
            if (!updatesInPlace(declared, codec)) {
                throw new IllegalArgumentException(type.getSimpleName() + "." + field.getName() + " is final and a "
                        + raw(declared).getSimpleName() + " cannot be decoded in place: make it non-final, or skip(\""
                        + field.getName() + "\")");
            }
            builder.into(key, codec, get);
            return;
        }
        MethodHandle setter = handle(field, false);
        BiConsumer<A, Object> set = (owner, value) -> {
            try {
                setter.invoke(owner, value);
            } catch (Throwable failed) {
                throw new CgCodecException(field.getName() + " could not be set: " + failed);
            }
        };
        builder.field(key, codec, get, set);
    }

    private MethodHandle handle(Field field, boolean read) {
        try {
            MethodHandle handle = read ? lookup.unreflectGetter(field) : lookup.unreflectSetter(field);
            // Boxed both ways: the builder's functions take and answer objects
            return handle.asType(read ? handle.type().changeReturnType(Object.class).changeParameterType(0, Object.class)
                    : handle.type().changeParameterType(0, Object.class).changeParameterType(1, Object.class));
        } catch (IllegalAccessException denied) {
            throw new IllegalArgumentException(type.getSimpleName() + "." + field.getName() + " is not accessible here: "
                    + "pass the class's own MethodHandles.lookup() to fieldsOf, or skip(\"" + field.getName() + "\")");
        }
    }

    /** Whether a final field of this type can be refilled where it is: a collection, a map, an object codec. */
    private static boolean updatesInPlace(Type declared, CgCodec<?> codec) {
        Class<?> raw = raw(declared);
        return List.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw) || codec instanceof CgObjectCodec;
    }

    // ── A type's codec ──────────────────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CgCodec<?> codecFor(Type declared, Field field) {
        Class<?> raw = raw(declared);
        CgCodec<?> given = with.get(raw);
        if (given != null) return given;
        if (raw == int.class || raw == Integer.class) return CgCodecs.INT;
        if (raw == long.class || raw == Long.class) return CgCodecs.LONG;
        if (raw == float.class || raw == Float.class) return CgCodecs.FLOAT;
        if (raw == double.class || raw == Double.class) return CgCodecs.DOUBLE;
        if (raw == boolean.class || raw == Boolean.class) return CgCodecs.BOOL;
        if (raw == short.class || raw == Short.class) return CgCodecs.xmap(CgCodecs.INT, Integer::shortValue, Short::intValue);
        if (raw == byte.class || raw == Byte.class) return CgCodecs.xmap(CgCodecs.INT, Integer::byteValue, Byte::intValue);
        if (raw == char.class || raw == Character.class) {
            return CgCodecs.xmap(CgCodecs.STRING, s -> s.isEmpty() ? '\0' : s.charAt(0), String::valueOf);
        }
        if (raw == String.class) return CgCodecs.STRING;
        if (raw == UUID.class) return CgCodecs.UUID;
        if (raw == byte[].class) return CgCodecs.BYTES;
        if (raw.isEnum()) return CgCodecs.enumOf((Class) raw);
        if (raw.isArray()) return arrayCodec(raw.getComponentType(), declared, field);
        if (List.class.isAssignableFrom(raw)) return CgCodecs.listOf(codecFor(argument(declared, 0, field), field));
        if (Set.class.isAssignableFrom(raw)) {
            CgCodec<List<Object>> list = CgCodecs.listOf((CgCodec<Object>) codecFor(argument(declared, 0, field), field));
            return CgCodecs.xmap(list, l -> new LinkedHashSet<>(l), s -> new ArrayList<>((Collection<Object>) s));
        }
        if (Map.class.isAssignableFrom(raw)) {
            return CgCodecs.mapOf(codecFor(argument(declared, 0, field), field), codecFor(argument(declared, 1, field), field));
        }
        CgCodec<?> own = declaredCodec(raw);
        if (own != null) return own;
        throw new IllegalArgumentException(type.getSimpleName() + "." + field.getName() + " is a " + raw.getSimpleName()
                + ", which has no codec: give one with with(" + raw.getSimpleName() + ".class, codec), or skip(\""
                + field.getName() + "\")");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CgCodec<?> arrayCodec(Class<?> component, Type declared, Field field) {
        // By hand, element by element: the Java 8 copy runs these, and they are a save's inner loop
        if (component == int.class) return CgCodecs.xmap(CgCodecs.listOf(CgCodecs.INT), l -> {
            int[] out = new int[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return out;
        }, a -> {
            int[] in = (int[]) a;
            List<Integer> out = new ArrayList<>(in.length);
            for (int v : in) out.add(v);
            return out;
        });
        if (component == long.class) return CgCodecs.xmap(CgCodecs.listOf(CgCodecs.LONG), l -> {
            long[] out = new long[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return out;
        }, a -> {
            long[] in = (long[]) a;
            List<Long> out = new ArrayList<>(in.length);
            for (long v : in) out.add(v);
            return out;
        });
        if (component == double.class) return CgCodecs.xmap(CgCodecs.listOf(CgCodecs.DOUBLE), l -> {
            double[] out = new double[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return out;
        }, a -> {
            double[] in = (double[]) a;
            List<Double> out = new ArrayList<>(in.length);
            for (double v : in) out.add(v);
            return out;
        });
        if (component == float.class) return CgCodecs.xmap(CgCodecs.listOf(CgCodecs.FLOAT), l -> {
            float[] out = new float[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return out;
        }, a -> {
            float[] in = (float[]) a;
            List<Float> out = new ArrayList<>(in.length);
            for (float f : in) out.add(f);
            return out;
        });
        if (component == boolean.class) return CgCodecs.xmap(CgCodecs.listOf(CgCodecs.BOOL), l -> {
            boolean[] out = new boolean[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return out;
        }, a -> {
            boolean[] in = (boolean[]) a;
            List<Boolean> out = new ArrayList<>(in.length);
            for (boolean b : in) out.add(b);
            return out;
        });
        Type element = declared instanceof GenericArrayType g ? g.getGenericComponentType() : component;
        CgCodec<Object> each = (CgCodec<Object>) codecFor(element, field);
        return CgCodecs.xmap(CgCodecs.listOf(each), l -> l.toArray((Object[]) Array.newInstance(component, l.size())),
                a -> Arrays.asList((Object[]) a));
    }

    /** A class's own {@code public static final CgCodec CODEC}, or null. */
    @Nullable
    private static CgCodec<?> declaredCodec(Class<?> raw) {
        try {
            Field codec = raw.getField("CODEC");
            int mods = codec.getModifiers();
            if (Modifier.isStatic(mods) && CgCodec.class.isAssignableFrom(codec.getType())) return (CgCodec<?>) codec.get(null);
        } catch (NoSuchFieldException | IllegalAccessException none) {
            return null;
        }
        return null;
    }

    // ── Generic types, as $Gson$Types resolves them ─────────────────────────────────────────────

    /** {@code type}'s {@code index}th type argument; a raw collection or map has none and throws, naming the field. */
    private Type argument(Type declared, int index, Field field) {
        if (declared instanceof ParameterizedType p) return p.getActualTypeArguments()[index];
        throw new IllegalArgumentException(type.getSimpleName() + "." + field.getName() + " is a raw "
                + raw(declared).getSimpleName() + ": declare its element type, or give it with(...), or skip(\""
                + field.getName() + "\")");
    }

    /** A field's type with its declaring class's type variables replaced by what {@link #type} binds them to. */
    private Type resolve(Type declared, Class<?> owner) {
        if (declared instanceof TypeVariable<?> variable) {
            Type bound = bindingOf(variable, owner);
            return bound == null ? variable.getBounds()[0] : bound;
        }
        if (declared instanceof WildcardType wildcard) return resolve(wildcard.getUpperBounds()[0], owner);
        if (declared instanceof ParameterizedType p) {
            Type[] args = p.getActualTypeArguments().clone();
            boolean changed = false;
            for (int i = 0; i < args.length; i++) {
                Type now = resolve(args[i], owner);
                changed |= now != args[i];
                args[i] = now;
            }
            return changed ? new Parameterized((Class<?>) p.getRawType(), args) : p;
        }
        return declared;
    }

    /** What {@link #type}'s superclass chain binds {@code variable} of {@code owner} to, or null. */
    @Nullable
    private Type bindingOf(TypeVariable<?> variable, Class<?> owner) {
        // From the class read down to the declaring one: each generic superclass names the next one's variables
        List<Type> chain = new ArrayList<>();
        for (Class<?> at = type; at != null && at != owner; at = at.getSuperclass()) chain.add(at.getGenericSuperclass());
        Type wanted = variable;
        for (int i = chain.size() - 1; i >= 0 && wanted instanceof TypeVariable<?> v; i--) {
            if (!(chain.get(i) instanceof ParameterizedType p)) return null;
            TypeVariable<?>[] params = ((Class<?>) p.getRawType()).getTypeParameters();
            Type next = null;
            for (int k = 0; k < params.length; k++) if (params[k].getName().equals(v.getName())) next = p.getActualTypeArguments()[k];
            if (next == null) return null;
            wanted = next;
        }
        return wanted instanceof TypeVariable<?> ? null : wanted;
    }

    static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (type instanceof GenericArrayType g) return Array.newInstance(raw(g.getGenericComponentType()), 0).getClass();
        if (type instanceof WildcardType w) return raw(w.getUpperBounds()[0]);
        return Object.class;
    }

    /** A parameterized type made here, for a field whose type arguments were resolved. */
    private record Parameterized(Class<?> raw, Type[] args) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }
}
