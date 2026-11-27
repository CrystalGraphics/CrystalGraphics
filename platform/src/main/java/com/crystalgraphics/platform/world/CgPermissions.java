package com.crystalgraphics.platform.world;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * The loader's permission API, where it has one: what a host answers for CrystalGraphics' {@code CgPermission}. Forge
 * and NeoForge fill it; Fabric, Forge 1.8.9 and 1.7.10 have none, and the permission's own default answers.
 *
 * <pre>{@code
 * CgPlatform.provide(CgPermissions.HOST, new PermissionsForge());    // a host, at init
 * }</pre>
 *
 * <ul>
 *   <li>{@link Host#has} answers null while the loader's own default handler is active: its rule for an operators'
 *       node folds in the world's allow-cheats flag, which would refuse a single-player owner.</li>
 *   <li>A node declared after the loader gathered its nodes is not registered there, and is answered by its
 *       default.</li>
 * </ul>
 */
public final class CgPermissions {

    /** Who a node allows unless something says otherwise. */
    public enum Level {
        EVERYONE, OPERATORS, OWNER
    }

    public interface Host {

        /**
         * A node, {@code namespace:path}, declared: registered with the loader if it has a permission API. A
         * permission mod that asks for the node's default is answered by {@code byDefault}.
         */
        default void declared(String node, Level level, String description, Predicate<UUID> byDefault) {
        }

        /** Whether the online player {@code player} has {@code node}, as a permission mod answers; null for no answer. */
        default Boolean has(UUID player, String node) {
            return null;
        }

        /** No permission API. */
        Host NONE = new Host() {
        };
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:permissions", Host.NONE);

    /** One node as it was declared: what a host registers, whenever it is ready to. */
    public record Declared(String node, Level level, String description, Predicate<UUID> byDefault) {
    }

    private static final List<Declared> DECLARED = new CopyOnWriteArrayList<>();

    private CgPermissions() {
    }

    /** {@code node} declared to the loader's permission API, where there is one. */
    public static void declare(String node, Level level, String description, Predicate<UUID> byDefault) {
        DECLARED.add(new Declared(node, level, description, byDefault));
        host().declared(node, level, description, byDefault);
    }

    /**
     * Every node declared so far, in order: what a host registers when its loader gathers nodes, or when it is
     * provided after some were declared.
     */
    public static List<Declared> declared() {
        return Collections.unmodifiableList(DECLARED);
    }

    /** What a permission mod answers for {@code player} and {@code node}; null where none answers. */
    public static Boolean has(UUID player, String node) {
        return host().has(player, node);
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
