package com.crystalgraphics.asset.registry;

/**
 * Where an asset's bytes come from, in the order a namespace's sources are tried: a builder's asset first, then a dev
 * run's source folders, a jar's cook, a jar's source YAML. Minecraft's resource packs are never one: a pack that
 * changed an asset on one client would break what both sides agree on.
 */
public enum CgAssetSource {
    /** Registered by a builder at init. A file of the same id is an error. */
    CODE,
    /** A dev run's source folder ({@code src/main/resources/assets/<namespace>/}), or a folder handed to a scan. */
    DEV,
    /** A jar's {@code assets/<namespace>/cooked/}. */
    COOKED,
    /** A jar's source YAML, for a mod that ships no cook: read, and warned once. */
    JAR
}
