# Example Usage

## Minimal on-screen usage

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

FloatBuffer projection = BufferUtils.createFloatBuffer(16);
populateOrthoMatrix(projection, screenWidth, screenHeight);
renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame, projection);
```

## Development integration example

If you want a live Minecraft-side example instead of a standalone snippet, see:

- `src/main/java/io/github/somehussar/crystalgraphics/mc/integration/CrystalGraphicsFontDemo.java`

That class contains:
- mouse-wheel zoom handling in `ClientTickEvent`
- visible on-screen rendering in `RenderGameOverlayEvent.Text`
- demo font creation and renderer setup

## Example orthographic matrix helper

```java
private void populateOrthoMatrix(FloatBuffer buffer, int width, int height) {
    buffer.clear();
    float left = 0.0f;
    float right = width;
    float bottom = height;
    float top = 0.0f;
    float near = -1.0f;
    float far = 1.0f;

    buffer.put(2.0f / (right - left)).put(0.0f).put(0.0f).put(0.0f);
    buffer.put(0.0f).put(2.0f / (top - bottom)).put(0.0f).put(0.0f);
    buffer.put(0.0f).put(0.0f).put(-2.0f / (far - near)).put(0.0f);
    buffer.put(-(right + left) / (right - left))
            .put(-(top + bottom) / (top - bottom))
            .put(-(far + near) / (far - near))
            .put(1.0f);
    buffer.flip();
}
```

## Wrapped text example

```java
CgTextLayout paragraph = layoutBuilder.layout(
        "BiDi, shaping, wrapping, and atlas caching all run through the same pipeline.",
        font,
        320.0f,
        200.0f);
renderer.draw(paragraph, font, 12.0f, 20.0f, 0xFFE8E8FF, frame, projection);
```

## Important runtime note

Call `registry.tickFrame(frame)` once per frame before drawing text so LRU usage and MSDF generation budget stay correct.
