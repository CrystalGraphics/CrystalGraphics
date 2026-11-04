# gl/buffer — VBO & Streaming Buffer System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

The buffers the GPU may still be reading while the CPU writes the next contents — vertex streams, and the
storage under every shader buffer — plus the global quad index buffer shared by all quad renderers.

This package should never contain VAO logic or vertex format knowledge — that belongs in `gl/vertex`. The
stream buffers here are format-agnostic byte pipes.

## Three kinds of stream, all on the frame ring

| | Factory | Lives |
|---|---|---|
| **Vertex stream** | `CgStreamBuffer.create(capacity)` | one frame: each upload at a new offset in this frame's region |
| **Shader-buffer storage** (`CgBufferLifetime.RETAINED`) | `CgStreamBuffer.createForShaderBuffer(target, capacity)` | until the next upload, always at offset 0: `RetainedStreamBuffer` writes each upload into one shared staging ring and copies it in on the GPU (Unreal's `RHIUpdateBuffer`, Vulkan's staging upload) |
| **Frame-local SSBO or UBO** (a shader buffer with `CgBufferLifetime.FRAME`) | `CgStreamBuffer.createFrameLocal(target, capacity)` | one frame, bound by range (`glBindBufferRange`) at each upload's offset; `CgShaderBuffer.uploadData` re-binds after every upload. Only for data every reading frame uploads first — the quad, curve and object buffers, the material and frame blocks (copied in at a frame's first material bind) and the text UBO. A UBO starts at its block size and doubles on overflow |

| Ring tier | Needs | What it costs |
|---|---|---|
| `PERSISTENT` | GL 4.4 / `ARB_buffer_storage` | nothing per upload: the storage is mapped once, coherent |
| `RING` | GL 3.2 sync + `glMapBufferRange` | one unsynchronised map per upload |

`CgCapabilities.vertexStreamTier()` decides it once per context and the factories only read it;
`-Dcrystalgraphics.stream.tier=persistent|ring` forces one, and a tier the context cannot do throws from
`CgCapabilities.detect()`. The orphan and subdata tiers went in rhi X2: the floor has the ring. The harness's
`capability-report` prints the tier chosen.

Which shader buffers go on the ring is a question of lifetime, not type: every frame that reads the buffer
uploads it first. Instance data meets it by being written just before its draw. A block written once meets it
when every reader passes a point that copies it into the frame: `CgMaterial.bind` does, for the material's
properties (uploaded only when their bytes changed, or on its first bind of a frame); the frame block is a
recorded pass's constants, packed into the frame's binding table; the text renderer re-uploads its block per frame.
Retained storage stays for a mod's own registry buffers, whose readers pass no such point. The copy is not made at
a frame's first host section: on Minecraft's Vulkan host that is the frame end's, before Minecraft's submit, where a
ring's wait for the frame three back is refused.

```
CgFrameRing (static clock)
├── FRAMES = 3 regions per ring; frame() advances at endFrame()
├── endFrame() — one glFenceSync per frame; CgGraphicsLifecycle.tickFrame() calls it, nothing else
├── awaitRetired(frame) — at a ring's first upload of a frame, for the frame FRAMES back
│   (profiler scope frameRing.wait; almost always already signalled)
└── reset() — context teardown

FrameRingStreamBuffer (PERSISTENT and RING)
├── one GL buffer = FRAMES regions; bump-allocated, 256-byte aligned
├── RING: map() → glMapBufferRange(UNSYNCHRONIZED | INVALIDATE_RANGE | FLUSH_EXPLICIT) at the cursor
├── PERSISTENT: glBufferStorage + one coherent persistent map; map() returns a slice, uploadFloats writes
│   through one float view of the whole mapping (rebuilt with it), commit() flushes nothing
├── RING small writes (a frame-local UBO, ≤256 B): one glBufferSubData at the reserved offset, not a
│   map/unmap pair
├── commit() → the offset, which the caller binds (a range for a shader buffer)
├── an offset is valid only in the frame that committed it: FRAMES later its bytes are overwritten
├── a frame that outgrows its region gets fresh storage at once, every region doubled, to 64 MB (only once:
│   new storage waits for a threaded driver to drain its queue) —
│   counted as frameRing.overflow. RING orphans under the same name; PERSISTENT storage is immutable, so it
│   takes a new buffer and bumps getGeneration(), which the bindings compare alongside the offset
└── a host that never ends a frame fills one region and renews storage: correct, unpipelined

RetainedStreamBuffer (shader-buffer storage)
├── map()/mapFloats()/write() hand out a region of one shared staging ring (GL_COPY_READ_BUFFER)
├── commit() → glCopyBufferSubData into its own storage at 0, growing it first; returns 0
└── releaseStaging() at context teardown, through CgStreamBuffer.releaseShared()

CgQuadIndexBuffer (global singleton)
├── shared IBO: pattern [0,1,2, 2,3,0, 4,5,6, 6,7,4, …]
├── GL_UNSIGNED_SHORT → max 16384 quads (65536/4 vertices)
├── lazy creation on first get(), initial grow to 256 quads minimum
├── doubling growth strategy, never shrinks
├── bindAndEnsureCapacity(neededQuads) — bind + grow if needed
├── freeAll() — static cleanup for context destroy
└── used by ALL quad renderers (text, UI, sprites)
```

