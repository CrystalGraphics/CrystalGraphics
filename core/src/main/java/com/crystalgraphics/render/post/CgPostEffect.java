package com.crystalgraphics.render.post;

import com.crystalgraphics.render.graph.CgScreenTexture;

/**
 * One effect of a post chain: given the picture so far, it answers the picture after it (Unreal's
 * {@code FPostProcessingPassDelegate}). Added at a {@link CgPostPoint} with {@link CgPost#add}.
 *
 * <pre>{@code
 * // Drawn over the picture where it stands: the common case
 * CgPost.Registration tint = CgPost.client().add(CgPostPoint.DISPLAY, (ctx, input) -> ctx.screen(tintMaterial).over(input));
 *
 * // Into a texture of its own, which the chain carries on from
 * CgPost.client().add(CgPostPoint.HDR, (ctx, input) -> {
 *     CgScreenTexture out = ctx.texture("mymod.outline", input);
 *     return ctx.screen(outline).reads(ctx.scene().customDepth()).into(out);
 * });
 *
 * // Nothing this frame
 * CgPost.client().add(CgPostPoint.SCENE, (ctx, input) -> enabled ? ctx.screen(heat).over(input) : input);
 * tint.close();                                   // removes it
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, inside the view's firing. Record only; nothing executes until the firing does.</li>
 *   <li>Answering {@code input} untouched draws nothing and costs nothing.</li>
 *   <li>At {@link CgPostPoint#SURFACES} and {@link CgPostPoint#TRANSLUCENT} answer {@code input}: the scene renderer goes
 *       on drawing into it. Another answer there throws.</li>
 *   <li>Allocate nothing per frame: keep materials and names in fields; {@code ctx.texture} keeps its textures.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgPostEffect {

    CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input);
}
