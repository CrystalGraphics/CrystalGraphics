package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
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
 *   current: { kind: float, fallback: 0.0, sync: owner, persisted: true }   # kept with the entity
 *   charging: { kind: bool }
 * events:
 *   overcharged: { payload: float }
 * entities: players
 * }</pre>
 * <pre>{@code
 * CgNetType<?> ki = CgNetType.named("mymod:ki");          // declared as the game started, on both sides
 * ki.of(player).setFloat((CgNetField<Float>) ki.field("current"), 80f);
 * }</pre>
 *
 * <ul>
 *   <li>Every file compiles at pre-init on each side ({@code CgAssetMounts.declareNetTypes}), before any entity enters
 *       or anyone connects.</li>
 *   <li>The same type in Java is {@code CgNetType.define("mymod:ki").fields(...).onEntities(...)}: a file and a
 *       declaration of the same shape are one type.</li>
 *   <li>A field's {@code persisted: true} saves it with the entity, through chunk unloads; the type's saves every field.
 *       A local field persisted fails at load, naming it.</li>
 *   <li>A field's {@code sync}: {@code all} (the default), {@code owner} (its owner alone), {@code local} (never sent)
 *       or {@code watched} (the players watching its object alone).</li>
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

    static CgNetType<?> compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) {
            for (CgIssue issue : v.issues()) {
                if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(asset.id() + ": " + issue.path() + ": " + issue.message());
            }
        }
        Map<Object, Object> tree = asset.resolved();
        String id = asset.id();
        try {
            return builder(id, tree, true).buildOnce();
        } catch (IllegalArgumentException | IllegalStateException bad) {
            throw new CgAssetException(id + ": " + bad.getMessage());
        }
    }

    /**
     * Declares the {@code net_type} file {@code yaml} as {@code id} for one client {@code connection} alone: a server's
     * type on a client without its mod, found by that connection before the process's types and gone with it. Two
     * servers' types of one name never meet.
     *
     * <pre>{@code
     * CgNetTypeCompiler.declareFor(connection, "shop:stall", Files.readString(mounted.resolve("net_types/stall.yaml")));
     * }</pre>
     *
     * <ul>
     *   <li>A name this process declares is refused: an installed mod's types are never replaced by a server's.</li>
     *   <li>Its {@code entities} rule is ignored: a connection's type is never put on entities.</li>
     * </ul>
     *
     * @throws CgAssetException naming the file and what is wrong
     */
    public static CgNetType<?> declareFor(CgProtocolConnection<Object> connection, String id, String yaml) {
        Object tree;
        try {
            tree = CgAssetYaml.read(yaml);
        } catch (RuntimeException bad) {
            throw new CgAssetException(id + ": " + bad.getMessage());
        }
        if (!(tree instanceof Map<?, ?> map)) throw new CgAssetException(id + ": not a net_type");
        @SuppressWarnings("unchecked") Map<Object, Object> declared = (Map<Object, Object>) map;
        try {
            CgNetType<?> type = builder(id, declared, false).buildForConnection();
            connection.attachment(ClientObjects.class, ClientObjects::new).declare(type);
            return type;
        } catch (IllegalArgumentException | IllegalStateException bad) {
            throw new CgAssetException(id + ": " + bad.getMessage());
        }
    }

    /** The type a file's resolved tree declares, not yet built; a connection's has no entities to name. */
    @SuppressWarnings("unchecked")
    private static CgNetType.Builder<CgNetObject> builder(String id, Map<Object, Object> tree, boolean onEntities) {
        boolean persisted = Boolean.TRUE.equals(tree.get("persisted"));
        List<CgNetField<?>> fields = new ArrayList<>();
        if (tree.get("fields") instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> e : declared.entrySet()) {
                String name = String.valueOf(e.getKey());
                Map<?, ?> f = (Map<?, ?>) e.getValue();
                String sync = f.get("sync") == null ? "all" : String.valueOf(f.get("sync"));
                if (Boolean.TRUE.equals(f.get("persisted")) && sync.equals("local")) {
                    throw new CgAssetException(id + ": fields." + name + ": a local field is never saved, and it is persisted");
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
        CgNetType.Builder<CgNetObject> type = CgNetType.define(id).fields(fields.toArray(new CgNetField<?>[0]))
                .events(events.toArray(new CgNetEvent<?>[0]));
        // In wire order: a call is a command that answers
        if (tree.get("commands") instanceof List<?> declared) {
            for (Object c : declared) {
                if (!(c instanceof Map<?, ?> m) || m.get("name") == null) throw new CgAssetException(id + ": commands: each has a name");
                String name = String.valueOf(m.get("name"));
                Object payload = m.get("payload"), result = m.get("result");
                if (result != null) {
                    CgCodec<Object> args = payload == null ? CgCodecs.PLAIN : (CgCodec<Object>) codec(String.valueOf(payload));
                    type.calls(CgNetCall.of(name, args, (CgCodec<Object>) codec(String.valueOf(result))));
                } else {
                    type.commands(payload == null ? CgNetCommand.of(name) : CgNetCommand.of(name, codec(String.valueOf(payload))));
                }
            }
        }
        if (Boolean.TRUE.equals(tree.get("watched"))) type.watched();
        if (onEntities) type.onEntities(entities(id, tree.get("entities")));
        if (persisted) type.persisted();
        return type;
    }

    /**
     * The {@code net_type} file of a type declared in Java: what a server offers a client that lacks its mod, which
     * declares it for that connection ({@link #declareFor}) and reads every field, sends every command and hears every
     * event as the mod would. A codec key is {@code kind: value}, read as its plain tree ({@code CgCodecs.PLAIN}).
     *
     * <pre>{@code
     * String yaml = CgNetTypeCompiler.describe(Bank.ACCOUNT);
     * CgServerMounts.offer("mybank").type(Bank.ACCOUNT);       // the same file, offered
     * }</pre>
     *
     * <ul>
     *   <li>Fields, commands and calls keep their order: they are numbered by it on the wire.</li>
     *   <li>A key a part, a map or a keyed list holds, or a fixed-width wire, has no file: it is refused by name.</li>
     * </ul>
     *
     * @throws IllegalArgumentException naming what has no file
     */
    public static String describe(CgNetType<?> type) {
        StringBuilder out = new StringBuilder("{\"_v\": 1");
        if (type.watched) out.append(", \"watched\": true");
        out.append(", \"fields\": {");
        boolean first = true;
        for (CgNetField<?> f : type.fields) {
            if (f instanceof CgNetList) throw new IllegalArgumentException(type.name + "." + f.name + ": a keyed list has no net_type file");
            out.append(first ? "" : ", ").append(quote(f.name)).append(": {\"kind\": ").append(quote(kindOf(type.name, f)));
            first = false;
            String sync = f.serverOnly ? "server" : f.local ? "local" : f.ownerOnly ? "owner" : f.watched ? "watched" : null;
            if (sync != null) out.append(", \"sync\": ").append(quote(sync));
            if (f.initialOnly) out.append(", \"initial_only\": true");
            if (f.skipOwner) out.append(", \"skip_owner\": true");
            if (f.command != null) out.append(", \"predicted\": true");
            if (f.kind != CgNetField.Kind.CODEC && f.fallback != null) out.append(", \"fallback\": ").append(f.fallback);
            out.append('}');
        }
        out.append("}, \"events\": {");
        first = true;
        for (CgNetEvent<?> e : type.events) {
            if (e.wire != null) throw new IllegalArgumentException(type.name + "." + e.name + ": a fixed-width event has no net_type file");
            out.append(first ? "" : ", ").append(quote(e.name)).append(": {");
            if (e.codec != null) out.append("\"payload\": ").append(quote(codecKind(e.codec)));
            out.append('}');
            first = false;
        }
        out.append("}, \"commands\": [");
        first = true;
        for (CgNetCommand<?> c : type.commands) {
            if (c.field != null) continue;                      // a predicted field's, made again from the field
            if (c.wire != null) throw new IllegalArgumentException(type.name + "." + c.name + ": a fixed-width command has no net_type file");
            out.append(first ? "" : ", ").append("{\"name\": ").append(quote(c.name));
            if (c.codec != null) out.append(", \"payload\": ").append(quote(codecKind(c.codec)));
            if (c.call != null) out.append(", \"result\": ").append(quote(codecKind(c.call.result)));
            out.append('}');
            first = false;
        }
        return out.append("]}").toString();
    }

    private static String kindOf(String type, CgNetField<?> f) {
        return switch (f.kind) {
            case INT -> "int";
            case LONG -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BOOL -> "bool";
            case CODEC -> codecKind(f.codec);
        };
    }

    private static String codecKind(CgCodec<?> codec) {
        if (codec == CgCodecs.BOOL) return "bool";
        if (codec == CgCodecs.INT) return "int";
        if (codec == CgCodecs.LONG) return "long";
        if (codec == CgCodecs.FLOAT) return "float";
        if (codec == CgCodecs.DOUBLE) return "double";
        if (codec == CgCodecs.STRING) return "string";
        return "value";
    }

    private static String quote(String text) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') q.append('\\');
            q.append(c);
        }
        return q.append('"').toString();
    }

    private static CgNetField<?> field(String id, String name, Map<?, ?> f, String sync) {
        Object fallback = f.get("fallback");
        CgNetField<?> field = switch (String.valueOf(f.get("kind"))) {
            case "bool" -> fallback == null ? CgNetField.ofBool(name) : CgNetField.ofBool(name).fallback((Boolean) fallback);
            case "int" -> fallback == null ? CgNetField.ofInt(name) : CgNetField.ofInt(name).fallback(number(id, name, fallback).intValue());
            case "long" -> fallback == null ? CgNetField.ofLong(name) : CgNetField.ofLong(name).fallback(number(id, name, fallback).longValue());
            case "float" -> fallback == null ? CgNetField.ofFloat(name) : CgNetField.ofFloat(name).fallback(number(id, name, fallback).floatValue());
            case "double" -> fallback == null ? CgNetField.ofDouble(name) : CgNetField.ofDouble(name).fallback(number(id, name, fallback).doubleValue());
            case "value" -> CgNetField.of(name, CgCodecs.PLAIN);
            default -> fallback == null ? CgNetField.of(name, CgCodecs.STRING) : CgNetField.of(name, CgCodecs.STRING).fallback(String.valueOf(fallback));
        };
        switch (sync) {
            case "owner" -> field.ownerOnly();
            case "local" -> field.local();
            case "watched" -> field.watched();
            case "server" -> field.serverOnly();
            default -> {
            }
        }
        if (Boolean.TRUE.equals(f.get("initial_only"))) field.initialOnly();
        if (Boolean.TRUE.equals(f.get("skip_owner"))) field.skipOwner();
        if (Boolean.TRUE.equals(f.get("predicted"))) field.predicted();
        if (Boolean.TRUE.equals(f.get("persisted"))) field.persisted();
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
            case "string" -> CgCodecs.STRING;
            default -> CgCodecs.PLAIN;
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
