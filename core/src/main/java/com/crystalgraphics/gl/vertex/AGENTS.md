# gl/vertex — the VAO wrapper

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

`CgVertexArray` is all that is left here: a VAO's creation, binding, attribute pointers from a `CgVertexFormat`, and
deletion. `CgMesh` uses its raw-id helpers for the one VAO each mesh owns. VAOs are core at the GL 3.3 floor.

- `gen()` warns when the driver returns a VAO name this process still owns -- the one cheap signal that two GL
  contexts are in play (1.7.10's splash screen; `CrystalGraphics/AGENTS.md` § *GL-thread rule*).
- `onContextDestroyed()` forgets the live names; `CgGraphicsLifecycle.destroyContext()` calls it.

The streaming and instanced VAO registries that lived here (`CgVertexArrayRegistry`, `CgVertexBufferRegistry`,
`CgVertexArrayBinding`, `CgInstanceVertexArrayBinding`, `CgVertexBuffer`, `CgInstanceVertexBuffer`) were deleted in
the mesh rewrite's M0 (2026-10-02). The rewrite's M5 moves meshes onto pooled buffers whose VAOs live inside the mesh
store, and this package goes with it.
