# Text Rendering Guide

## Pipeline Overview

```
Input Text (Unicode)
    ↓
[HarfBuzz Shaping] — complex script handling, ligatures, kerning
    ↓
Glyph IDs + Positions
    ↓
[FreeType Rasterization] — glyph → bitmap with hinting
    ↓
Positioned Bitmaps → OpenGL Texture
```

## Step-by-Step

### 1. Initialize Libraries
```java
FreeTypeLibrary ft = FreeTypeLibrary.create();
FTFace face = ft.newFace("path/to/font.ttf", 0);
face.setPixelSizes(0, 24);
HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
```

### 2. Shape Text
```java
HBBuffer buffer = HBBuffer.create();
buffer.addUTF8("مرحبا بالعالم"); // Arabic: "Hello World"
buffer.setDirection(HBDirection.HB_DIRECTION_RTL);
buffer.setScript(HBScript.HB_SCRIPT_ARABIC);
buffer.setLanguage("ar");
HBShape.shape(hbFont, buffer);
```

Or let HarfBuzz guess:
```java
buffer.addUTF8("Hello, World!");
buffer.guessSegmentProperties();
HBShape.shape(hbFont, buffer);
```

### 3. Render Glyphs
```java
HBGlyphInfo[] infos = buffer.getGlyphInfos();
HBGlyphPosition[] positions = buffer.getGlyphPositions();
int cursorX = 0, cursorY = 0;

for (int i = 0; i < infos.length; i++) {
    face.loadGlyph(infos[i].getCodepoint(), FTLoadFlags.FT_LOAD_RENDER);
    FTBitmap bmp = face.getGlyphBitmap();

    int drawX = cursorX + (positions[i].getXOffset() >> 6) + bmp.getLeft();
    int drawY = cursorY - (positions[i].getYOffset() >> 6) - bmp.getTop();

    // Upload bmp.getBuffer() to GL texture at (drawX, drawY)
    // Width: bmp.getWidth(), Height: bmp.getHeight()

    cursorX += positions[i].getXAdvance() >> 6;
    cursorY += positions[i].getYAdvance() >> 6;
}
```

### 4. Cleanup (Order Matters!)
```java
buffer.destroy();    // 1. HB buffer first
hbFont.destroy();    // 2. HB font second (references FT_Face)
face.destroy();      // 3. FT face third
ft.destroy();        // 4. FT library last
```

## OpenType Features

Control ligatures, kerning, and more:
```java
HBShape.shape(hbFont, buffer, new String[] {
    "kern",   // Enable kerning
    "-liga",  // Disable standard ligatures
    "+smcp",  // Enable small caps
});
```

## Changing Font Size

After changing FT_Face pixel sizes, sync the HBFont:
```java
face.setPixelSizes(0, 32);
FreeTypeHarfBuzzIntegration.syncFontMetrics(hbFont, face);
```

## CrystalGraphics Integration

In a Minecraft mod using CrystalGraphics:

```java
// In your mod's init:
FreeTypeLibrary ft = FreeTypeLibrary.create();
FTFace face = ft.newFace(fontPath, 0);
face.setPixelSizes(0, scale);
HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

// In render tick (on GL thread):
// Create texture from shaped text
// Use CgFramebuffer for off-screen rendering if needed
// Bind shader program via CgShaderProgram
```
