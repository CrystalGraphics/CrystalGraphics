package com.crystalgraphics.mc.shared;

/**
 * What a bootstrapper constructs once it has chosen a variant.
 *
 * <p>One jar carries every loader's entry classes and, above one Minecraft version per loader,
 * several of each. A loader can only be told one thing to construct, so what it constructs is a
 * bootstrapper; the bootstrapper reads {@code variants.json}, picks the row matching this loader and
 * this Minecraft version, and calls {@link #start} on the class that row names.</p>
 *
 * <pre>
 * public final class CrystalGUIForge implements VariantEntry {
 *     &#64;Override public void start(Object context) {
 *         FMLJavaModLoadingContext ctx = (FMLJavaModLoadingContext) context;   // see the table below
 *         ctx.getModEventBus().addListener(this::clientSetup);
 *     }
 * }
 * </pre>
 *
 * <p>What {@code context} is, per loader family — it is passed as {@code Object} because this module
 * may name no loader class:</p>
 *
 * <table>
 *   <tr><th>Family</th><th>{@code context}</th></tr>
 *   <tr><td>Fabric</td><td>{@code null} — Fabric hands an entry point nothing</td></tr>
 *   <tr><td>Forge</td><td>{@code FMLJavaModLoadingContext}; the mod event bus is {@code getModEventBus()}</td></tr>
 *   <tr><td>NeoForge</td><td>the {@code IEventBus} the {@code @Mod} constructor received</td></tr>
 * </table>
 *
 * <p>Easy to get wrong: an implementation needs a <b>public no-argument constructor</b>, because the
 * bootstrapper reaches it by name through reflection. It must also carry <b>no</b> {@code @Mod} or
 * {@code @EventBusSubscriber} — two variants annotated alike are two mods of one id as far as a
 * loader's scanner is concerned, and it refuses to load rather than picking one. Register listeners
 * from {@code start} instead.</p>
 */
public interface VariantEntry {

    /**
     * Called once, on the loader's own thread, with the loader's own context object.
     *
     * @param context the object named in the table above; never cast it without checking the family
     */
    void start(Object context);
}
