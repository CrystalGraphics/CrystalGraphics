# gl/buffer — VBO & Streaming Buffer System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

The buffers the GPU may still be reading while the CPU writes the next contents — vertex streams, and the
storage under every shader buffer — plus the global quad index buffer shared by all quad renderers.

This package should never contain VAO logic or vertex format knowledge — that belongs in `gl/vertex`. The
stream buffers here are format-agnostic byte pipes.

## Two kinds of stream, each a waterfall of tiers

| | Factory | Tiers, best first | Lives |
|---|---|---|---|
| **Vertex stream** | `CgStreamBuffer.create(capacity)` | `PERSISTENT` > `RING` > `ORPHAN` > `SUBDATA` | on a ring tier, one frame: each upload at a new offset in this frame's region |
| **Shader-buffer storage** | `CgStreamBuffer.createForShaderBuffer(target, capacity)` | `ORPHAN` > `SUBDATA` | until the next upload, always at offset 0 |

| Tier | Class | Needs | What it costs |
|---|---|---|---|
| `PERSISTENT` | `FrameRingStreamBuffer(persistent)` | GL 4.4 / `ARB_buffer_storage` | nothing per upload: the storage is mapped once, coherent |
| `RING` | `FrameRingStreamBuffer` | GL 3.2 sync + `glMapBufferRange` | one unsynchronised map per upload |
| `ORPHAN` | `MapAndOrphanStreamBuffer` | `glMapBufferRange` | a driver-side rename per upload |
| `SUBDATA` | `SubDataStreamBuffer` | nothing | a CPU copy and `glBufferSubData` per upload — the path every driver gets right |

**The waterfall is not only a GL-version fallback.** At the 3.3 floor every tier but `PERSISTENT` is always
available, and the tiers still matter: drivers differ in which of them is fast or correct, which is why Dolphin
— where these tiers come from — chooses among them. `CgCapabilities` decides both tiers once per context,
`vertexStreamTier()` and `shaderStreamTier()`, and the factories only read them.
`-Dcrystalgraphics.stream.tier=persistent|ring|orphan|subdata` forces one; a tier the context cannot do throws
from `CgCapabilities.detect()`, and a shader buffer takes `subdata` if forced, else `orphan`. The harness's
`capability-report` prints the tier chosen.

Shader buffers stay off the ring because a material block is written once and bound for many frames,
`glBindBufferBase` reads at offset 0, and the TBO path runs exactly where `glTexBufferRange` is missing
(Mac 4.1, older Intel). On a backend that records rather than calls GL, an orphan is a fresh
sub-allocation — the same meaning, no change here.

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
├── PERSISTENT: glBufferStorage + one coherent persistent map; map() returns a slice, commit() flushes nothing
├── commit() → the offset; the caller re-points its VAO (CgVertexArrayBinding)
├── an offset is valid only in the frame that committed it: FRAMES later its bytes are overwritten.
│   Every caller draws straight after commit(); CgBatchRenderer's replay API, the one path that holds
│   an upload, throws when drawn in a later frame
├── a frame that outgrows its region gets fresh storage and the next frame's region doubles, to 64 MB —
│   counted as frameRing.overflow. RING orphans under the same name; PERSISTENT storage is immutable, so it
│   takes a new buffer and bumps getGeneration(), which the bindings compare alongside the offset
└── a host that never ends a frame fills one region and renews storage: correct, unpipelined

MapAndOrphanStreamBuffer (ORPHAN — vertex streams below the rings, and shader-buffer storage)
├── orphans on every map() (GL_MAP_INVALIDATE_BUFFER_BIT), commit() returns 0
└── uploadSmall(): glBufferSubData for writes ≤ 256 bytes, where the map call's fixed cost dominates

SubDataStreamBuffer (SUBDATA)
└── CPU staging ByteBuffer + glBufferSubData at offset 0

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
- **`commit()` returns the data offset** — new per upload on a vertex stream, 0 for shader-buffer storage.
  `CgVertexArrayBinding` and `CgInstanceVertexArrayBinding` re-point attributes from it.
- **A vertex stream is not storage.** An upload read in a later frame reads another frame's bytes.
- **One quad IBO for everything** — `CgQuadIndexBuffer` is a global singleton; all quad-based renderers
  share it. Max 16384 quads due to `GL_UNSIGNED_SHORT`.

## Lifecycle Rules

1. **Creation**: through the two factories. The `gl/vertex` registry creates vertex streams; `CgShaderBuffer`
   creates shader-buffer storage.
2. **Per-frame upload**: `map(size)` → write into the returned `ByteBuffer` → `commit(usedBytes)` → draw at
   the returned offset. `afterSubmit()` is a no-op kept for existing callers.
3. **Cleanup**: stream buffers are deleted by their owners; `CgQuadIndexBuffer.freeAll()` and
   `CgFrameRing.reset()` run in `CgGraphicsLifecycle.destroyContext()`.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gl/vertex/` | `CgVertexArrayBinding` owns one vertex stream per format |
| `gl/buffer/shader/` | `CgShaderBuffer` owns one shader-buffer storage |
| `gl/lifecycle/` | `CgGraphicsLifecycle.tickFrame()` ends the ring's frame |

## File Map

| File | Role |
|------|------|
| `CgStreamBuffer.java` | Abstract base and both factories. Fields: `glBuffer`, `target`, `capacityBytes`, `writeOffset`. |
| `CgFrameRing.java` | The frame clock: one fence per frame, `awaitRetired`. |
| `FrameRingStreamBuffer.java` | The two ring tiers, persistent and mapped. |
| `MapAndOrphanStreamBuffer.java` | The orphan tier: per upload, offset 0, small-write path. |
| `SubDataStreamBuffer.java` | The subdata tier: CPU staging + `glBufferSubData`. |
| `CgQuadIndexBuffer.java` | Global shared quad IBO. Pattern `[0,1,2,2,3,0,...]`. Max 16384 quads. Doubling growth. |
