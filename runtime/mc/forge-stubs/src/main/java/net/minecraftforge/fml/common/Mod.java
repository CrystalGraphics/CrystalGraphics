package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-only: the union of legacy FML's {@code @Mod} (1.8–1.12.2: {@code modid}, {@code name},
 * {@code version}, {@code dependencies}) and modern Forge's ({@code value}). Both eras scan for this one
 * annotation name, so a bootstrapper compiled against it carries both elements in its class file and
 * each loader reads the one it knows.
 *
 * <pre>
 * &#64;Mod(value = "mymod", modid = "mymod", name = "My Mod", version = "1.0.0",
 *      dependencies = "required-after:othermod")
 * </pre>
 *
 * - Never shipped: the running Forge supplies the real annotation.
 * - Set every element explicitly — javac writes only what is set, and a loader reads only what is there.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    /** Modern Forge's mod id. */
    String value() default "";

    /** Legacy FML's mod id. */
    String modid() default "";

    String name() default "";

    String version() default "";

    /** Legacy FML's ordering, e.g. {@code required-after:crystalgraphics}. */
    String dependencies() default "";

    /** Legacy FML's handshake: which remote versions a connection accepts. {@code "*"} accepts an absent mod. */
    String acceptableRemoteVersions() default "";

    /** Legacy FML's lifecycle hook, found by reflection on the {@code @Mod} instance. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface EventHandler {
    }
}
