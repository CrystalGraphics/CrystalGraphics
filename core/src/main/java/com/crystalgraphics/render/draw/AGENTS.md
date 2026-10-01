# render/draw — what a recorded draw is made of

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md). Plan: `plan/crystalgraphics/render-graph.md`.

The vocabulary of the frame graph's recorded draws. **Everything here a recorder touches is CPU only**, because a
recorder will run on a UI document's own thread (`render-graph` §3.1); GL happens when the render thread executes.

| Type | Is | Thread |
|---|---|---|
| `CgPipeline` | a key: shader asset + pass + keyword set + render state + instance kind, interned to an `int` | `of`/`byId` any thread; `prepare`/`program`/`bind`/`instanceBase` render thread |
| `CgBindingTable` | a recording's binding snapshots: block bytes copied, textures and buffers as handles, interned by content | filled by one recorder; `upload`/`bind`/`delete` on the render thread after handover |
| `CgInstanceKind` | what instances are: `QUAD`, `CURVE`, `OBJECT`, and the record stride each writes | — |
| `CgPassConstants` | the pass block (`CgFrameBlock`'s layout) as a value, one per pass | any |

```java
// recording, any thread:
CgPipeline p = material.pipeline(CgInstanceKind.QUAD);
int bindings = material.captureBindings(table);
int pass = constants.capture(table);

// execution, render thread:
table.upload();
table.bind(pass);
if (p.bind()) { table.bind(bindings); p.instanceBase(first); /* draw */ }
```

## Rules

- **A recorder never reaches GL.** `pipeline()` parses at most (`CgMaterialShader.ensureParsed()`); it never
  compiles. `captureBindings()` reads no GL name.
- **A texture in a snapshot is a handle**, resolved at `bind`: one reallocated before execution binds its new
  storage. Never capture a `CgTextureMutable` whose id another thread rewrites.
- **A sampler's unit is its index among the shader's declared samplers.** `CgMaterialShader` wires every program
  that way at compile, so nothing per material is left to wire at execution.
- **`cg_InstanceBase`** is what `CG_INSTANCE_ID` adds to `gl_InstanceID`; it defaults to 0, so a draw that uploads
  its own instances needs nothing.
- A pipeline lives for the session, and is keyed by the *identity* of its render state: the first compile reuses the
  parse of unchanged source, so a key named before it is the one that draws after it.
