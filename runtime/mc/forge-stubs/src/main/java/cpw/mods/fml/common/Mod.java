package cpw.mods.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only: FML 1.7.10's {@code @Mod}, the elements a declaration sets. Never shipped. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String modid();

    String name() default "";

    String version() default "";

    String dependencies() default "";

    String acceptedMinecraftVersions() default "";

    String acceptableRemoteVersions() default "";
}
