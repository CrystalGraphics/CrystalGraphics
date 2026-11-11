# gl/framebuffer — targets as device textures

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

A `CgFrameBuffer` is a set of device textures a pass draws into: colour slots and a depth, as a
`CgFrameBufferFormat` lays them out. There is no GL framebuffer object behind an owned one. Nothing binds it: an
execution takes it as its current target through `CgExecutor.into(target)`, and a graph pass attaches its textures.

## Names

Every framebuffer has an `int` name (`getId()`), what the frame graph, readbacks and a host frame's
`mainFramebuffer` key by:

- **Owned**: a name the engine gives it, from `0x40000000` up, above any a GL driver hands out.
  `CgFrameBuffer.owned(id)` finds it until deleted.
- **Wrapped** (`wrap(name, fbo, w, h)`): a host's framebuffer, its attachments imported (`CgHostImports`);
  framebuffer 0 is the window's. Owns nothing; `delete()` does nothing.

`CgFrameBuffer.attachment(fbo, depth, w, h)` answers a texture view for either kind: an owned one's own, else the
import. Use it, never `CgHostImports.attachment`, wherever a name may be ours.

## What it does

| Concern | |
|---|---|
| `create` (registry, by name and format), `createOwned`, `createOnDevice` (with `Images` placing each slot: the graph's transients), `createVolume` (a `CgTexture3D`, nothing draws into it; or around one the caller made, its sampling and levels kept), `createArray` (a `CgTexture2DArray`) | factories |
| `rhiColor(slot)`, `rhiDepth()` — the device textures a pass attaches | attachments |
| `getColorTexture(slot)`, `getDepthTexture()` — the sampleable textures; null for an attach-only slot | attachments |
| A renderbuffer or multisampled slot is an attach-only device texture: a pass attaches it, nothing samples it | attachments |
| `clear*` — a render pass of its own with clear loads; depth in GL's convention, mirrored for a reversed host | device |
| `readColor` (RGBA8), `readTexture`/`readTextureFloats` (static, any texture as RGBA8 / RGBA32F) — read now, waiting for the GPU, rows bottom first | device |
| `copyColorTo(fbo)`, `copyColorAndDepthTo(fbo)` — a blit into another framebuffer at the same size: a main target into the window | device |
| `depthTypeOf(fbo, w, h)` — a framebuffer's depth type, from its attachment's format | names |
| `resize`, `delete` — remake or release its textures; the name stays until deleted | lifecycle |

## Registry

```java
CgFrameBuffer fbo = CgFrameBuffer.create("shadow_map", 1024, 1024, SHADOW_FORMAT);   // cached by name and format
CgFrameBuffer hdr = CgFrameBuffer.createScreenSized("hdr_buffer", HDR_FORMAT);       // resized in place
CgGraphicsLifecycle.onResize(newWidth, newHeight);
```

`CgFrameBufferRegistry` holds what `create` and `createScreenSized` made; `deleteAll()` at shutdown. A
`createOwned` framebuffer is the caller's to resize and delete.

## Reading a framebuffer back without a stall — `CgPixelReadback`

```java
CgPixelReadback readback = new CgPixelReadback(3);
readback.request(fbo.getId(), fbo.getWidth(), fbo.getHeight(), 256, tag);   // shrink on the device, queue the read
readback.poll(pixels -> use(pixels.tag(), pixels.rgb()));                     // every frame; never waits
```

`readColor` waits for the GPU to finish everything before it. This halves the source with linear blits, then reads it
back through `CgReadback` (`gl/buffer`), landing a frame or two later. Copy the landed bytes out in ONE bulk `get`: a
get per byte from the mapped buffer measured 2-3 ms for a 256x144 picture.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
