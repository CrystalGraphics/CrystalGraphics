# `gl/shader` — GL Shader Program Backends

Low-level, backend-specific shader program implementations. These classes compile and link GLSL source using whatever GL API is available (core GL20 or ARB_shader_objects). They are consumed exclusively by `CgShaderImpl` via `CgShaderFactory.compile()`.

---

## Class Map

### `CgShaderFactory`
Static facade. Entry point for all shader creation.

**Global singletons:**
- `SHADER_MANAGER` — the `CgShaderManagerImpl` instance used by the whole application. Access managed shaders via `CgShaderFactory.load(...)`.
- `JOML_BUFFER` — `ThreadLocal<FloatBuffer>` (16 elements) for zero-allocation matrix serialization.

**`load()` overloads** — delegate to `SHADER_MANAGER.load(...)`:
```java
CgShaderFactory.load("mymod:shaders/foo.vert", "mymod:shaders/foo.frag")
CgShaderFactory.load(vertRL, fragRL, CgVertexFormat)
```

**`fromSource()` overloads** — create an inline-mode `CgShaderImpl` via `CgShaderManagerImpl.createFromSource()`, compile eagerly, return handle. NOT cached in the shader manager. Callers must call `delete()` when done:
```java
CgShader s = CgShaderFactory.fromSource(vertSrc, fragSrc);
CgShader s = CgShaderFactory.fromSource(vertSrc, fragSrc, format);
```

**`compile()` overloads** — compile from raw GLSL strings directly (no caching, no managed lifecycle). Used internally by `CgShaderImpl.recompile()` after preprocessing:
```java
CgShaderProgram prog = CgShaderFactory.compile(vertSrc, fragSrc);
CgShaderProgram prog = CgShaderFactory.compile(vertSrc, fragSrc, format);
```
`CgShaderFactory.compile` is `CgShaderProgram.compile` (`api/shader`): one concrete class, every context meeting the
3.3 floor. `delete()` is idempotent.

---

## Invariants

- The caller of `CgShaderFactory.compile()` owns the program and calls `delete()` when done (managed shaders do this automatically on recompile or `delete()`).
- `format` passed to `compile()` is used for `glBindAttribLocation` calls before linking, so attribute indices match the VAO layout. Pass `null` if not needed (e.g. when using explicit `layout(location = N)` in GLSL).
- `CgShaderFactory` is not instantiable — constructor throws `AssertionError`.

---

Also loaded with this folder:

@../../../../../../../../docs/SHADERS.md
