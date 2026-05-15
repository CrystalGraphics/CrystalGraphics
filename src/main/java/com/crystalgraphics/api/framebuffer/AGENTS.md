# api/framebuffer — Framebuffer Format Descriptor

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Public, backend-agnostic format descriptor for framebuffer attachments.
This package contains exactly one type:

- `CgFrameBufferFormat` — immutable descriptor of every attachment in a framebuffer.

The concrete framebuffer implementation lives in `gl/framebuffer/CgFrameBuffer`.

## CgFrameBufferFormat

Immutable value type describing the attachment layout of a framebuffer.
Built via a builder factory (`CgFrameBufferFormat.builder(name)`); validated at
build time (structural rules only — no GL calls).

### Builder API

```java
CgFrameBufferFormat fmt = CgFrameBufferFormat.builder("gbuffer")
        .color(0, CgTextureType.RGBA8)           // slot 0 → texture
        .color(1, CgTextureType.RGBA16F)          // slot 1 → texture
        .colorRbo(2, CgTextureType.RGBA8)         // slot 2 → renderbuffer
        .depth(CgTextureType.DEPTH24_STENCIL8)    // depth → texture
        .depthRbo(CgTextureType.DEPTH24_STENCIL8) // depth → renderbuffer
        .build();
```

### Validation Rules (build-time)

- At least one attachment (color or depth) must be declared.
- Color slot indices must be in `[0, 8)`.
- Depth types must have `isDepth == true`.
- Color types must have `isColor() == true`.

### Key Methods

| Method | Description |
|--------|-------------|
| `getActiveColorSlotIds()` | Sorted list of color slot indices that have an attachment |
| `colorSlotCount()` | Number of active color slots |
| `getColorSlot(int slot)` | `CgTextureType` for the given slot, or `null` |
| `isColorRenderbuffer(int slot)` | `true` if the slot is backed by a renderbuffer |
| `hasDepth()` | `true` if a depth attachment is declared |
| `getDepthType()` | `CgTextureType` for the depth attachment, or `null` |
| `isDepthRenderbuffer()` | `true` if depth is backed by a renderbuffer |
| `isDepthOnly()` | `true` if there are no color attachments |

### Equality

`equals`/`hashCode` are implemented — two formats with the same name, slot
configuration, and renderbuffer flags are equal. Safe as a `Map` key.

## Relationship to Other Packages

- **gl/framebuffer/CgFrameBuffer** — consumes `CgFrameBufferFormat` at construction time.
- **api/texture/CgTextureType** — provides the format constants used as slot descriptors.
