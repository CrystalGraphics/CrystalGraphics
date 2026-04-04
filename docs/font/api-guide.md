# Font API Guide

## Loading a font

```java
CgFont font = CgFont.load(
        "C:/path/to/font.ttf",
        CgFontStyle.REGULAR,
        24);
```

Or from bytes:

```java
byte[] fontData = ...;
CgFont font = CgFont.load(fontData, "my-font", CgFontStyle.REGULAR, 24);
```

## Building a layout

```java
CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();
CgTextLayout layout = layoutBuilder.layout(
        "Hello CrystalGraphics",
        font,
        400.0f,
        0.0f);
```

Parameters:
- text: UTF-16 Java string
- font: loaded `CgFont`
- maxWidth: line-wrap width, `<= 0` means unbounded
- maxHeight: vertical limit, `<= 0` means unbounded

## Creating the renderer

```java
CgCapabilities caps = CgCapabilities.detect();
CgFontRegistry registry = new CgFontRegistry();
CgTextRenderer renderer = CgTextRenderer.create(caps, registry);
```

## Drawing text

```java
FloatBuffer projection = ...; // 4x4 orthographic matrix
long frame = 1L;
registry.tickFrame(frame);
renderer.draw(layout, font, 20.0f, 40.0f, 0xFFFFFFFF, frame, projection);
```

## Cleanup

```java
renderer.delete();
font.dispose();
registry.releaseAll();
```

## Lifecycle notes

- `CgFont` owns native font resources
- `CgFontRegistry` owns glyph atlases
- `CgTextRenderer` owns shaders and VBO
- dispose/delete these explicitly

## Public contracts worth knowing

### `CgFont`
- `getKey()`
- `getMetrics()`
- `dispose()`
- `isDisposed()`

### `CgTextLayoutBuilder`
- `layout(...)`

### `CgTextRenderer`
- `create(...)`
- `draw(...)`
- `delete()`

### `CgFontRegistry`
- `ensureGlyph(...)`
- `tickFrame(...)`
- `releaseFontAtlases(...)`
- `releaseAll()`
