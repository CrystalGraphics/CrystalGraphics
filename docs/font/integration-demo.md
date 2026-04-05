# Integration Demo

A working development example is now wired into:

`src/main/java/io/github/somehussar/crystalgraphics/mc/integration/CrystalGraphicsFontDemo.java`

The integration test mod now only registers this demo class and keeps the
framebuffer self-check logic separate in:

`src/main/java/io/github/somehussar/crystalgraphics/mc/integration/CrystalGraphicsIntegrationTest.java`

This means font-demo-only debugging can now start from one class without
stepping through the framebuffer validation harness.

## What it does

- polls mouse wheel input in `ClientTickEvent`
- changes demo pose scale in steps of 0.1
- clamps zoom to `0.5x .. 4.0x`
- renders a 2D UI demo line demonstrating stable logical spacing under pose scale
- renders a 2D label explaining that logical size is stable while raster scales
- uses `CgTextRenderContext` for projection (created once, updated on resize)
- uses `PoseStack` for model-view transforms via the PoseStack-aware draw API

## Why render is not done directly in ClientTickEvent

`ClientTickEvent` is appropriate for input and state updates.
Visible HUD rendering belongs in a render event. The demo therefore uses:
- `ClientTickEvent` for wheel zoom updates
- `RenderGameOverlayEvent.Text` for actual on-screen drawing

This matches Forge/Minecraft timing correctly while still honoring the requested zoom behavior.

## Demo font source

The example currently uses the existing dev font file:

`freetype-harfbuzz-java-bindings/src/test/resources/test-font.ttf`

The integration code resolves that path relative to the project root at runtime.

## Demo flow

1. post-init registers the integration mod to both event buses
2. `ClientTickEvent` reads `Mouse.getDWheel()`
3. font size changes invalidate the current `CgFont`
4. overlay render lazily creates:
   - `CgFontRegistry`
   - `CgTextRenderer`
   - `CgFont`
   - `CgTextRenderContext` (orthographic, updated on viewport resize)
5. layout is built for the current demo string
6. a `PoseStack` is created for the draw call
7. renderer draws through the PoseStack-aware API with the render context

Code references in the dedicated demo class:
- `CrystalGraphicsFontDemo.java:46-61` - zoom and frame updates
- `CrystalGraphicsFontDemo.java:63-91` - overlay rendering
- `CrystalGraphicsFontDemo.java:93-107` - lazy font/renderer creation
- `CrystalGraphicsFontDemo.java:121-141` - orthographic matrix generation

## Self-check mode

The integration class also still contains the old framebuffer self-check suite,
but it is no longer active by default.

Enable it explicitly with:

```text
-Dcrystalgraphics.integration.runSelfChecks=true
```

That mode is separate from the interactive font demo.

## Example line

The current example draws:

`CrystalGraphics font demo - mouse wheel zoom [Npx]`

where `N` is the live font size.

## Debugging benefit of the extraction

All font-demo-only state is now isolated from the framebuffer self-check mod.
That means the following behaviors live in one class only:
- demo font lifecycle
- `ClientTickEvent` wheel zoom
- overlay text rendering
- orthographic projection helper

This makes it much easier to debug demo-only issues without stepping through
the unrelated framebuffer validation code.

## Known critical debug point

If draw calls are reached but no text is visible, check the orthographic
projection matrix in the `CgTextRenderContext`. The projection is built by
`CgTextRenderContext.orthographic(width, height)` and updated via
`context.updateOrtho(width, height)`. A wrong viewport size can make every
quad render off-screen while still hitting `glDrawElements`.

## Running it

Use the normal dev client path:

```bash
./gradlew.bat runClient
```

Then enter a world and scroll the mouse wheel to zoom the demo text in and out.
