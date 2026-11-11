package com.crystalgraphics.vfx.look;

import com.crystalgraphics.render.post.volume.CgPostSettings;

import java.util.function.Consumer;

/**
 * The post looks a kit's effect opens as volumes while it plays (a flash, an impact frame), named so a look's precache
 * builds the composite passes they draw with before the effect plays: Unreal's component precaching its post
 * materials. Without it the frame a volume first turns a look on builds those passes.
 *
 * <pre>{@code
 * public static final CgVfxKit KIT = CgVfxKit.of("mymod:burst")
 *         .postLooks((look, out) -> out.accept(new CgPostSettings().flash(look.get(FLASH_STOPS))))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Name each settings the effect builds, from the look's values as the effect reads them: what is precached is the
 *       set of looks they switch on, so a value of 0 that switches one off must stay 0 here.</li>
 *   <li>Any thread; called by {@code CgVfxSystem.precache} on the render thread.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgVfxPostLooks {

    /** Hands {@code out} every settings an effect playing {@code look} opens a volume with. */
    void looks(CgVfxLook look, Consumer<CgPostSettings> out);
}
