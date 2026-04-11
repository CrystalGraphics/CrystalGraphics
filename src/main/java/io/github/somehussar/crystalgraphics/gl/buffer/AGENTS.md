# gl/buffer — VBO & Streaming Buffer System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

VBO (Vertex Buffer Object) streaming backend and shared index buffer.
Owns per-frame dynamic vertex data upload via multiple streaming strategies
and the global quad index buffer shared by all quad renderers.

This package should never contain VAO logic or vertex format knowledge —
that belongs in `gl/vertex`. The stream buffers here are format-agnostic
byte pipes.

## Ownership Model

```
CgStreamBuffer (abstract base)
├── owns one GL VBO id (GL_ARRAY_BUFFER target)
├── tracks: glBuffer, target, capacityBytes, writeOffset
├── map(sizeBytes) → ByteBuffer for CPU writes
├── commit(usedBytes) → byte offset where data starts in the GL buffer
├── bind()/unbind() — raw GL_ARRAY_BUFFER bind
├── waterfall factory: create() picks best strategy via CgCapabilities
└── one instance per CgVertexFormat (lifecycle owned by CgVertexArrayBinding)

MapAndSyncStreamBuffer (Tier A — preferred)
├── 3-slot ring buffer (RING_FRAMES = 3), 256-byte aligned slots
├── GL fence sync via ARB_sync: glFenceSync / glClientWaitSync
├── CPU writes slot N while GPU reads N-1, N-2
├── map() waits on the slot's fence, then maps with UNSYNCHRONIZED + FLUSH_EXPLICIT
├── commit() flushes, places fence, advances to next slot
├── oversize uploads (> slotSize) fall back to single-shot orphan path
├── orphan path deletes all fences and resets ring state
├── fence timeout: 5 seconds → throws IllegalStateException on GPU hang
└── requires: ARB_sync + glMapBufferRange (CgCapabilities.isArbSync())

MapAndOrphanStreamBuffer (Tier B)
├── full-buffer orphan on every map() (GL_MAP_INVALIDATE_BUFFER_BIT)
├── no sync fences — driver manages backing store internally
├── commit() always returns offset 0
├── auto-grows: if sizeBytes > capacityBytes, re-allocates via glBufferData
└── requires: glMapBufferRange (CgCapabilities.isMapBufferRangeSupported())

SubDataStreamBuffer (Tier C — baseline fallback)
├── CPU-side staging ByteBuffer (BufferUtils.createByteBuffer)
├── map() returns the staging buffer (clears + limits to requested size)
├── commit() calls glBufferSubData at offset 0, returns offset 0
├── auto-grows: re-creates staging buffer + GL buffer if needed
└── GL 1.5 only — works on all hardware

CgQuadIndexBuffer (global singleton)
├── shared IBO: pattern [0,1,2, 2,3,0, 4,5,6, 6,7,4, …]
├── GL_UNSIGNED_SHORT → max 16384 quads (65536/4 vertices)
├── lazy creation on first get(), initial grow to 256 quads minimum
├── doubling growth strategy, never shrinks
├── bindAndEnsureCapacity(neededQuads) — bind + grow if needed
├── freeAll() — static cleanup for context destroy
└── used by ALL quad renderers (text, UI, sprites)
```

## Streaming Strategy Waterfall

Selection happens in `CgStreamBuffer.create(capacityBytes)`:

```
CgCapabilities.detect()
  isArbSync()               → MapAndSyncStreamBuffer  (best: no stalls, ring)
  isMapBufferRangeSupported() → MapAndOrphanStreamBuffer (okay: driver orphan)
  else                      → SubDataStreamBuffer      (safe: CPU staging)
```

The caller never picks a strategy — the factory always selects the best
available path. All three strategies implement the same `map()/commit()`
contract and are interchangeable from the consumer's perspective.

## Key Design Decisions

- **Waterfall is automatic** — `CgStreamBuffer.create()` picks the best
  strategy. No manual configuration needed.
- **Sync ring avoids stalls** — triple-buffering with fences means the CPU
  never waits for the GPU unless 3+ frames behind.
- **Orphan fallback for oversize** — in the sync path, data exceeding one
  ring slot triggers a single-shot orphan that deletes all fences and resets
  ring state. This is a correctness requirement, not an optimization.
- **commit() returns data offset** — the sync ring returns different offsets
  per slot (slot * slotSize). Orphan and subdata always return 0. The caller
  (`CgVertexArrayBinding`) uses this offset to rebind VAO attribute pointers.
- **One quad IBO for everything** — `CgQuadIndexBuffer` is a global singleton;
  all quad-based renderers share it. Max 16384 quads due to `GL_UNSIGNED_SHORT`.
- **No buffer mapping state tracking** — the stream buffers do not participate
  in the `GLStateMirror` system. They use direct `GL15.glBindBuffer` calls.

## Lifecycle Rules

1. **Creation**: Always through `CgStreamBuffer.create(capacityBytes)`.
   The `gl/vertex` registry handles this — do not create stream buffers
   directly unless building a non-vertex buffer use case.
2. **Per-frame upload**: `map(size)` → write into returned `ByteBuffer` →
   `commit(usedBytes)`. The buffer must be bound when `commit()` is called.
3. **Cleanup**: Stream buffers are deleted by their owning `CgVertexArrayBinding`.
   `CgQuadIndexBuffer.freeAll()` cleans up the shared IBO.
4. **Auto-grow**: All strategies auto-grow when requested size exceeds capacity.
   Growth re-allocates the GL buffer. The sync ring also re-computes slot sizes.

## Relationship to Other Packages

| Package | Relationship |
|---------|-------------|
| `gl/vertex/` | `CgVertexArrayBinding` owns one `CgStreamBuffer` per format |
| `gl/pass/` | Render passes use the quad IBO for indexed quad draws |
| `api/` | `CgCapabilities` drives the streaming strategy waterfall |

## File Map

| File | Role |
|------|------|
| `CgStreamBuffer.java` | Abstract streaming VBO base. Fields: `glBuffer`, `target`, `capacityBytes`, `writeOffset`. Factory: `create()`. |
| `MapAndSyncStreamBuffer.java` | Tier A: 3-slot ring buffer, `ARB_sync` fences, 256-byte alignment, 5s fence timeout. |
| `MapAndOrphanStreamBuffer.java` | Tier B: orphan-based streaming, `GL_MAP_INVALIDATE_BUFFER_BIT`, auto-grow. |
| `SubDataStreamBuffer.java` | Tier C: CPU staging `ByteBuffer` + `glBufferSubData`. GL 1.5 baseline. |
| `CgQuadIndexBuffer.java` | Global shared quad IBO. Pattern `[0,1,2,2,3,0,...]`. Max 16384 quads. Doubling growth. |
