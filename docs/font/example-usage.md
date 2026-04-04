# Example Usage

## Minimal on-screen usage (PoseStack-aware, recommended)

```java
CgCapabilities caps = CgCapabilities.detect();
CgFontRegistry registry = new CgFontRegistry();
CgTextRenderer renderer = CgTextRenderer.create(caps, registry);
CgFont font = CgFont.load("C:/fonts/MyFont.ttf", CgFontStyle.REGULAR, 24);
CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

long frame = 1L;
registry.tickFrame(frame);

CgTextLayout layout = layoutBuilder.layout(
        "CrystalGraphics font demo",
        font,
        600.0f,
        0.0f);

// Create a render context once (or on viewport resize)
CgTextRenderContext context = CgTextRenderContext.orthographic(screenWidth, screenHeight);

// PoseStack for model-view transforms
PoseStack poseStack = new PoseStack();

renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame, context, poseStack);
```

## Scaled text via PoseStack

```java
// Pose scaling increases the effective raster size for sharper rendering
// without changing the logical layout dimensions.
PoseStack poseStack = new PoseStack();
poseStack.last().pose().scale(2.0f);  // 2x zoom → effective 48px from base 24px

renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame, context, poseStack);
// Layout metrics (width, height) remain in base 24px logical coordinates
```

## Legacy compatibility (deprecated)

The old projection-matrix-per-draw API still works:

```java
FloatBuffer projection = BufferUtils.createFloatBuffer(16);
populateOrthoMatrix(projection, screenWidth, screenHeight);
renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame, projection);
```

This is a backward-compatible wrapper that uses identity pose (no scale).
Prefer the PoseStack-aware API for new code.

## Development integration example

If you want a live Minecraft-side example instead of a standalone snippet, see:

- `src/main/java/io/github/somehussar/crystalgraphics/mc/integration/CrystalGraphicsFontDemo.java`

That class contains:
- mouse-wheel zoom handling in `ClientTickEvent`
- visible on-screen rendering in `RenderGameOverlayEvent.Text`
- demo font creation and renderer setup
- PoseStack-aware draw path with `CgTextRenderContext`

## Wrapped text example

```java
CgTextLayout paragraph = layoutBuilder.layout(
        "BiDi, shaping, wrapping, and atlas caching all run through the same pipeline.",
        font,
        320.0f,
        200.0f);
renderer.draw(paragraph, font, 12.0f, 20.0f, 0xFFE8E8FF, frame, context, poseStack);
```

## Important runtime note

Call `registry.tickFrame(frame)` once per frame before drawing text so LRU usage and MSDF generation budget stay correct.
