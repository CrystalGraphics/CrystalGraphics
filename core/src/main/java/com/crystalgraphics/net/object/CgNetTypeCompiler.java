package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * The {@code net_type} kind: a networked type declared in a file, compiled into the {@link CgNetType} its id names, the
 * same a mod would declare in Java. Unreal's replicated properties of a class, here as data; who holds one is a rule of
 * the file ({@code entities}: put on the entities it lists as they enter a level).
 *
 * <pre>{@code
 * # assets/mymod/net_types/ki.yaml        -> the type mymod:ki
 * _v: 1
 * fields:
 *   current: { kind: float, fallback: 0.0, sync: owner }
 *   charging: { kind: bool }
 * events:
 *   overcharged: { payload: float }
 * entities: players
 * }</pre>
 * <pre>{@code
 * CgNetType ki = CgNetType.named("mymod:ki");          // declared as the game started, on both sides
 * ki.of(player).setFloat((CgNetField<Float>) ki.field("current"), 80f);
 * }</pre>
 *
 * <ul>
 *   <li>Every file compiles at pre-init on each side ({@code CgAssetMounts.declareNetTypes}), before any entity enters
 *       or anyone connects.</li>
 *   <li>The same type in Java is {@code CgNetType.define("mymod:ki").fields(...).onEntities(...)}: a file and a
 *       declaration of the same shape are one type.</li>
 *   <li>{@code persisted: true} fails at load: an entity's object is not saved with it yet.</li>
 *   <li>A reload that changes its fields, events or holders fails by name until a restart.</li>
 * </ul>
 */
public final class CgNetTypeCompiler {

    private CgNetTypeCompiler() {
    }

    /** Sets it as the {@code net_type} kind's compiler. {@code CgBuiltInCompilers.register} calls it. */
    public static void register() {
        CgAssetCompiler.register(CgGameplayKinds.NET_TYPE, CgNetTypeCompiler::compile);
    }

    static CgNetType compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) {
            for (CgIssue issue : v.issues()) {
                if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(asset.id() + ": " + issue.path() + ": " + issue.message());
            }
        }
        Map<Object, Object> tree = asset.resolved();
        String id = asset.id();
        boolean persisted = Boolean.TRUE.equals(tree.get("persisted"));
        List<CgNetField<?>> fields = new ArrayList<>();
        if (tree.get("fields") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> e : declared.entrySet()) {
                String name = String.valueOf(e.getKey());
                Map<?, ?> f = (Map<?, ?>) e.getValue();
                String sync = f.get("sync") == null ? "all" : String.valueOf(f.get("sync"));
                if (persisted && sync.equals("local")) {
                    throw new CgAssetException(id + ": fields." + name + ": a local field is never saved, and the type is persisted");
                }
                fields.add(field(id, name, f, sync));
            }
        }
        List<CgNetEvent<?>> events = new ArrayList<>();
        if (tree.get("events") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> e : declared.entrySet()) {
                Object payload = e.getValue() instanceof Map<?, ?> m ? m.get("payload") : null;
                String name = String.valueOf(e.getKey());
                events.add(payload == null ? CgNetEvent.of(name) : CgNetEvent.of(name, codec(String.valueOf(payload))));
            }
        }
        if (persisted) {
            // An entity's object rides its entity, and saving into the entity's own data is not built yet.
            throw new CgAssetException(id + ": persisted: an entity's properties are not saved with it yet");
        }
        CgNetType.Builder type = CgNetType.define(id).fields(fields.toArray(new CgNetField<?>[0]))
                .events(events.toArray(new CgNetEvent<?>[0])).onEntities(entities(id, tree.get("entities")));
        try {
            return type.buildOnce();
        } catch (IllegalArgumentException | IllegalStateException bad) {
            throw new CgAssetException(id + ": " + bad.getMessage());
        }
    }

    private static CgNetField<?> field(String id, String name, Map<?, ?> f, String sync) {
        Object fallback = f.get("fallback");
        CgNetField<?> field = switch (String.valueOf(f.get("kind"))) {
            case "bool" -> fallback == null ? CgNetField.ofBool(name) : CgNetField.ofBool(name).fallback((Boolean) fallback);
            case "int" -> fallback == null ? CgNetField.ofInt(name) : CgNetField.ofInt(name).fallback(number(id, name, fallback).intValue());
            case "long" -> fallback == null ? CgNetField.ofLong(name) : CgNetField.ofLong(name).fallback(number(id, name, fallback).longValue());
            case "float" -> fallback == null ? CgNetField.ofFloat(name) : CgNetField.ofFloat(name).fallback(number(id, name, fallback).floatValue());
            case "double" -> fallback == null ? CgNetField.ofDouble(name) : CgNetField.ofDouble(name).fallback(number(id, name, fallback).doubleValue());
            default -> fallback == null ? CgNetField.of(name, CgCodecs.STRING) : CgNetField.of(name, CgCodecs.STRING).fallback(String.valueOf(fallback));
        };
        switch (sync) {
            case "owner" -> field.ownerOnly();
            case "local" -> field.local();
            default -> {
            }
        }
        if (Boolean.TRUE.equals(f.get("initial_only"))) field.initialOnly();
        if (Boolean.TRUE.equals(f.get("predicted"))) field.predicted();
        return field;
    }

    private static Number number(String id, String name, Object fallback) {
        if (fallback instanceof Number n) return n;
        throw new CgAssetException(id + ": fields." + name + ".fallback: a number, not " + fallback);
    }

    private static CgCodec<?> codec(String kind) {
        return switch (kind) {
            case "bool" -> CgCodecs.BOOL;
            case "int" -> CgCodecs.INT;
            case "long" -> CgCodecs.LONG;
            case "float" -> CgCodecs.FLOAT;
            case "double" -> CgCodecs.DOUBLE;
            default -> CgCodecs.STRING;
        };
    }

    /** {@code entities}: a list of entity type ids, {@code players} or {@code all}. */
    private static Predicate<String> entities(String id, @Nullable Object entities) {
        if ("all".equals(entities)) return type -> true;
        if ("players".equals(entities)) return CgNetEntities.PLAYER::equals;
        if (!(entities instanceof List<?> list)) throw new CgAssetException(id + ": entities: a list of entity type ids, players or all");
        Set<String> types = new HashSet<>();
        for (Object type : list) types.add(String.valueOf(type));
        return types::contains;
    }
}