## Key Design Decisions

- **No wait inside a frame.** The ring fences once per frame, and the only wait is at a frame's first
  upload, for the frame three back. The sync ring it replaced fenced every upload and waited mid-frame
  when it lapped, which a backend that records and submits later cannot do at all.
- **`commit()` returns the data offset** — new per upload on the ring, 0 for shader-buffer storage.
- **A vertex stream is not storage.** An upload read in a later frame reads another frame's bytes.
- **One quad IBO for everything** — `CgQuadIndexBuffer` is a global singleton; all quad-based renderers
  share it. Max 16384 quads due to `GL_UNSIGNED_SHORT`.

## Lifecycle Rules

1. **Creation**: through the two factories. The `gl/vertex` registry creates vertex streams; `CgShaderBuffer`
   creates shader-buffer storage.
2. **Per-frame upload**: `map(size)` → write into the returned `ByteBuffer` → `commit(usedBytes)` → draw at
   the returned offset. `afterSubmit()` is a no-op kept for existing callers.
3. **Cleanup**: stream buffers are deleted by their owners; `CgQuadIndexBuffer.freeAll()` and
   `CgFrameRing.reset()` run in `CgEngineLifecycle.shutdown()`.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gl/buffer/shader/` | `CgShaderBuffer` owns one shader-buffer storage |
| `lifecycle/` | `CgGraphicsLifecycle.tickFrame()` ends the ring's frame |

## File Map

| File | Role |
|------|------|
| `CgStreamBuffer.java` | Abstract base and the three factories. Fields: `glBuffer`, `target`, `capacityBytes`, `writeOffset`. |
| `CgFrameRing.java` | The frame clock: one fence per frame, `awaitRetired`. |
| `FrameRingStreamBuffer.java` | The two ring tiers, persistent and mapped. |
| `RetainedStreamBuffer.java` | Shader-buffer storage: staged in the ring, copied in at offset 0. |
| `CgQuadIndexBuffer.java` | Global shared quad IBO. Pattern `[0,1,2,2,3,0,...]`. Max 16384 quads. Doubling growth. |
| `CgReadback.java` | A buffer range, a framebuffer region or a 3D texture's box (`slices`, each slice attached in turn to one framebuffer bound for reading) read back without a stall: copied on the GPU into pooled staging, handed to a sink from `poll()` in `tickFrame` once `CgFrameRing` has retired the frame it was issued in (its own fence is queried only where the host has ended no frame: a query of a young fence waits for a threaded driver to drain). On the `PERSISTENT` tier the staging is mapped once, coherent, and read with no GL call; below it each delivery maps it, which on a threaded driver waits for the queue to drain. What `CgRecording.readback` and `CgPixelReadback` use. In-flight reads are dropped at teardown |
| `CgBufferReadback.java` | A buffer's words read now, a stall: for a count a draw needs this frame (G33) and the CPU tier's copies |

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
