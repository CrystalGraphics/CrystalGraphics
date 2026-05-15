# `gl/state` — GL State Save/Restore Framework

## Class Map

### `CgGlState` (public)
Thin static dispatcher. Entry point for all state capture.

```java
// Save specific slots
try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
    // GL operations
}

// Convenience shorthands
CgGlState.saveProgram()  // → save(PROGRAM)
CgGlState.saveFull()     // → save(FBO, PROGRAM, TEXTURES, VERTEX_INPUT)
CgGlState.saveAll()      // → save all 16 slots
```

Mirror-vs-glGet trust is resolved **once** per `save()` call via:
- `crystalgraphics.boundary.forceGlGet` system property
- `CrystalGraphicsCoremod.isGapOnlyMode()` reflection
- `GLStateMirror.getCurrentFboFamily()` / `getCurrentProgramFamily()` trust check

No GL calls live in `CgGlState` itself — all GL access is in the `CgGlStates` inner classes.

### `CgGlScope` (public)
Thin `AutoCloseable`. Holds a `SlotState[]` indexed by `CgGlSlot.ordinal()`.

- `restore()` — iterates the array, calls `s.restore()` for each non-null entry; idempotent
- `close()` — delegates to `restore()`
- Constructor is package-private; only `CgGlState` creates instances
- **Zero slot-specific logic** — adding a new `CgGlSlot` constant requires no change here

### `CgGlStates` (package-private)
Container for all 16 GL state slot implementations. Pure namespace — no instance.

Each static inner class (`FboState`, `ProgramState`, `TextureState`, `VertexState`,
`BlendState`, `DepthState`, `CullState`, `StencilState`, `ColorMaskState`, `ViewportState`,
`ScissorState`, `PolygonOffsetState`, `AlphaTestState`, `LineWidthState`, `PolygonModeState`,
`PointSizeState`) follows this pattern:

```java
static final class XxxState implements SlotState {
    // private final fields
    private XxxState(...) { ... }
    static XxxState capture(...) { /* glGet calls */ return new XxxState(...); }
    @Override public void restore() { /* GL restore calls */ }
}
```

No public constructors, no accessors — inner classes are opaque outside `gl/state/`.

**Critical implementation notes:**
- `FboState` and `ProgramState` store `CallFamily` for correct cross-API restore dispatch
- `TextureState.capture()` wraps the `glActiveTexture` loop in `enterRedirect()`/`exitRedirect()` to prevent mirror corruption
- `VertexState.restore()` restores VAO **before** VBO/EBO — binding EBO while wrong VAO corrupts that VAO's recorded EBO
- `ScissorState` captures both enable bit and the 4-int scissor box

### `SlotState` (package-private interface)
Single method: `void restore()`. Implemented by all 16 inner classes of `CgGlStates`.

### `GLStateMirror` (public)
Pure-Java mirror of FBO, program, texture, VAO, and buffer bindings. No GL calls.
Updated by the ASM redirect layer. Used by `CgGlState` for the mirror-vs-glGet trust decision.

### `CallFamily` (public enum)
Tags a binding with the GL call family that produced it:
`CORE_GL30`, `ARB_FBO`, `EXT_FBO`, `OPENGLHELPER_WRAPPER`, `CORE_GL20`, `ARB_SHADER_OBJECTS`, `UNKNOWN`.

## Deleted (replaced)
- `CgStateBoundary` → replaced by `CgGlState`
- `CgStateSnapshot` → replaced by `CgGlScope`

## Usage Pattern

```java
// Minimal: save only what you modify
try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
    fbo.bind();
    shader.bind();
    // draw
}
// FBO and program restored here

// Shader convenience (via CgShader.bindScoped)
try (CgGlScope scope = shader.bindScoped()) {
    // renders with shader; PROGRAM slot auto-saved
}

// Full save for complex multi-pass effects
try (CgGlScope scope = shader.bindScoped(CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES)) {
    // all 3 slots restored on close
}
```
