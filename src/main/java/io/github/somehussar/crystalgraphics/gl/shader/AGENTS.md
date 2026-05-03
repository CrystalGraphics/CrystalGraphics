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
Waterfall: `CgCapabilities.isCoreShaders()` → `CgCoreShaderProgram`; `isArbShaders()` → `CgArbShaderProgram`; else throws `UnsupportedOperationException`.

### `CgAbstractShaderProgram`
Base class with ownership model and deleted-state tracking. Subclasses provide `bind()`, `unbind()`, and `getUniformLocation()`. `delete()` is idempotent.

### `CgCoreShaderProgram`
GL20 backend (`glCreateProgram`, `glUseProgram`, `glGetUniformLocation`, `glUniform*`). Used on all hardware that supports OpenGL 2.0 or later (which is essentially everything since 2007). Implements `getActiveUniforms()` via `GL20.glGetProgrami(GL_ACTIVE_UNIFORMS)` + `GL20.glGetActiveUniform`.

### `CgArbShaderProgram`
ARB_shader_objects backend. Same interface but uses `ARBShaderObjects.glCreateProgramObjectARB` etc. Fallback for pre-GL20 contexts. Rare in practice. Implements `getActiveUniforms()` via `glGetObjectParameteriARB(GL_OBJECT_ACTIVE_UNIFORMS_ARB)` + `glGetActiveUniformARB`.

---

## Invariants

- `CgShaderFactory.compile()` always returns an **owned** `CgShaderProgram` — the caller is responsible for calling `delete()` when done (managed shaders do this automatically on recompile or `delete()`).
- `format` passed to `compile()` is used for `glBindAttribLocation` calls before linking, so attribute indices match the VAO layout. Pass `null` if not needed (e.g. when using explicit `layout(location = N)` in GLSL).
- `CgShaderFactory` is not instantiable — constructor throws `AssertionError`.
